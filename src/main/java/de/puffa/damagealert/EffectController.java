package de.puffa.damagealert;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemeinsame Logik fuer Weboberflaeche und Ingame-Menue: Effekte an/aus schalten und testen.
 * Beide greifen auf dieselbe Instanz zu, dadurch sind sie immer auf demselben Stand.
 * Thread-sicher: wird vom Render-Thread (Menue) und von HTTP-Threads (Web) benutzt.
 */
public final class EffectController {
	/** So lange meldet ein Zustands-Test den Zustand als "an". */
	public static final long STATE_TEST_MILLIS = 5000;

	/** Events, die sich per Test-Button einmal an Streamer.bot schicken lassen. */
	public enum TestEvent {
		DAMAGE("damage"), PORTAL_START("portalStart"), PORTAL_END("portalEnd");

		private final String id;

		TestEvent(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public static TestEvent byId(String id) {
			for (TestEvent e : values()) {
				if (e.id.equals(id)) {
					return e;
				}
			}
			return null;
		}
	}

	private final ConfigStore config;
	private final StreamerBotClient streamerBot;
	/** Laufende Zustands-Tests: Effekt -> Ende (System.nanoTime). */
	private final Map<Effect, Long> stateTests = new ConcurrentHashMap<>();

	public EffectController(ConfigStore config, StreamerBotClient streamerBot) {
		this.config = config;
		this.streamerBot = streamerBot;
	}

	public ConfigStore config() {
		return config;
	}

	public StreamerBotClient streamerBot() {
		return streamerBot;
	}

	public boolean isEnabled(Effect effect) {
		return config.isEnabled(effect);
	}

	public void setEnabled(Effect effect, boolean enabled) {
		config.setEnabled(effect, enabled);
		DamageAlert.LOGGER.info("[DamageAlert] Effekt {} {}", effect.id(), enabled ? "an" : "aus");
	}

	/** Schickt ein Event einmal an Streamer.bot, unabhaengig vom Schalter. */
	public CompletableFuture<StreamerBotClient.SendResult> testEvent(TestEvent event) {
		DamageAlertConfig c = config.get();
		return switch (event) {
			case DAMAGE -> {
				DamageAlert.LOGGER.info("[DamageAlert] Test-Event {}", c.actionDamage());
				yield streamerBot.doAction(c.actionDamage(), Map.of("damage", 2, "health", 18));
			}
			case PORTAL_START -> {
				DamageAlert.LOGGER.info("[DamageAlert] Test-Event {}", c.actionPortalStart());
				yield streamerBot.doAction(c.actionPortalStart());
			}
			case PORTAL_END -> {
				DamageAlert.LOGGER.info("[DamageAlert] Test-Event {}", c.actionPortalEnd());
				yield streamerBot.doAction(c.actionPortalEnd());
			}
		};
	}

	/** Meldet einen Zustand {@link #STATE_TEST_MILLIS} ms lang als "an". */
	public boolean testState(Effect state) {
		if (state == null || !state.isTestableState()) {
			return false;
		}
		stateTests.put(state, System.nanoTime() + STATE_TEST_MILLIS * 1_000_000L);
		DamageAlert.LOGGER.info("[DamageAlert] Test-Zustand {} fuer {} ms", state.id(), STATE_TEST_MILLIS);
		return true;
	}

	public Set<Effect> activeStateTests() {
		if (stateTests.isEmpty()) {
			return Set.of();
		}
		long now = System.nanoTime();
		EnumSet<Effect> active = EnumSet.noneOf(Effect.class);
		stateTests.forEach((effect, end) -> {
			if (now - end < 0) {
				active.add(effect);
			}
		});
		stateTests.entrySet().removeIf(e -> now - e.getValue() >= 0);
		return active;
	}

	/** Snapshot mit Schaltern und laufenden Tests, so wie /state ihn ausliefert. */
	public StateSnapshot effective(StateSnapshot raw) {
		return raw.effective(config::isEnabled, activeStateTests());
	}
}
