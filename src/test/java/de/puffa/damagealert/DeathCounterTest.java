package de.puffa.damagealert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeathCounterTest {
	@TempDir
	Path dir;

	@Test
	void countsPersistsAndResets() throws Exception {
		Path file = dir.resolve("damagealert-deaths.properties");
		DeathCounter c = DeathCounter.load(file);
		assertEquals(new DeathCounter.Counts(0, 0), c.get());
		c.increment();
		c.increment();
		c.resetSession();
		c.increment();
		assertEquals(new DeathCounter.Counts(3, 1), c.get());

		// Neustart: Werte kommen aus der Datei
		DeathCounter reloaded = DeathCounter.load(file);
		assertEquals(new DeathCounter.Counts(3, 1), reloaded.get());

		reloaded.adjust(-1);
		reloaded.adjust(-1);
		assertEquals(new DeathCounter.Counts(1, 0), reloaded.get()); // nie unter 0
		reloaded.adjust(1);
		assertEquals(new DeathCounter.Counts(2, 1), reloaded.get());
		reloaded.resetTotal();
		assertEquals(new DeathCounter.Counts(0, 0), DeathCounter.load(file).get());
		assertEquals("{\"total\":0,\"session\":0}", reloaded.get().toJson());
	}

	@Test
	void brokenFileStartsAtZero() throws Exception {
		Path file = dir.resolve("damagealert-deaths.properties");
		Files.writeString(file, "total=abc\nsession=-4\n");
		assertEquals(new DeathCounter.Counts(0, 0), DeathCounter.load(file).get());
	}
}
