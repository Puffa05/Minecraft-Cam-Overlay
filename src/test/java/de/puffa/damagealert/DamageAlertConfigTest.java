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
		String text = Files.readString(file);
		assertTrue(text.contains("state.port=7475"), text);
		assertTrue(text.contains("enabled.death=true"), text);
		assertFalse(text.toLowerCase().contains("streamer"), text);
		assertEquals(c, DamageAlertConfig.load(file));
	}

	@Test
	void oldStreamerBotConfigStillLoadsAndIsCleanedOnSave() throws Exception {
		Path file = dir.resolve("damagealert.properties");
		Files.writeString(file, """
				streamerbot.port=7474
				state.port=8001
				action.damage=Spieler verletzt äöü
				damage.cooldownMs=250
				enabled.damage=false
				enabled.biome=FALSE
				""", StandardCharsets.UTF_8);
		ConfigStore store = ConfigStore.load(file);
		DamageAlertConfig c = store.get();
		assertEquals(8001, c.statePort());
		assertEquals(250, c.damageCooldownMs());
		assertFalse(c.isEnabled(Effect.DAMAGE));
		assertFalse(c.isEnabled(Effect.BIOME));
		assertTrue(c.isEnabled(Effect.DEATH)); // fehlt -> an

		store.setEnabled(Effect.PORTAL, false);
		String text = Files.readString(file);
		assertFalse(text.contains("streamerbot") || text.contains("action."), text);
		DamageAlertConfig reloaded = DamageAlertConfig.load(file);
		assertEquals(store.get(), reloaded);
		assertFalse(reloaded.isEnabled(Effect.PORTAL));
		try (var files = Files.list(dir)) {
			assertEquals(1, files.count()); // keine Temp-Dateien
		}
	}

	@Test
	void invalidValuesFallBackToDefaults() throws Exception {
		Path file = dir.resolve("damagealert.properties");
		Files.writeString(file, """
				state.port=70000
				damage.cooldownMs=-5
				enabled.freeze=vielleicht
				""", StandardCharsets.UTF_8);
		DamageAlertConfig c = DamageAlertConfig.load(file);
		assertEquals(7475, c.statePort());
		assertEquals(1000, c.damageCooldownMs());
		assertTrue(c.isEnabled(Effect.FREEZE));
	}
}
