package de.puffa.damagealert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JsonAndSnapshotTest {
	@Test
	void numbersUseDotAndRound() {
		assertEquals("4", Json.number(4.0));
		assertEquals("1.6", Json.number(1.6000004f));
		assertEquals("0.33", Json.number(1.0 / 3));
		assertEquals("0", Json.number(Double.NaN));
	}

	@Test
	void stringsAreEscaped() {
		assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.string("a\"b\\c\n\u0001"));
		assertEquals("null", Json.string(null));
	}

	private static final StateSnapshot FULL = new StateSnapshot(true, true, true, 0.5f,
			"minecraft:cherry_grove", "Kirschberghain", "minecraft:overworld", 64, true, true, true, 16f, 20f);

	@Test
	void allEnabledChangesNothing() {
		assertEquals(FULL, FULL.effective(e -> true, Set.of()));
		assertEquals("{\"inWorld\":true,\"underwater\":true,\"inLava\":true,\"freeze\":0.5,\"biome\":\"minecraft:cherry_grove\","
				+ "\"biomeName\":\"Kirschberghain\",\"dimension\":\"minecraft:overworld\",\"y\":64,\"raining\":true,\"thundering\":true,\"inPortal\":true,"
				+ "\"health\":16,\"maxHealth\":20}", FULL.toJson());
	}

	@Test
	void disabledStatesReportOff() {
		StateSnapshot s = FULL.effective(e -> false, Set.of());
		assertEquals(new StateSnapshot(true, false, false, 0f, null, null, null, null, false, false, false, 16f, 20f), s);
	}

	@Test
	void testsWinEvenWhenDisabledAndWithoutWorld() {
		StateSnapshot s = StateSnapshot.NOT_IN_WORLD.effective(e -> false, EnumSet.of(Effect.UNDERWATER, Effect.FREEZE, Effect.PORTAL));
		assertEquals(new StateSnapshot(false, true, false, 1f, null, null, null, null, false, false, true, null, null), s);
		assertTrue(s.toJson().contains("\"health\":null"));
	}

	@Test
	void eventsCountUp() {
		OverlayEvents e = new OverlayEvents();
		assertTrue(e.toJson().contains("\"damage\":{\"seq\":0"), e.toJson());
		e.damage(4, 16);
		e.damage(1.5f, 14.5f);
		e.death();
		String json = e.toJson();
		assertTrue(json.contains("\"damage\":{\"seq\":2,\"amount\":1.5,\"health\":14.5}"), json);
		assertTrue(json.contains("\"death\":{\"seq\":1}"), json);
		assertTrue(json.startsWith("{\"epoch\":"), json);
	}
}
