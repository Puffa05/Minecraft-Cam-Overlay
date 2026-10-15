package de.puffa.damagealert;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemeinsame Logik fuer Weboberflaeche und Ingame-Menue: Effekte an/aus schalten, testen
 * und den Todeszaehler korrigieren. Beide greifen auf dieselbe Instanz zu und sind dadurch
 * immer auf demselben Stand. Thread-sicher (Render-Thread und HTTP-Threads).
 */
public final class EffectController {
	/** So lange meldet ein Zustands-Test den Zustand als "an". */
	public static final long STATE_TEST_MILLIS = 5000;

	/** Ereignisse, die sich per Test-Button einmal im Overlay ausloesen lassen. */
	public enum TestEvent {
		DAMAGE("damage"), DEATH("death");

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
	private final OverlayEvents events;
	private final DeathCounter deaths;
	/** Laufende Zustands-Tests: Effekt -> Ende (System.nanoTime). */
	private final Map<Effect, Long> stateTests = new ConcurrentHashMap<>();

	public EffectController(ConfigStore config, OverlayEvents events, DeathCounter deaths) {
		this.config = config;
		this.events = events;
		this.deaths = deaths;
	}

	public ConfigStore config() {
		return config;
	}

	public OverlayEvents events() {
		return events;
	}

	public DeathCounter deaths() {
		return deaths;
	}

	public boolean isEnabled(Effect effect) {
		return config.isEnabled(effect);
	}

	public void setEnabled(Effect effect, boolean enabled) {
		config.setEnabled(effect, enabled);
		DamageAlert.LOGGER.info("[DamageAlert] Effekt {} {}", effect.id(), enabled ? "an" : "aus");
	}

	/** Loest ein Ereignis einmal im Overlay aus, unabhaengig vom Schalter. Zaehlt keinen Tod. */
	public void testEvent(TestEvent event) {
		DamageAlert.LOGGER.info("[DamageAlert] Test-Ereignis {}", event.id());
		switch (event) {
			case DAMAGE -> events.damage(2, 18);
			case DEATH -> events.death();
		}
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

	/** Kompletter /state-Inhalt: Zustand + Todeszaehler + Ereignisse. */
	public String stateJson(StateSnapshot raw) {
		StringBuilder sb = new StringBuilder("{");
		effective(raw).appendJsonFields(sb);
		sb.append(",\"deaths\":").append(deaths.get().toJson());
		sb.append(",\"events\":").append(events.toJson());
		return sb.append('}').toString();
	}
}
