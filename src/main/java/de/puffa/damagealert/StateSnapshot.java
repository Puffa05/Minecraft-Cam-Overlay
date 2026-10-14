package de.puffa.damagealert;

/**
 * Unveraenderlicher Schnappschuss des Spielzustands. Wird im Client-Tick erzeugt
 * und vom HTTP-Server (anderer Thread) nur gelesen. Weil sich ein Record nach dem
 * Erzeugen nicht mehr aendern kann, ist das ohne Sperren thread-sicher.
 */
public record StateSnapshot(
		boolean inWorld,
		boolean underwater,
		boolean inLava,
		float freeze,
		String biome,
		String dimension,
		Integer y,
		boolean raining,
		boolean thundering) {

	/** Zustand, wenn man in keiner Welt ist (Hauptmenue, Ladebildschirm). */
	public static final StateSnapshot NOT_IN_WORLD =
			new StateSnapshot(false, false, false, 0f, null, null, null, false, false);

	public String toJson() {
		return "{\"inWorld\":" + inWorld
				+ ",\"underwater\":" + underwater
				+ ",\"inLava\":" + inLava
				+ ",\"freeze\":" + Json.number(freeze)
				+ ",\"biome\":" + Json.string(biome)
				+ ",\"dimension\":" + Json.string(dimension)
				+ ",\"y\":" + (y == null ? "null" : y.toString())
				+ ",\"raining\":" + raining
				+ ",\"thundering\":" + thundering
				+ "}";
	}
}
