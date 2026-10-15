package de.puffa.damagealert.client;

import de.puffa.damagealert.ConfigStore;
import de.puffa.damagealert.DamageAlert;
import de.puffa.damagealert.DamageAlertConfig;
import de.puffa.damagealert.DeathCounter;
import de.puffa.damagealert.EffectController;
import de.puffa.damagealert.OverlayEvents;
import de.puffa.damagealert.StateServer;
import de.puffa.damagealert.StateSnapshot;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/**
 * Einstiegspunkt des Mods. Liest einmal pro Tick (20x pro Sekunde) den Spielzustand:
 * Zustaende, Ereignisse (Schaden, Tod) und der Todeszaehler landen im lokalen Server,
 * von dem sich die OBS-Overlays alles abholen.
 * Der Mod liest nur. Er sendet keine Pakete an den Server und aendert nichts am Gameplay.
 */
public class DamageAlertClient implements ClientModInitializer {
	private DamageTracker damageTracker;
	private DeathTracker deathTracker;
	private PortalTracker portalTracker;
	private StateServer stateServer;
	private EffectController controller;
	private KeyBinding openMenuKey;
	private String webUrl;

	/** Das zuletzt gesehene Spieler-Objekt. Minecraft erzeugt bei Join, Respawn und Dimensionswechsel ein neues. */
	private ClientPlayerEntity lastPlayer;
	private boolean tickErrorLogged;

	@Override
	public void onInitializeClient() {
		ConfigStore store = ConfigStore.load(DamageAlertConfig.defaultPath());
		DamageAlertConfig config = store.get();
		OverlayEvents events = new OverlayEvents();
		DeathCounter deaths = DeathCounter.load(DeathCounter.defaultPath());

		damageTracker = new DamageTracker(events, store);
		deathTracker = new DeathTracker(deaths, events, store);
		portalTracker = new PortalTracker();
		controller = new EffectController(store, events, deaths);

		stateServer = new StateServer(controller);
		if (stateServer.start(config.statePort())) { // bei belegtem Port nur Warnung
			webUrl = "http://127.0.0.1:" + config.statePort() + "/";
		}

		// Taste fuer das Ingame-Menue (Standard J, aenderbar unter Optionen > Steuerung)
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(DamageAlert.MOD_ID, "main"));
		openMenuKey = KeyBindingHelper.registerKeyBinding(
				new KeyBinding("key.damagealert.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stateServer.stop());

		DamageAlert.LOGGER.info("[DamageAlert] geladen. Overlay-Port {}, Tode bisher: {} (Stream: {})",
				config.statePort(), deaths.get().total(), deaths.get().session());
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
		while (openMenuKey.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new DamageAlertScreen(null, controller, webUrl));
			}
		}

		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;

		if (player == null || world == null) {
			if (lastPlayer != null) {
				// Welt/Server verlassen
				deathTracker.onPlayerReplaced(lastPlayer);
				portalTracker.onLeftWorld();
				lastPlayer = null;
			}
			stateServer.publish(StateSnapshot.NOT_IN_WORLD);
			return;
		}

		if (player != lastPlayer) {
			// Join, Respawn oder Dimensionswechsel
			deathTracker.onPlayerReplaced(lastPlayer);
			deathTracker.onNewPlayer(player);
			damageTracker.onNewPlayer(player);
			portalTracker.onNewPlayer();
			lastPlayer = player;
		}

		// Erst true, wenn der Ladebildschirm fertig ist und die Chunks um den Spieler geladen sind
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		boolean loaded = handler != null && handler.isLoaded();

		damageTracker.tick(player, loaded);
		deathTracker.tick(player, loaded);
		portalTracker.tick(player, world, loaded);
		stateServer.publish(StateCollector.collect(player, world, portalTracker.isActive()));
	}
}
