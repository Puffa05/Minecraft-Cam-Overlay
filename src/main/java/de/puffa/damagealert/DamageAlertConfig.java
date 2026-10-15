package de.puffa.damagealert;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Einfache Config in {@code config/damagealert.properties}.
 * Fehlt die Datei, wird sie mit Standardwerten angelegt. Port und Cooldown gelten nach einem
 * Neustart; die An/Aus-Schalter aendern Menue und Weboberflaeche live.
 */
public record DamageAlertConfig(
		int statePort,
		long damageCooldownMs,
		Set<Effect> enabledEffects) {

	public static final String FILE_NAME = "damagealert.properties";

	public DamageAlertConfig {
		// Unveraenderliche Kopie: der Record wird zwischen Threads geteilt
		enabledEffects = enabledEffects.isEmpty()
				? Collections.emptySet()
				: Collections.unmodifiableSet(EnumSet.copyOf(enabledEffects));
	}

	public static DamageAlertConfig defaults() {
		return new DamageAlertConfig(7475, 1000, EnumSet.allOf(Effect.class));
	}

	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public boolean isEnabled(Effect effect) {
		return enabledEffects.contains(effect);
	}

	public DamageAlertConfig withEnabled(Effect effect, boolean enabled) {
		EnumSet<Effect> set = EnumSet.noneOf(Effect.class);
		set.addAll(enabledEffects);
		if (enabled) {
			set.add(effect);
		} else {
			set.remove(effect);
		}
		return new DamageAlertConfig(statePort, damageCooldownMs, set);
	}

	/** Liest die Config. Fehlt die Datei, wird sie mit Standardwerten angelegt. */
	public static DamageAlertConfig load(Path file) {
		DamageAlertConfig d = defaults();

		if (Files.notExists(file)) {
			try {
				d.save(file);
				DamageAlert.LOGGER.info("[DamageAlert] Config angelegt: {}", file);
			} catch (IOException e) {
				DamageAlert.LOGGER.warn("[DamageAlert] Config konnte nicht angelegt werden: {}", e.toString());
			}
			return d;
		}

		Properties p = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			p.load(reader);
		} catch (IOException | RuntimeException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] Config {} nicht lesbar, nutze Standardwerte: {}", file, e.toString());
			return d;
		}

		EnumSet<Effect> enabled = EnumSet.noneOf(Effect.class);
		for (Effect effect : Effect.values()) {
			if (bool(p, effect.configKey(), true)) {
				enabled.add(effect);
			}
		}

		return new DamageAlertConfig(
				(int) number(p, "state.port", d.statePort(), 1, 65535),
				number(p, "damage.cooldownMs", d.damageCooldownMs(), 0, 600_000),
				enabled);
	}

	/** Schreibt die komplette Datei neu. Alte Eintraege (z. B. von Streamer.bot) fallen dabei weg. */
	public void save(Path file) throws IOException {
		StringBuilder toggles = new StringBuilder();
		for (Effect effect : Effect.values()) {
			toggles.append(effect.configKey()).append('=').append(isEnabled(effect)).append('\n');
		}
		String content = """
				# DamageAlert - Einstellungen.
				# Port und Cooldown gelten nach einem Neustart von Minecraft.
				# Die enabled.*-Schalter lassen sich live im Spiel (Taste J) oder unter http://127.0.0.1:%1$d/ aendern.

				# Port fuer Overlays (/state) und Weboberflaeche (/)
				state.port=%1$d

				# Mindestabstand zwischen zwei Schadens-Effekten in Millisekunden
				damage.cooldownMs=%2$d

				# Effekte an (true) / aus (false)
				%3$s""".formatted(statePort, damageCooldownMs, toggles);
		Files2.writeAtomically(file, content);
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String value = p.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String v = value.trim();
		if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
			return Boolean.parseBoolean(v);
		}
		DamageAlert.LOGGER.warn("[DamageAlert] Ungueltiger Wert {}={} (erlaubt true/false), nutze {}", key, value, fallback);
		return fallback;
	}

	private static long number(Properties p, String key, long fallback, long min, long max) {
		String value = p.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			long n = Long.parseLong(value.trim());
			if (n >= min && n <= max) {
				return n;
			}
		} catch (NumberFormatException ignored) {
			// unten Warnung
		}
		DamageAlert.LOGGER.warn("[DamageAlert] Ungueltiger Wert {}={} (erlaubt {}..{}), nutze {}", key, value, min, max, fallback);
		return fallback;
	}
}
