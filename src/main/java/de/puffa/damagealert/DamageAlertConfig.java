package de.puffa.damagealert;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Einfache Config in {@code config/damagealert.properties}.
 * Fehlt die Datei, wird sie mit Standardwerten angelegt. Aenderungen gelten nach einem Neustart des Spiels.
 */
public record DamageAlertConfig(
		int streamerBotPort,
		int statePort,
		String actionDamage,
		String actionPortalStart,
		String actionPortalEnd,
		long damageCooldownMs) {

	public static final String FILE_NAME = "damagealert.properties";

	public static DamageAlertConfig defaults() {
		return new DamageAlertConfig(7474, 7475, "Minecraft Schaden", "Portal Start", "Portal Ende", 1000);
	}

	public static DamageAlertConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		DamageAlertConfig d = defaults();

		if (Files.notExists(file)) {
			writeDefaults(file, d);
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

		return new DamageAlertConfig(
				port(p, "streamerbot.port", d.streamerBotPort()),
				port(p, "state.port", d.statePort()),
				text(p, "action.damage", d.actionDamage()),
				text(p, "action.portalStart", d.actionPortalStart()),
				text(p, "action.portalEnd", d.actionPortalEnd()),
				number(p, "damage.cooldownMs", d.damageCooldownMs(), 0, 600_000));
	}

	private static String text(Properties p, String key, String fallback) {
		String value = p.getProperty(key);
		return value == null || value.isBlank() ? fallback : value.trim();
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

	private static void writeDefaults(Path file, DamageAlertConfig d) {
		String content = """
				# DamageAlert - Einstellungen. Nach Aenderungen Minecraft neu starten.

				# Port des Streamer.bot HTTP-Servers (Streamer.bot: Servers/Clients > HTTP Server)
				streamerbot.port=%d

				# Port, auf dem der Mod den Spielzustand fuer das OBS-Overlay bereitstellt
				# (abrufbar unter http://127.0.0.1:<port>/state)
				state.port=%d

				# Namen der Streamer.bot-Actions (muessen exakt so in Streamer.bot heissen)
				action.damage=%s
				action.portalStart=%s
				action.portalEnd=%s

				# Mindestabstand zwischen zwei Schadens-Events in Millisekunden
				damage.cooldownMs=%d
				""".formatted(d.streamerBotPort(), d.statePort(), d.actionDamage(), d.actionPortalStart(),
				d.actionPortalEnd(), d.damageCooldownMs());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
			DamageAlert.LOGGER.info("[DamageAlert] Config angelegt: {}", file);
		} catch (IOException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] Config konnte nicht angelegt werden: {}", e.toString());
		}
	}
}
