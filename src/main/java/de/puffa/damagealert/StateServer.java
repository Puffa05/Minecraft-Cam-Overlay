package de.puffa.damagealert;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Kleiner lokaler HTTP-Server: GET /state liefert den letzten {@link StateSnapshot} als JSON.
 * Nur an 127.0.0.1 gebunden, also nur vom eigenen PC erreichbar.
 */
public final class StateServer {
	private volatile StateSnapshot snapshot = StateSnapshot.NOT_IN_WORLD;
	private HttpServer server;
	private ExecutorService executor;

	/** Wird vom Client-Tick aufgerufen. */
	public void publish(StateSnapshot newSnapshot) {
		this.snapshot = newSnapshot;
	}

	public StateSnapshot current() {
		return snapshot;
	}

	/**
	 * Startet den Server. Schlaegt das fehl (z. B. Port belegt), wird nur eine Warnung geloggt.
	 * @return true, wenn der Server laeuft
	 */
	public boolean start(int port) {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
			ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "DamageAlert-StateServer");
				t.setDaemon(true); // haelt das Beenden des Spiels nicht auf
				return t;
			});
			s.createContext("/", this::handle);
			s.setExecutor(ex);
			s.start();
			this.server = s;
			this.executor = ex;
			DamageAlert.LOGGER.info("[DamageAlert] Zustands-Server laeuft auf http://127.0.0.1:{}/state", port);
			return true;
		} catch (IOException | RuntimeException e) {
			DamageAlert.LOGGER.warn("[DamageAlert] Zustands-Server konnte Port {} nicht oeffnen (belegt?). "
					+ "Overlay-Daten sind deaktiviert, das Spiel laeuft normal weiter. Grund: {}", port, e.toString());
			return false;
		}
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

	private void handle(HttpExchange exchange) {
		try (exchange) {
			Headers h = exchange.getResponseHeaders();
			// OBS-Browserquelle laedt das Overlay von einer lokalen Datei -> CORS erlauben
			h.set("Access-Control-Allow-Origin", "*");
			h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
			h.set("Access-Control-Allow-Headers", "*");
			h.set("Access-Control-Allow-Private-Network", "true");
			h.set("Cache-Control", "no-store");

			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();

			if ("OPTIONS".equalsIgnoreCase(method)) {
				exchange.sendResponseHeaders(204, -1);
				return;
			}
			if (!"/state".equals(path) && !"/state/".equals(path)) {
				sendText(exchange, 404, "text/plain; charset=utf-8", "Nicht gefunden. Benutze /state");
				return;
			}
			if (!"GET".equalsIgnoreCase(method)) {
				h.set("Allow", "GET, OPTIONS");
				sendText(exchange, 405, "text/plain; charset=utf-8", "Nur GET");
				return;
			}
			sendText(exchange, 200, "application/json; charset=utf-8", snapshot.toJson());
		} catch (IOException | RuntimeException e) {
			DamageAlert.LOGGER.debug("[DamageAlert] Fehler bei /state-Anfrage: {}", e.toString());
		}
	}

	private static void sendText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
