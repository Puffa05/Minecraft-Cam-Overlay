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
		String biomeName,
		String dimension,
		Integer y,
		boolean raining,
		boolean thundering,
		boolean inPortal,
		Float health,
		Float maxHealth) {

	/** Zustand, wenn man in keiner Welt ist (Hauptmenue, Ladebildschirm). */
	public static final StateSnapshot NOT_IN_WORLD =
			new StateSnapshot(false, false, false, 0f, null, null, null, null, false, false, false, null, null);

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
				enabled.test(Effect.BIOME) ? biomeName : null,
				enabled.test(Effect.DIMENSION) ? dimension : null,
				enabled.test(Effect.Y) ? y : null,
				testing.contains(Effect.RAINING) || (enabled.test(Effect.RAINING) && raining),
				testing.contains(Effect.THUNDERING) || (enabled.test(Effect.THUNDERING) && thundering),
				testing.contains(Effect.PORTAL) || (enabled.test(Effect.PORTAL) && inPortal),
				health,
				maxHealth);
	}

	/** Schreibt die Felder (ohne geschweifte Klammern) in den StringBuilder. */
	public void appendJsonFields(StringBuilder sb) {
		sb.append("\"inWorld\":").append(inWorld)
				.append(",\"underwater\":").append(underwater)
				.append(",\"inLava\":").append(inLava)
				.append(",\"freeze\":").append(Json.number(freeze))
				.append(",\"biome\":").append(Json.string(biome))
				.append(",\"biomeName\":").append(Json.string(biomeName))
				.append(",\"dimension\":").append(Json.string(dimension))
				.append(",\"y\":").append(y == null ? "null" : y.toString())
				.append(",\"raining\":").append(raining)
				.append(",\"thundering\":").append(thundering)
				.append(",\"inPortal\":").append(inPortal)
				.append(",\"health\":").append(health == null ? "null" : Json.number(health))
				.append(",\"maxHealth\":").append(maxHealth == null ? "null" : Json.number(maxHealth));
	}

	public String toJson() {
		StringBuilder sb = new StringBuilder("{");
		appendJsonFields(sb);
		return sb.append('}').toString();
	}
}
