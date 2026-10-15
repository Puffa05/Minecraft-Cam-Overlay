package de.puffa.damagealert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Map;
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

	@Test
	void streamerBotBody() {
		assertEquals("{\"action\":{\"name\":\"Portal \\\"X\\\"\"},\"args\":{}}",
				StreamerBotClient.buildBody("Portal \"X\"", Map.of()));
		String body = StreamerBotClient.buildBody("Minecraft Schaden", Map.of("damage", 2.5f));
		assertEquals("{\"action\":{\"name\":\"Minecraft Schaden\"},\"args\":{\"damage\":2.5}}", body);
	}

	private static final StateSnapshot FULL = new StateSnapshot(true, true, true, 0.5f,
			"minecraft:cherry_grove", "minecraft:overworld", 64, true, true);

	@Test
	void allEnabledChangesNothing() {
		assertEquals(FULL, FULL.effective(e -> true, Set.of()));
		assertEquals("{\"inWorld\":true,\"underwater\":true,\"inLava\":true,\"freeze\":0.5,\"biome\":\"minecraft:cherry_grove\","
				+ "\"dimension\":\"minecraft:overworld\",\"y\":64,\"raining\":true,\"thundering\":true}", FULL.toJson());
	}

	@Test
	void disabledStatesReportOff() {
		StateSnapshot s = FULL.effective(e -> false, Set.of());
		assertEquals(new StateSnapshot(true, false, false, 0f, null, null, null, false, false), s);
	}

	@Test
	void testsWinEvenWhenDisabledAndWithoutWorld() {
		StateSnapshot s = StateSnapshot.NOT_IN_WORLD.effective(e -> false, EnumSet.of(Effect.UNDERWATER, Effect.FREEZE));
		assertEquals(new StateSnapshot(false, true, false, 1f, null, null, null, false, false), s);
	}
}
