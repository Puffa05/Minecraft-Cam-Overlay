package de.puffa.damagealert;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Todeszaehler, gespeichert in {@code config/damagealert-deaths.properties}, damit er Neustarts uebersteht.
 * "total" zaehlt immer weiter, "session" setzt man z. B. vor jedem Stream auf 0.
 * Thread-sicher (Client-Tick zaehlt, Menue und Weboberflaeche korrigieren).
 */
public final class DeathCounter {
	public static final String FILE_NAME = "damagealert-deaths.properties";

	public record Counts(int total, int session) {
		public String toJson() {
			return "{\"total\":" + total + ",\"session\":" + session + "}";
		}
	}

	private final Path file;
	private volatile Counts counts;

	private DeathCounter(Path file, Counts counts) {
		this.file = file;
		this.counts = counts;
	}

	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static DeathCounter load(Path file) {
		Counts c = new Counts(0, 0);
		if (Files.exists(file)) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
				c = new Counts(parse(p.getProperty("total")), parse(p.getProperty("session")));
			} catch (IOException | RuntimeException e) {
				DamageAlert.LOGGER.warn("[DamageAlert] Todeszaehler {} nicht lesbar, starte bei 0: {}", file, e.toString());
			}
		}
		return new DeathCounter(file, c);
	}

	private static int parse(String s) {
		try {
			return s == null ? 0 : Math.max(0, Integer.parseInt(s.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public Counts get() {
		return counts;
	}

	/** Ein Tod: beide Zaehler +1. */
	public synchronized Counts increment() {
		return set(new Counts(counts.total() + 1, counts.session() + 1));
	}

	/** Korrektur von Hand (+1 / -1), auf beide Zaehler, nie unter 0. */
	public synchronized Counts adjust(int delta) {
		return set(new Counts(Math.max(0, counts.total() + delta), Math.max(0, counts.session() + delta)));
	}

	public synchronized Counts resetSession() {
		return set(new Counts(counts.total(), 0));
	}

	public synchronized Counts resetTotal() {
		return set(new Counts(0, 0));
	}

	private Counts set(Counts c) {
		counts = c;
		try {
			Files2.writeAtomically(file, "# DamageAlert Todeszaehler (wird vom Mod verwaltet)\ntotal=" + c.total()
					+ "\nsession=" + c.session() + "\n");
		} catch (IOException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] Todeszaehler konnte nicht gespeichert werden: {}", e.toString());
		}
		return c;
	}
}
