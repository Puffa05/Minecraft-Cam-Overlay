package de.puffa.damagealert;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;   // Yarn-Mappings
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LightningEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private boolean tickErrorLogged = false;
    private long lightning = 0;          // Blitz-Zaehler, steigt bei jedem Blitz, den das Spiel anzeigt
    private float lightningDist = -1;    // Entfernung des letzten Blitzes in Bloecken

    @Override
    public void onInitializeClient() {
        int port = loadPort();
        try {
            VideoConfig.load(FabricLoader.getInstance().getConfigDir().resolve("damagealert.properties"));
        } catch (RuntimeException e) {
            LOGGER.warn("Video-Einstellungen konnten nicht geladen werden", e); // Overlay nutzt dann die eingebauten Themes
        }
        StateServer.start(port, () -> stateJson);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> StateServer.stop());

        // Blitze: Das Spiel legt fuer jeden sichtbaren Blitz ein Blitz-Objekt in der Welt an.
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            try {
                if (entity instanceof LightningEntity) {
                    lightning++;
                    var player = MinecraftClient.getInstance().player;
                    lightningDist = player != null ? entity.distanceTo(player) : -1;
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("Blitz konnte nicht ausgewertet werden", e);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Absturzschutz: Ein Fehler im Mod darf nie das Spiel beenden.
            try {
                onTick(client);
            } catch (RuntimeException | LinkageError e) {
                if (!tickErrorLogged) {
                    tickErrorLogged = true; // nur einmal loggen, sonst 20x pro Sekunde
                    LOGGER.error("Fehler beim Lesen des Spielzustands (weitere werden nicht geloggt)", e);
                }
                // Overlay sicher auf "aus" stellen, statt einen veralteten Zustand stehen zu lassen
                stateJson = String.format(Locale.ROOT,
                        "{\"inWorld\":false,\"inPortal\":false,\"underwater\":false,\"freeze\":0,\"biome\":null,\"hits\":%d,\"lightning\":%d}",
                        hits, lightning);
            }
        });
    }

    /** Liest den Spielzustand. Laeuft jeden Tick im Spiel-Thread. */
    private void onTick(MinecraftClient client) {
        var player = client.player;
        var world = client.world;

        if (player == null || world == null) {
            lastPlayer = null;
            lastHealth = -1;
            stateJson = String.format(Locale.ROOT,
                    "{\"inWorld\":false,\"inPortal\":false,\"underwater\":false,\"freeze\":0,\"biome\":null,\"hits\":%d,\"lightning\":%d}",
                    hits, lightning);
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
        boolean touchingPortal = hitboxTouchesPortal(player, world);
        // Erst entsperren, wenn die Welt fertig geladen ist: Waehrend des Ladens sind die
        // Chunks noch leer, das Portal wuerde als Luft gelesen und die Sperre zu frueh aufgehoben.
        boolean worldLoaded = client.getNetworkHandler() != null && client.getNetworkHandler().isLoaded();
        if (!touchingPortal && worldLoaded) portalArmed = true;
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
                        + "\"raining\":%b,\"thundering\":%b,\"lightning\":%d,\"lightningDist\":%.1f}",
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
                world.isThundering(),
                lightning,
                lightningDist);
    }

    /**
     * Berührt die Hitbox des Spielers irgendwo einen Portalblock? Prüft alle Blöcke, die die
     * Hitbox schneidet (wie Minecraft selbst), nicht nur den Block unter den Füßen.
     */
    private static boolean hitboxTouchesPortal(ClientPlayerEntity player, ClientWorld world) {
        Box box = player.getBoundingBox().contract(1.0E-5); // Kanten, die einen Block nur berühren, zählen nicht
        BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ);
        for (BlockPos p : BlockPos.iterate(min, max)) {
            if (world.getBlockState(p).isOf(Blocks.NETHER_PORTAL)) return true;
        }
        return false;
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
