package de.puffa.damagealert;

/** Alles, was sich in Menue und Weboberflaeche einzeln an- und abschalten laesst. */
public enum Effect {
	// Einmalige Ereignisse (das Overlay spielt einen kurzen Effekt ab)
	DAMAGE("damage", true, false),
	DEATH("death", true, false),
	// Dauerzustaende
	PORTAL("portal", false, true),
	UNDERWATER("underwater", false, true),
	IN_LAVA("inLava", false, true),
	FREEZE("freeze", false, true),
	RAINING("raining", false, true),
	THUNDERING("thundering", false, true),
	BIOME("biome", false, false),
	DIMENSION("dimension", false, false),
	Y("y", false, false);

	private final String id;
	private final boolean event;
	private final boolean testableState;

	Effect(String id, boolean event, boolean testableState) {
		this.id = id;
		this.event = event;
		this.testableState = testableState;
	}

	/** Name in Config, API und JSON, z. B. "inLava". */
	public String id() {
		return id;
	}

	public boolean isEvent() {
		return event;
	}

	/** Zustaende, die sich per Test-Button kurz "einschalten" lassen. */
	public boolean isTestableState() {
		return testableState;
	}

	public String configKey() {
		return "enabled." + id;
	}

	public static Effect byId(String id) {
		if (id == null) {
			return null;
		}
		for (Effect e : values()) {
			if (e.id.equalsIgnoreCase(id.trim())) {
				return e;
			}
		}
		return null;
	}
}
