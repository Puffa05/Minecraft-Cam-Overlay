package de.puffa.damagealert;

import java.util.Set;
import java.util.function.Predicate;

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

	/**
	 * Wendet die An/Aus-Schalter und laufende Tests an.
	 * Abgeschaltete Zustaende werden als "aus" gemeldet (false / 0 / null),
	 * ein laufender Test meldet den Zustand als "an" (auch wenn er abgeschaltet ist).
	 */
	public StateSnapshot effective(Predicate<Effect> enabled, Set<Effect> testing) {
		return new StateSnapshot(
				inWorld,
				testing.contains(Effect.UNDERWATER) || (enabled.test(Effect.UNDERWATER) && underwater),
				testing.contains(Effect.IN_LAVA) || (enabled.test(Effect.IN_LAVA) && inLava),
				testing.contains(Effect.FREEZE) ? 1f : enabled.test(Effect.FREEZE) ? freeze : 0f,
				enabled.test(Effect.BIOME) ? biome : null,
				enabled.test(Effect.DIMENSION) ? dimension : null,
				enabled.test(Effect.Y) ? y : null,
				testing.contains(Effect.RAINING) || (enabled.test(Effect.RAINING) && raining),
				testing.contains(Effect.THUNDERING) || (enabled.test(Effect.THUNDERING) && thundering));
	}

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
