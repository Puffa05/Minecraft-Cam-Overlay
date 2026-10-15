package de.puffa.damagealert;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Einfache Config in {@code config/damagealert.properties}.
 * Fehlt die Datei, wird sie mit Standardwerten angelegt. Ports, Action-Namen und Cooldown
 * gelten nach einem Neustart; die An/Aus-Schalter aendert die Oberflaeche live.
 */
public record DamageAlertConfig(
		int streamerBotPort,
		int statePort,
		String actionDamage,
		String actionPortalStart,
		String actionPortalEnd,
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
		return new DamageAlertConfig(7474, 7475, "Minecraft Schaden", "Portal Start", "Portal Ende", 1000,
				EnumSet.allOf(Effect.class));
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
		return new DamageAlertConfig(streamerBotPort, statePort, actionDamage, actionPortalStart, actionPortalEnd,
				damageCooldownMs, set);
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
		// Als UTF-8 lesen, damit Umlaute in Action-Namen funktionieren
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
				port(p, "streamerbot.port", d.streamerBotPort()),
				port(p, "state.port", d.statePort()),
				text(p, "action.damage", d.actionDamage()),
				text(p, "action.portalStart", d.actionPortalStart()),
				text(p, "action.portalEnd", d.actionPortalEnd()),
				number(p, "damage.cooldownMs", d.damageCooldownMs(), 0, 600_000),
				enabled);
	}

	/** Schreibt die komplette Datei neu (erst in eine Temp-Datei, dann umbenennen). */
	public void save(Path file) throws IOException {
		StringBuilder toggles = new StringBuilder();
		for (Effect effect : Effect.values()) {
			toggles.append(effect.configKey()).append('=').append(isEnabled(effect)).append('\n');
		}
		String content = """
				# DamageAlert - Einstellungen.
				# Ports, Action-Namen und Cooldown gelten nach einem Neustart von Minecraft.
				# Die enabled.*-Schalter lassen sich live unter http://127.0.0.1:%2$d/ aendern.

				# Port des Streamer.bot HTTP-Servers (Streamer.bot: Servers/Clients > HTTP Server)
				streamerbot.port=%1$d

				# Port fuer Overlay-Zustand (/state) und Oberflaeche (/)
				state.port=%2$d

				# Namen der Streamer.bot-Actions (muessen exakt so in Streamer.bot heissen)
				action.damage=%3$s
				action.portalStart=%4$s
				action.portalEnd=%5$s

				# Mindestabstand zwischen zwei Schadens-Events in Millisekunden
				damage.cooldownMs=%6$d

				# Effekte an (true) / aus (false)
				%7$s""".formatted(streamerBotPort, statePort, escape(actionDamage), escape(actionPortalStart),
				escape(actionPortalEnd), damageCooldownMs, toggles);

		Path dir = file.toAbsolutePath().getParent();
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, FILE_NAME, ".tmp");
		try {
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** Properties-Format: Backslashes und fuehrende Leerzeichen maskieren, Umlaute bleiben lesbar. */
	private static String escape(String value) {
		String s = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
		return s.startsWith(" ") ? "\\" + s : s;
	}

	private static String text(Properties p, String key, String fallback) {
		String value = p.getProperty(key);
		return value == null || value.isBlank() ? fallback : value.trim();
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

	private static int port(Properties p, String key, int fallback) {
		return (int) number(p, key, fallback, 1, 65535);
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
