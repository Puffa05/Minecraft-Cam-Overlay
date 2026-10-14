package de.puffa.damagealert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schickt Actions per HTTP POST an Streamer.bot ({@code /DoAction}).
 * Alles laeuft asynchron im Hintergrund: Das Spiel wartet nie auf eine Antwort,
 * und Fehler (z. B. Streamer.bot laeuft nicht) werden ignoriert.
 */
public final class StreamerBotClient {
	private final URI endpoint;
	private final HttpClient http;
	/** Damit "nicht erreichbar" nur einmal im Log steht und nicht bei jedem Event. */
	private final AtomicBoolean reachable = new AtomicBoolean(true);

	public StreamerBotClient(int port) {
		this.endpoint = URI.create("http://127.0.0.1:" + port + "/DoAction");
		HttpClient client = null;
		try {
			client = HttpClient.newBuilder()
					// HTTP/1.1 erzwingen: sonst schickt Java einen h2c-Upgrade-Header mit
					.version(HttpClient.Version.HTTP_1_1)
					// Lokale Verbindung, nie ueber einen System-Proxy leiten
					.proxy(HttpClient.Builder.NO_PROXY)
					.connectTimeout(Duration.ofSeconds(2))
					.build();
		} catch (RuntimeException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] HTTP-Client konnte nicht erstellt werden: {}", e.toString());
		}
		this.http = client;
	}

	/** Baut den Body {"action":{"name":"..."},"args":{...}}. Werte: String, Number, Boolean. */
	public static String buildBody(String actionName, Map<String, ?> args) {
		StringBuilder sb = new StringBuilder("{\"action\":{\"name\":").append(Json.string(actionName)).append("},\"args\":{");
		boolean first = true;
		for (Map.Entry<String, ?> e : args.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(Json.string(e.getKey())).append(':');
			Object v = e.getValue();
			if (v instanceof Number n) {
				sb.append(Json.number(n.doubleValue()));
			} else if (v instanceof Boolean b) {
				sb.append(b);
			} else {
				sb.append(Json.string(v == null ? null : v.toString()));
			}
		}
		return sb.append("}}").toString();
	}

	public void doAction(String actionName) {
		doAction(actionName, Map.of());
	}

	public void doAction(String actionName, Map<String, ?> args) {
		if (http == null) {
			return;
		}
		try {
			String body = buildBody(actionName, new LinkedHashMap<>(args));
			HttpRequest request = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(3))
					.header("Content-Type", "application/json; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();

			http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
				if (error != null) {
					if (reachable.compareAndSet(true, false)) {
						DamageAlert.LOGGER.info("[DamageAlert] Streamer.bot unter {} nicht erreichbar (wird ignoriert): {}",
								endpoint, error.toString());
					}
				} else {
					if (reachable.compareAndSet(false, true)) {
						DamageAlert.LOGGER.info("[DamageAlert] Streamer.bot wieder erreichbar.");
					}
					if (response.statusCode() >= 300) {
						DamageAlert.LOGGER.debug("[DamageAlert] Streamer.bot antwortete {} auf {}", response.statusCode(), actionName);
					}
				}
			});
		} catch (RuntimeException e) {
			// Darf das Spiel nie stoeren
			DamageAlert.LOGGER.debug("[DamageAlert] Senden fehlgeschlagen: {}", e.toString());
		}
	}
}
