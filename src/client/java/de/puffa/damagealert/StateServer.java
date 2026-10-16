package de.puffa.damagealert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
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
    private static int port;

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
            StateServer.port = port;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/state", exchange -> handle(exchange, json));
            server.createContext("/texture/", StateServer::handleTexture);
            server.createContext("/", StateServer::handleSettingsPage);
            server.createContext("/config", StateServer::handleConfig);
            server.createContext("/videos", StateServer::handleVideoList);
            server.createContext("/video/", StateServer::handleVideo);
            // Mehrere Threads: Ein Video, das gerade geladen wird, darf /state nicht aufhalten
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "damagealert-state-server");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            DamageAlertClient.LOGGER.info("Zustands-Server läuft auf http://127.0.0.1:{}/state", port);
            DamageAlertClient.LOGGER.info("Einstellungen (eigene Videos): http://127.0.0.1:{}/", port);
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

    // ---------------- Eigene Videos ----------------

    /**
     * Nur Aufrufe an 127.0.0.1/localhost mit unserem Port. Schuetzt gegen Webseiten, die per
     * DNS-Rebinding ihre eigene Adresse auf 127.0.0.1 umbiegen und so den Server erreichen wollen.
     */
    private static boolean hostOk(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port)
                || (port == 80 && (host.equals("127.0.0.1") || host.equals("localhost")));
    }

    /** Aenderungen nur von der Einstellungsseite selbst, nicht von fremden Webseiten im Browser. */
    private static boolean originOk(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        origin = origin.toLowerCase(Locale.ROOT);
        String suffix = port == 80 ? "" : ":" + port;
        return origin.equals("http://127.0.0.1" + suffix) || origin.equals("http://localhost" + suffix);
    }

    /** GET /: Einstellungsseite (im normalen Browser oeffnen). */
    private static void handleSettingsPage(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!hostOk(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            byte[] body;
            try (InputStream in = StateServer.class.getResourceAsStream("/damagealert/settings.html")) {
                if (in == null) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                body = in.readAllBytes();
            }
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Security-Policy", "frame-ancestors 'none'");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    /** GET /config: alle Video-Einstellungen. POST /config (JSON): speichern. Nur fuer die Einstellungsseite. */
    private static void handleConfig(HttpExchange exchange) throws IOException {
        try {
            // Absichtlich kein Access-Control-Allow-Origin: Andere Seiten duerfen weder lesen noch schreiben
            if (!hostOk(exchange) || !originOk(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 200, VideoConfig.fullJson());
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                exchange.sendResponseHeaders(415, -1);
                return;
            }
            byte[] raw;
            try (InputStream in = exchange.getRequestBody()) {
                raw = in.readNBytes(64 * 1024);
            }
            JsonObject themes;
            try {
                JsonElement root = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                themes = root.getAsJsonObject().getAsJsonObject("themes");
                if (themes == null) throw new IllegalStateException("themes fehlt");
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Map<String, String> errors = VideoConfig.save(themes);
            JsonObject out = VideoConfig.fullJson();
            out.addProperty("ok", errors.isEmpty());
            JsonObject err = new JsonObject();
            errors.forEach(err::addProperty);
            out.add("errors", err);
            sendJson(exchange, errors.isEmpty() ? 200 : 400, out);
        } finally {
            exchange.close();
        }
    }

    /** GET /videos: welche Themes ein eigenes Video haben (ohne Dateipfade). Fragt das Overlay ab. */
    private static void handleVideoList(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            sendJson(exchange, 200, VideoConfig.publicJson());
        } finally {
            exchange.close();
        }
    }

    /**
     * GET /video/portal usw.: die eingestellte Videodatei, mit Range-Unterstuetzung (braucht der Browser
     * zum Abspielen und Wiederholen). Nur die eingestellten Dateien, keine beliebigen Pfade.
     */
    private static void handleVideo(HttpExchange exchange) throws IOException {
        try {
            var headers = exchange.getResponseHeaders();
            // Das Overlay muss die Bilder lesen duerfen, um Schwarz/Greenscreen herauszurechnen
            headers.set("Access-Control-Allow-Origin", "*");
            if (!hostOk(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String theme = exchange.getRequestURI().getPath().substring("/video/".length());
            Path file = VideoConfig.videoFile(theme);
            if (file == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            try (FileChannel ch = FileChannel.open(file)) {
                long size = ch.size();
                long start = 0, end = size - 1;
                int status = 200;
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
                    String spec = range.substring(6).trim();
                    int dash = spec.indexOf('-');
                    try {
                        if (dash == 0) {                       // bytes=-500: die letzten 500 Bytes
                            start = Math.max(0, size - Long.parseLong(spec.substring(1)));
                        } else if (dash > 0) {                 // bytes=100- oder bytes=100-199
                            start = Long.parseLong(spec.substring(0, dash));
                            if (dash < spec.length() - 1) end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                        }
                        if (start > end || start >= size) {
                            headers.set("Content-Range", "bytes */" + size);
                            exchange.sendResponseHeaders(416, -1);
                            return;
                        }
                        status = 206;
                        headers.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
                    } catch (NumberFormatException e) {
                        start = 0;                              // kaputte Range: ganze Datei
                        end = size - 1;
                    }
                }
                headers.set("Content-Type", VideoConfig.contentType(file));
                headers.set("Accept-Ranges", "bytes");
                headers.set("Cache-Control", "no-cache");
                long remaining = end - start + 1;
                exchange.sendResponseHeaders(status, remaining > 0 ? remaining : -1);
                try (OutputStream out = exchange.getResponseBody()) {
                    ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
                    long pos = start;
                    while (remaining > 0) {
                        buf.clear();
                        if (remaining < buf.capacity()) buf.limit((int) remaining);
                        int n = ch.read(buf, pos);
                        if (n < 0) break;
                        out.write(buf.array(), 0, n);
                        pos += n;
                        remaining -= n;
                    }
                }
            } catch (IOException e) {
                // Normal: Der Browser bricht das Laden ab, sobald er genug hat
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
