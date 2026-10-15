package de.puffa.damagealert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Kleiner lokaler HTTP-Server, nur an 127.0.0.1 gebunden:
 * <ul>
 *   <li>{@code GET /state} - Spielzustand, Todeszaehler und Ereignisse als JSON fuer die OBS-Overlays (CORS offen)</li>
 *   <li>{@code GET /} - Oberflaeche zum An-/Abschalten und Testen der Effekte</li>
 *   <li>{@code /api/...} - wird nur von der Oberflaeche selbst benutzt (kein CORS, Origin-Pruefung)</li>
 * </ul>
 */
public final class StateServer {
	/** Header, den nur die eigene Oberflaeche mitschickt (fremde Webseiten koennen ihn nicht ohne CORS-Freigabe setzen). */
	static final String API_HEADER = "X-DamageAlert";

	private final EffectController controller;
	private volatile StateSnapshot snapshot = StateSnapshot.NOT_IN_WORLD;

	private HttpServer server;
	private ExecutorService executor;
	private int port;

	public StateServer(EffectController controller) {
		this.controller = controller;
	}

	/** Wird vom Client-Tick aufgerufen. */
	public void publish(StateSnapshot newSnapshot) {
		this.snapshot = newSnapshot;
	}

	/** Was /state gerade ausliefert (mit Schaltern, Tests, Todeszaehler und Ereignissen). */
	public String stateJson() {
		return controller.stateJson(snapshot);
	}

	/**
	 * Startet den Server. Schlaegt das fehl (z. B. Port belegt), wird nur eine Warnung geloggt.
	 * @return true, wenn der Server laeuft
	 */
	public boolean start(int port) {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
			// Mehrere Threads, weil mehrere Overlays (eins pro OBS-Szene) gleichzeitig abfragen
			ExecutorService ex = Executors.newFixedThreadPool(4, r -> {
				Thread t = new Thread(r, "DamageAlert-StateServer");
				t.setDaemon(true); // haelt das Beenden des Spiels nicht auf
				return t;
			});
			s.createContext("/", this::handle);
			s.setExecutor(ex);
			s.start();
			this.server = s;
			this.executor = ex;
			this.port = s.getAddress().getPort();
			DamageAlert.LOGGER.info("[DamageAlert] Zustands-Server laeuft auf http://127.0.0.1:{}/state , Oberflaeche: http://127.0.0.1:{}/",
					this.port, this.port);
			return true;
		} catch (IOException | RuntimeException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] Zustands-Server konnte Port {} nicht oeffnen (belegt?). "
					+ "Overlay-Daten und Oberflaeche sind deaktiviert, das Spiel laeuft normal weiter. Grund: {}", port, e.toString());
			return false;
		}
	}

	public int port() {
		return port;
	}

	public void stop() {
		try {
			if (server != null) {
				server.stop(0);
			}
			if (executor != null) {
				executor.shutdownNow();
			}
		} catch (RuntimeException ignored) {
			// Beim Beenden egal
		}
		server = null;
		executor = null;
	}

	// ------------------------------------------------------------------ Routing

	private void handle(HttpExchange exchange) {
		try (exchange) {
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
			String path = exchange.getRequestURI().getPath();

			// Schutz gegen DNS-Rebinding: nur Anfragen an 127.0.0.1/localhost annehmen
			if (!isAllowedHost(exchange.getRequestHeaders().getFirst("Host"), port)) {
				sendText(exchange, 403, "text/plain; charset=utf-8", "Falscher Host");
				return;
			}

			if (path.equals("/state") || path.equals("/state/")) {
				handleState(exchange, method);
			} else if (path.startsWith("/api/")) {
				handleApi(exchange, method, path);
			} else if (path.equals("/") || path.equals("/index.html")) {
				if (!method.equals("GET")) {
					sendText(exchange, 405, "text/plain; charset=utf-8", "Nur GET");
					return;
				}
				sendPage(exchange);
			} else {
				sendText(exchange, 404, "text/plain; charset=utf-8", "Nicht gefunden. Benutze /state oder /");
			}
		} catch (IOException | RuntimeException e) {
			DamageAlert.LOGGER.debug("[DamageAlert] Fehler bei HTTP-Anfrage: {}", e.toString());
		}
	}

	private void handleState(HttpExchange exchange, String method) throws IOException {
		Headers h = exchange.getResponseHeaders();
		// OBS-Browserquelle laedt das Overlay von einer lokalen Datei -> CORS erlauben
		h.set("Access-Control-Allow-Origin", "*");
		h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
		h.set("Access-Control-Allow-Headers", "*");
		h.set("Access-Control-Allow-Private-Network", "true");
		if (method.equals("OPTIONS")) {
			exchange.sendResponseHeaders(204, -1);
			return;
		}
		if (!method.equals("GET")) {
			h.set("Allow", "GET, OPTIONS");
			sendText(exchange, 405, "text/plain; charset=utf-8", "Nur GET");
			return;
		}
		sendText(exchange, 200, "application/json; charset=utf-8", stateJson());
	}

	private void handleApi(HttpExchange exchange, String method, String path) throws IOException {
		// Bewusst KEINE CORS-Header: fremde Webseiten sollen hier weder lesen noch schalten koennen
		if (path.equals("/api/settings") && method.equals("GET")) {
			sendJson(exchange, 200, settingsJson());
			return;
		}
		if (!method.equals("POST")) {
			sendJson(exchange, 405, "{\"ok\":false,\"error\":\"Nur POST\"}");
			return;
		}
		if (!isTrustedPost(exchange.getRequestHeaders().getFirst("Origin"),
				exchange.getRequestHeaders().getFirst(API_HEADER), port)) {
			sendJson(exchange, 403, "{\"ok\":false,\"error\":\"Nur von der Oberfläche erlaubt\"}");
			return;
		}

		Map<String, String> q = query(exchange.getRequestURI().getRawQuery());
		switch (path) {
			case "/api/toggle" -> {
				Effect effect = Effect.byId(q.get("effect"));
				String enabled = q.get("enabled");
				if (effect == null || !("true".equals(enabled) || "false".equals(enabled))) {
					sendJson(exchange, 400, "{\"ok\":false,\"error\":\"effect oder enabled ungültig\"}");
					return;
				}
				controller.setEnabled(effect, Boolean.parseBoolean(enabled));
				sendJson(exchange, 200, settingsJson());
			}
			case "/api/test" -> handleTest(exchange, q);
			case "/api/deaths" -> handleDeaths(exchange, q);
			default -> sendJson(exchange, 404, "{\"ok\":false,\"error\":\"Unbekannter API-Pfad\"}");
		}
	}

	private void handleTest(HttpExchange exchange, Map<String, String> q) throws IOException {
		String event = q.get("event");
		if (event != null) {
			EffectController.TestEvent e = EffectController.TestEvent.byId(event);
			if (e == null) {
				sendJson(exchange, 400, "{\"ok\":false,\"error\":\"Unbekanntes Ereignis\"}");
				return;
			}
			controller.testEvent(e);
			sendJson(exchange, 200, "{\"ok\":true}");
			return;
		}

		if (!controller.testState(Effect.byId(q.get("state")))) {
			sendJson(exchange, 400, "{\"ok\":false,\"error\":\"Unbekannter oder nicht testbarer Zustand\"}");
			return;
		}
		sendJson(exchange, 200, "{\"ok\":true,\"millis\":" + EffectController.STATE_TEST_MILLIS + "}");
	}

	private void handleDeaths(HttpExchange exchange, Map<String, String> q) throws IOException {
		DeathCounter d = controller.deaths();
		DeathCounter.Counts c;
		switch (q.getOrDefault("op", "")) {
			case "inc" -> c = d.adjust(1);
			case "dec" -> c = d.adjust(-1);
			case "resetSession" -> c = d.resetSession();
			case "resetTotal" -> c = d.resetTotal();
			default -> {
				sendJson(exchange, 400, "{\"ok\":false,\"error\":\"op muss inc, dec, resetSession oder resetTotal sein\"}");
				return;
			}
		}
		DamageAlert.LOGGER.info("[DamageAlert] Todeszaehler {} -> gesamt {}, Stream {}", q.get("op"), c.total(), c.session());
		sendJson(exchange, 200, settingsJson());
	}

	private String settingsJson() {
		DamageAlertConfig c = controller.config().get();
		StringBuilder effects = new StringBuilder("{");
		for (Effect e : Effect.values()) {
			if (effects.length() > 1) {
				effects.append(',');
			}
			effects.append(Json.string(e.id())).append(':').append(c.isEnabled(e));
		}
		effects.append('}');
		return "{\"effects\":" + effects
				+ ",\"deaths\":" + controller.deaths().get().toJson()
				+ ",\"cooldownMs\":" + c.damageCooldownMs()
				+ ",\"stateTestMillis\":" + EffectController.STATE_TEST_MILLIS + "}";
	}

	// ------------------------------------------------------------------ Sicherheits-Pruefungen

	/** Nur Host-Header 127.0.0.1:port oder localhost:port (schuetzt vor DNS-Rebinding). */
	static boolean isAllowedHost(String host, int port) {
		if (host == null) {
			return false;
		}
		String h = host.trim().toLowerCase(java.util.Locale.ROOT);
		return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
	}

	/**
	 * Schaltende Anfragen nur von der eigenen Oberflaeche: eigener Header noetig, und falls
	 * der Browser eine Origin mitschickt, muss es diese Seite selbst sein.
	 */
	static boolean isTrustedPost(String origin, String apiHeader, int port) {
		if (apiHeader == null) {
			return false;
		}
		if (origin == null) {
			return true; // z. B. curl
		}
		String o = origin.trim().toLowerCase(java.util.Locale.ROOT);
		return o.equals("http://127.0.0.1:" + port) || o.equals("http://localhost:" + port);
	}

	static Map<String, String> query(String rawQuery) {
		Map<String, String> map = new HashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return map;
		}
		for (String pair : rawQuery.split("&")) {
			int i = pair.indexOf('=');
			String k = i < 0 ? pair : pair.substring(0, i);
			String v = i < 0 ? "" : pair.substring(i + 1);
			map.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
		}
		return map;
	}

	// ------------------------------------------------------------------ Antworten

	private void sendPage(HttpExchange exchange) throws IOException {
		try (InputStream in = StateServer.class.getResourceAsStream("/assets/damagealert/web/index.html")) {
			if (in == null) {
				sendText(exchange, 500, "text/plain; charset=utf-8", "Oberflaeche fehlt in der Mod-Datei");
				return;
			}
			Headers h = exchange.getResponseHeaders();
			h.set("X-Frame-Options", "DENY");
			h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
			sendBytes(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
		}
	}

	private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
		sendText(exchange, status, "application/json; charset=utf-8", json);
	}

	private static void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
