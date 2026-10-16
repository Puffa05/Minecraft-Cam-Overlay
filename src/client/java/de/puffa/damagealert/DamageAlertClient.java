package de.puffa.damagealert;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;   // Yarn-Mappings
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Liest jeden Tick den Spielzustand und stellt ihn als JSON bereit:
 *   GET http://127.0.0.1:7475/state
 * Das OBS-Overlay (cam-themes.html) fragt diese Adresse regelmäßig ab.
 * Der Mod liest nur, er sendet nichts an den Minecraft-Server.
 */
public class DamageAlertClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("damagealert");

    private static final int GRACE_TICKS = 40; // 2 s Ruhe nach Join/Respawn/Dimensionswechsel

    // Zuletzt erzeugter Zustand als fertiger JSON-Text.
    // Wird nur im Spiel-Thread geschrieben, der Server liest ihn nur.
    private static volatile String stateJson = "{\"inWorld\":false}";

    private Object lastPlayer = null;
    private float lastHealth = -1;
    private int graceTicks = 0;
    private long hits = 0;              // Trefferzähler, steigt nur
    private boolean portalArmed = false; // verhindert Portal-Effekt direkt nach der Ankunft

    @Override
    public void onInitializeClient() {
        int port = loadPort();
        StateServer.start(port, () -> stateJson);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> StateServer.stop());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var player = client.player;
            var world = client.world;

            if (player == null || world == null) {
                lastPlayer = null;
                lastHealth = -1;
                stateJson = String.format(Locale.ROOT,
                        "{\"inWorld\":false,\"inPortal\":false,\"underwater\":false,\"freeze\":0,\"biome\":null,\"hits\":%d}",
                        hits);
                return;
            }

            // Neue Welt, Serverwechsel, Respawn oder Dimensionswechsel (neues Spieler-Objekt)
            if (player != lastPlayer) {
                lastPlayer = player;
                lastHealth = -1;
                graceTicks = GRACE_TICKS;
                portalArmed = false;
            }

            BlockPos pos = player.getBlockPos();

            // ---- Portal ----
            // Erst "scharf", nachdem man einmal außerhalb eines Portals stand.
            // So startet der Effekt nicht sofort wieder im Ziel-Portal.
            boolean touchingPortal = world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL);
            if (!touchingPortal) portalArmed = true;
            boolean inPortal = touchingPortal && portalArmed;

            // ---- Schaden -> Trefferzähler ----
            float health = player.getHealth();
            if (graceTicks > 0) {
                graceTicks--;
            } else if (lastHealth >= 0 && health < lastHealth) {
                hits++;
            }
            lastHealth = health;

            // ---- Weitere Zustände ----
            String biome = world.getBiome(pos).getKey()
                    .map(key -> key.getValue().toString())
                    .orElse("unknown");
            String dimension = world.getRegistryKey().getValue().toString();

            stateJson = String.format(Locale.ROOT,
                    "{\"inWorld\":true,\"inPortal\":%b,\"underwater\":%b,\"inLava\":%b,\"onFire\":%b,"
                            + "\"freeze\":%.3f,\"health\":%.1f,\"maxHealth\":%.1f,\"hits\":%d,"
                            + "\"biome\":\"%s\",\"dimension\":\"%s\",\"y\":%.1f,"
                            + "\"raining\":%b,\"thundering\":%b}",
                    inPortal,
                    player.isSubmergedInWater(),
                    player.isInLava(),
                    player.isOnFire(),
                    player.getFreezingScale(),
                    health,
                    player.getMaxHealth(),
                    hits,
                    biome,
                    dimension,
                    player.getY(),
                    world.isRaining(),
                    world.isThundering());
        });
    }

    /** Liest den Port aus config/damagealert.properties, legt die Datei bei Bedarf an. */
    private static int loadPort() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("damagealert.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) { props.load(r); }
            } else {
                props.setProperty("port", "7475");
                try (Writer w = Files.newBufferedWriter(file)) {
                    props.store(w, "Port des Zustands-Servers (muss zu STATE_URL im Overlay passen)");
                }
            }
            return Integer.parseInt(props.getProperty("port", "7475").trim());
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("Config konnte nicht gelesen werden, nutze Port 7475", e);
            return 7475;
        }
    }
}
