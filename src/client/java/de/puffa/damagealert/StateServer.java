package de.puffa.damagealert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Mini-HTTP-Server, nur vom eigenen PC aus erreichbar (127.0.0.1). */
public final class StateServer {

    private static HttpServer server;

    private StateServer() {}

    public static void start(int port, Supplier<String> json) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/state", exchange -> handle(exchange, json));
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "damagealert-state-server");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            DamageAlertClient.LOGGER.info("Zustands-Server läuft auf http://127.0.0.1:{}/state", port);
        } catch (IOException | RuntimeException e) {
            // Port belegt, ungültiger Port (z. B. 99999) o. Ä.: Spiel läuft trotzdem normal weiter
            DamageAlertClient.LOGGER.warn("Zustands-Server konnte nicht starten (Port {} belegt?)", port, e);
            server = null;
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void handle(HttpExchange exchange, Supplier<String> json) throws IOException {
        try {
            var headers = exchange.getResponseHeaders();
            // Erlaubt der OBS-Browser-Quelle (lokale Datei), die Antwort zu lesen
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Cache-Control", "no-store");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            byte[] body = json.get().getBytes(StandardCharsets.UTF_8);
            headers.set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }
}
