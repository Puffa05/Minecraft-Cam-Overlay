package de.puffa.damagealert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DamageAlertConfigTest {
	@TempDir
	Path dir;

	@Test
	void createsDefaultFileWhenMissing() throws Exception {
		Path file = dir.resolve("sub/damagealert.properties");
		DamageAlertConfig c = DamageAlertConfig.load(file);
		assertEquals(DamageAlertConfig.defaults(), c);
		assertTrue(Files.exists(file));
		String text = Files.readString(file);
		assertTrue(text.contains("action.damage=Minecraft Schaden"), text);
		assertTrue(text.contains("enabled.underwater=true"), text);
		// Wieder einlesen ergibt dasselbe
		assertEquals(c, DamageAlertConfig.load(file));
	}

	@Test
	void readsUmlautsAndCustomValues() throws Exception {
		Path file = dir.resolve("damagealert.properties");
		Files.writeString(file, """
				streamerbot.port=8000
				state.port=8001
				action.damage=Spieler verletzt äöü
				damage.cooldownMs=250
				enabled.damage=false
				enabled.biome=FALSE
				""", StandardCharsets.UTF_8);
		DamageAlertConfig c = DamageAlertConfig.load(file);
		assertEquals(8000, c.streamerBotPort());
		assertEquals(8001, c.statePort());
		assertEquals("Spieler verletzt äöü", c.actionDamage());
		assertEquals("Portal Start", c.actionPortalStart()); // fehlt -> Standard
		assertEquals(250, c.damageCooldownMs());
		assertFalse(c.isEnabled(Effect.DAMAGE));
		assertFalse(c.isEnabled(Effect.BIOME));
		assertTrue(c.isEnabled(Effect.PORTAL)); // fehlt -> an
	}

	@Test
	void invalidValuesFallBackToDefaults() throws Exception {
		Path file = dir.resolve("damagealert.properties");
		Files.writeString(file, """
				streamerbot.port=abc
				state.port=70000
				damage.cooldownMs=-5
				action.portalEnd=
				enabled.freeze=vielleicht
				""", StandardCharsets.UTF_8);
		DamageAlertConfig c = DamageAlertConfig.load(file);
		assertEquals(7474, c.streamerBotPort());
		assertEquals(7475, c.statePort());
		assertEquals(1000, c.damageCooldownMs());
		assertEquals("Portal Ende", c.actionPortalEnd());
		assertTrue(c.isEnabled(Effect.FREEZE));
	}

	@Test
	void toggleIsSavedAndKeepsOtherValues() throws Exception {
		Path file = dir.resolve("damagealert.properties");
		Files.writeString(file, "action.damage=  Aua \\\\ Test\nstreamerbot.port=9000\n", StandardCharsets.UTF_8);
		ConfigStore store = ConfigStore.load(file);
		assertEquals("Aua \\ Test", store.get().actionDamage());

		store.setEnabled(Effect.UNDERWATER, false);
		store.setEnabled(Effect.DAMAGE, false);
		store.setEnabled(Effect.DAMAGE, true);

		DamageAlertConfig reloaded = DamageAlertConfig.load(file);
		assertFalse(reloaded.isEnabled(Effect.UNDERWATER));
		assertTrue(reloaded.isEnabled(Effect.DAMAGE));
		assertEquals(9000, reloaded.streamerBotPort());
		assertEquals("Aua \\ Test", reloaded.actionDamage());
		assertEquals(store.get(), reloaded);
		// Keine Temp-Dateien liegen lassen
		try (var files = Files.list(dir)) {
			assertEquals(1, files.count());
		}
	}
}
