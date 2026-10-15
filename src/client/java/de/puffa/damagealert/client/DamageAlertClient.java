package de.puffa.damagealert.client;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.DamageAlert;
import de.puffa.damagealert.DamageAlertConfig;
import de.puffa.damagealert.StateServer;
import de.puffa.damagealert.StateSnapshot;
import de.puffa.damagealert.StreamerBotClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/**
 * Einstiegspunkt des Mods. Liest einmal pro Tick (20x pro Sekunde) den Spielzustand:
 * <ul>
 *   <li>einmalige Events (Schaden, Portal) gehen an Streamer.bot</li>
 *   <li>dauerhafte Zustaende landen als Schnappschuss im lokalen Zustands-Server</li>
 * </ul>
 * Der Mod liest nur. Er sendet keine Pakete an den Server und aendert nichts am Gameplay.
 */
public class DamageAlertClient implements ClientModInitializer {
	private DamageTracker damageTracker;
	private PortalTracker portalTracker;
	private StateServer stateServer;

	/** Das zuletzt gesehene Spieler-Objekt. Minecraft erzeugt bei Join, Respawn und Dimensionswechsel ein neues. */
	private ClientPlayerEntity lastPlayer;
	private boolean tickErrorLogged;

	@Override
	public void onInitializeClient() {
		ConfigStore store = ConfigStore.load(DamageAlertConfig.defaultPath());
		DamageAlertConfig config = store.get();
		StreamerBotClient streamerBot = new StreamerBotClient(config.streamerBotPort());

		damageTracker = new DamageTracker(streamerBot, store);
		portalTracker = new PortalTracker(streamerBot, store);

		stateServer = new StateServer(store, streamerBot);
		stateServer.start(config.statePort()); // bei belegtem Port nur Warnung

		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stateServer.stop());

		DamageAlert.LOGGER.info("[DamageAlert] geladen. Streamer.bot-Port {}, Zustands-Port {}",
				config.streamerBotPort(), config.statePort());
	}

	private void onEndTick(MinecraftClient client) {
		try {
			tick(client);
		} catch (RuntimeException e) {
			// Ein Fehler im Mod darf nie das Spiel abstuerzen lassen
			if (!tickErrorLogged) {
				tickErrorLogged = true;
				DamageAlert.LOGGER.error("[DamageAlert] Fehler im Client-Tick (weitere werden nicht geloggt)", e);
			}
		}
	}

	private void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;

		if (player == null || world == null) {
			if (lastPlayer != null) {
				// Welt/Server verlassen: offenes Portal-Event sauber beenden
				portalTracker.onLeftWorld();
				lastPlayer = null;
			}
			stateServer.publish(StateSnapshot.NOT_IN_WORLD);
			return;
		}

		if (player != lastPlayer) {
			// Join, Respawn oder Dimensionswechsel
			damageTracker.onNewPlayer(player);
			portalTracker.onNewPlayer();
			lastPlayer = player;
		}

		// Erst true, wenn der Ladebildschirm fertig ist und die Chunks um den Spieler geladen sind
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		boolean loaded = handler != null && handler.isLoaded();

		damageTracker.tick(player, loaded);
		portalTracker.tick(player, world, loaded);
		stateServer.publish(StateCollector.collect(player, world));
	}
}
