package de.puffa.damagealert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Schickt Actions per HTTP POST an Streamer.bot ({@code /DoAction}).
 * Alles laeuft asynchron im Hintergrund: Das Spiel wartet nie auf eine Antwort,
 * und Fehler (z. B. Streamer.bot laeuft nicht) werden ignoriert.
 */
public final class StreamerBotClient {
	/** Ergebnis eines Sendeversuchs (fuer die Test-Buttons der Oberflaeche). */
	public record SendResult(boolean ok, int status, String error) {
		public String toJson() {
			return "{\"ok\":" + ok + ",\"status\":" + status + ",\"error\":" + Json.string(error) + "}";
		}
	}

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

	public URI endpoint() {
		return endpoint;
	}

	public CompletableFuture<SendResult> doAction(String actionName) {
		return doAction(actionName, Map.of());
	}

	/**
	 * Schickt eine Action. Kehrt sofort zurueck; das Ergebnis kommt spaeter ueber das Future.
	 * Das Future wird nie mit einer Exception abgeschlossen, Fehler stehen im SendResult.
	 */
	public CompletableFuture<SendResult> doAction(String actionName, Map<String, ?> args) {
		if (http == null) {
			return CompletableFuture.completedFuture(new SendResult(false, 0, "HTTP-Client nicht verfügbar"));
		}
		try {
			// Sortiert, damit der Body immer gleich aussieht (damage vor health)
			String body = buildBody(actionName, new TreeMap<>(args));
			HttpRequest request = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(3))
					.header("Content-Type", "application/json; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();

			return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
				if (error != null) {
					if (reachable.compareAndSet(true, false)) {
						DamageAlert.LOGGER.info("[DamageAlert] Streamer.bot unter {} nicht erreichbar (wird ignoriert): {}",
								endpoint, error.toString());
					}
					return new SendResult(false, 0, describe(error));
				}
				if (reachable.compareAndSet(false, true)) {
					DamageAlert.LOGGER.info("[DamageAlert] Streamer.bot wieder erreichbar.");
				}
				int status = response.statusCode();
				if (status >= 300) {
					DamageAlert.LOGGER.debug("[DamageAlert] Streamer.bot antwortete {} auf {}", status, actionName);
				}
				return new SendResult(status < 300, status, status < 300 ? null : "Streamer.bot antwortete " + status);
			});
		} catch (RuntimeException e) {
			// Darf das Spiel nie stoeren
			DamageAlert.LOGGER.debug("[DamageAlert] Senden fehlgeschlagen: {}", e.toString());
			return CompletableFuture.completedFuture(new SendResult(false, 0, e.toString()));
		}
	}

	/** Uebersetzt Java-Fehler in eine verstaendliche Meldung fuer die Oberflaeche. */
	static String describe(Throwable error) {
		Throwable root = error;
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof java.net.http.HttpTimeoutException) {
				return "Zeitüberschreitung: Streamer.bot antwortet nicht";
			}
			// Verbindung abgelehnt zeigt sich je nach System als ConnectException oder ClosedChannelException
			if (t instanceof java.net.ConnectException || t instanceof java.nio.channels.ClosedChannelException) {
				return "Streamer.bot nicht erreichbar (läuft er? HTTP-Server aktiviert?)";
			}
			root = t;
		}
		return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
	}
}
