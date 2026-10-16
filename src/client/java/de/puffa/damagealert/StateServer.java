package de.puffa.damagealert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/** Mini-HTTP-Server, nur vom eigenen PC aus erreichbar (127.0.0.1). */
public final class StateServer {

    private static HttpServer server;

    /**
     * Texturen, die das Overlay aus deinem installierten Minecraft laden darf (inkl. Ressourcenpaket).
     * Nur diese feste Liste, keine beliebigen Dateien. So landen keine Mojang-Dateien im Repo.
     */
    private static final Map<String, Identifier> TEXTURES = buildTextureList();

    private static Map<String, Identifier> buildTextureList() {
        Map<String, Identifier> m = new HashMap<>();
        m.put("nether_portal", Identifier.ofVanilla("textures/block/nether_portal.png"));
        m.put("water_still", Identifier.ofVanilla("textures/block/water_still.png"));
        m.put("underwater", Identifier.ofVanilla("textures/misc/underwater.png"));
        m.put("bubble", Identifier.ofVanilla("textures/particle/bubble.png"));
        m.put("cod", Identifier.ofVanilla("textures/item/cod.png"));
        m.put("salmon", Identifier.ofVanilla("textures/item/salmon.png"));
        m.put("tropical_fish", Identifier.ofVanilla("textures/item/tropical_fish.png"));
        m.put("pufferfish", Identifier.ofVanilla("textures/item/pufferfish.png"));
        m.put("powder_snow_outline", Identifier.ofVanilla("textures/misc/powder_snow_outline.png"));
        m.put("damage", Identifier.ofVanilla("textures/particle/damage.png")); // Schadens-Herz-Partikel
        for (int i = 0; i < 12; i++) { // Kirschblueten-Partikel
            m.put("cherry_" + i, Identifier.ofVanilla("textures/particle/cherry_" + i + ".png"));
        }
        for (int i = 0; i < 8; i++) {  // Schneeflocken-Partikel
            m.put("generic_" + i, Identifier.ofVanilla("textures/particle/generic_" + i + ".png"));
        }
        return Map.copyOf(m);
    }

    private StateServer() {}

    public static void start(int port, Supplier<String> json) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/state", exchange -> handle(exchange, json));
            server.createContext("/texture/", StateServer::handleTexture);
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

    /** GET /texture/nether_portal.png usw.: liefert die PNG-Datei aus dem laufenden Spiel. */
    private static void handleTexture(HttpExchange exchange) throws IOException {
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", "*");
            String name = exchange.getRequestURI().getPath().substring("/texture/".length()).replace(".png", "");
            Identifier id = TEXTURES.get(name);
            byte[] body = null;
            if (id != null) {
                Optional<Resource> resource = MinecraftClient.getInstance().getResourceManager().getResource(id);
                if (resource.isPresent()) {
                    try (var in = resource.get().getInputStream()) {
                        body = in.readAllBytes();
                    }
                }
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            headers.set("Content-Type", "image/png");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (RuntimeException e) {
            // Darf das Spiel nie stoeren: z. B. waehrend Ressourcen neu geladen werden
            exchange.sendResponseHeaders(503, -1);
        } finally {
            exchange.close();
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
