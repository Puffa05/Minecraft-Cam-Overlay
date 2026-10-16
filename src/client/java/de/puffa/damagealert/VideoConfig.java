package de.puffa.damagealert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Eigene Videos statt der eingebauten Themes. Eingestellt auf der Seite http://127.0.0.1:7475/,
 * gespeichert in config/damagealert.properties (Schluessel video.&lt;theme&gt;.*).
 */
public final class VideoConfig {

    /** Themes, die ein eigenes Video bekommen koennen (gleiche Namen wie in cam-themes.html). */
    public static final List<String> THEMES = List.of("portal", "underwater", "freeze", "cherry");

    /** Erlaubte Dateiendungen und ihr Typ. Andere Dateien liefert der Server nie aus. */
    private static final Map<String, String> TYPES = Map.of(
            "mp4", "video/mp4", "m4v", "video/mp4", "mov", "video/mp4", "webm", "video/webm");

    /** Wie das Video durchsichtig wird: gar nicht, Schwarz ausblenden, eine Farbe ausblenden (Greenscreen). */
    private static final Set<String> KEYS = Set.of("none", "black", "color");

    public record Entry(String path, String key, String color, double tolerance, double softness, double opacity) {
        static final Entry EMPTY = new Entry("", "black", "#00ff00", 0.05, 0.30, 1.0);
    }

    private static Path file;
    private static volatile Map<String, Entry> entries = emptyMap();

    private VideoConfig() {}

    private static Map<String, Entry> emptyMap() {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (String t : THEMES) m.put(t, Entry.EMPTY);
        return m;
    }

    /** Liest die Video-Einstellungen aus der Config-Datei. Fehler werden nur geloggt. */
    static synchronized void load(Path configFile) {
        file = configFile;
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) { props.load(r); }
            }
        } catch (IOException | IllegalArgumentException e) {
            DamageAlertClient.LOGGER.warn("Video-Einstellungen konnten nicht gelesen werden", e);
        }
        Map<String, Entry> m = new LinkedHashMap<>();
        for (String t : THEMES) {
            String p = "video." + t + ".";
            Entry d = Entry.EMPTY;
            String key = props.getProperty(p + "key", d.key());
            String color = props.getProperty(p + "color", d.color());
            m.put(t, new Entry(
                    props.getProperty(p + "path", "").trim(),
                    KEYS.contains(key) ? key : d.key(),
                    color.matches("#[0-9a-fA-F]{6}") ? color : d.color(),
                    num(props.getProperty(p + "tolerance"), d.tolerance()),
                    num(props.getProperty(p + "softness"), d.softness()),
                    num(props.getProperty(p + "opacity"), d.opacity())));
        }
        entries = Map.copyOf(m);
    }

    /**
     * Uebernimmt die Einstellungen von der Einstellungsseite und speichert sie.
     * @return Fehler je Theme (leer = gespeichert). Bei Fehlern wird nichts geaendert.
     */
    static synchronized Map<String, String> save(JsonObject themes) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Entry> m = new LinkedHashMap<>();
        for (String t : THEMES) {
            JsonObject o = themes.has(t) && themes.get(t).isJsonObject() ? themes.getAsJsonObject(t) : new JsonObject();
            Entry d = Entry.EMPTY;
            String path = cleanPath(str(o, "path", ""));
            String key = str(o, "key", d.key());
            String color = str(o, "color", d.color());
            if (!path.isEmpty()) {
                String error = checkPath(path);
                if (error != null) errors.put(t, error);
            }
            m.put(t, new Entry(path,
                    KEYS.contains(key) ? key : d.key(),
                    color.matches("#[0-9a-fA-F]{6}") ? color.toLowerCase(Locale.ROOT) : d.color(),
                    num(o, "tolerance", d.tolerance()),
                    num(o, "softness", d.softness()),
                    num(o, "opacity", d.opacity())));
        }
        if (!errors.isEmpty()) return errors;

        // Bestehende Datei einlesen, damit der Port und alles andere erhalten bleibt
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) { props.load(r); }
            } else {
                props.setProperty("port", "7475");
            }
            m.forEach((t, e) -> {
                String p = "video." + t + ".";
                props.setProperty(p + "path", e.path());
                props.setProperty(p + "key", e.key());
                props.setProperty(p + "color", e.color());
                props.setProperty(p + "tolerance", fmt(e.tolerance()));
                props.setProperty(p + "softness", fmt(e.softness()));
                props.setProperty(p + "opacity", fmt(e.opacity()));
            });
            try (Writer w = Files.newBufferedWriter(file)) {
                props.store(w, "port: muss zu STATE_URL im Overlay passen. video.*: wird auf http://127.0.0.1:"
                        + props.getProperty("port", "7475").trim() + "/ eingestellt");
            }
        } catch (IOException | IllegalArgumentException e) {
            DamageAlertClient.LOGGER.warn("Video-Einstellungen konnten nicht gespeichert werden", e);
            errors.put("", "Konnte die Einstellungen nicht speichern: " + e.getMessage());
            return errors;
        }
        entries = Map.copyOf(m);
        return errors;
    }

    /** Videodatei fuer ein Theme, oder null, wenn keine eingestellt ist oder sie nicht (mehr) da ist. */
    static Path videoFile(String theme) {
        Entry e = entries.get(theme);
        if (e == null || e.path().isEmpty() || checkPath(e.path()) != null) return null;
        return Path.of(e.path());
    }

    static String contentType(Path p) {
        return TYPES.getOrDefault(extension(p), "application/octet-stream");
    }

    /** Fuer das Overlay (/videos): nur Themes mit gueltigem Video, ohne Dateipfade. */
    static JsonObject publicJson() {
        JsonObject out = new JsonObject();
        for (String t : THEMES) {
            Path p = videoFile(t);
            String rev = p != null ? revision(p) : null;
            if (rev == null) continue;
            JsonObject o = settingsJson(entries.get(t));
            o.addProperty("rev", rev);
            out.add(t, o);
        }
        return out;
    }

    /** Fuer die Einstellungsseite (/config): alles inkl. Pfad und Status. */
    static JsonObject fullJson() {
        JsonObject themes = new JsonObject();
        for (String t : THEMES) {
            Entry e = entries.get(t);
            JsonObject o = settingsJson(e);
            o.addProperty("path", e.path());
            if (e.path().isEmpty()) {
                o.addProperty("status", "off");
            } else {
                String error = checkPath(e.path());
                o.addProperty("status", error == null ? "ok" : error);
                Path p = videoFile(t);
                if (p != null) o.addProperty("rev", revision(p));
            }
            themes.add(t, o);
        }
        JsonObject out = new JsonObject();
        out.add("themes", themes);
        return out;
    }

    private static JsonObject settingsJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("key", e.key());
        o.addProperty("color", e.color());
        o.addProperty("tolerance", e.tolerance());
        o.addProperty("softness", e.softness());
        o.addProperty("opacity", e.opacity());
        return o;
    }

    /** Aendert sich, wenn die Datei ersetzt wird, damit OBS nicht das alte Video aus dem Cache zeigt. */
    private static String revision(Path p) {
        try {
            return Long.toString(Files.getLastModifiedTime(p).toMillis(), 36) + "-" + Long.toString(Files.size(p), 36);
        } catch (IOException e) {
            return null;
        }
    }

    /** Fehlertext fuer einen Pfad, oder null, wenn er passt. */
    private static String checkPath(String path) {
        Path p;
        try {
            p = Path.of(path);
        } catch (InvalidPathException e) {
            return "Ungültiger Pfad";
        }
        if (!p.isAbsolute()) return "Bitte den vollständigen Pfad angeben, z. B. C:\\Videos\\portal.mp4";
        if (!TYPES.containsKey(extension(p))) return "Nur .mp4, .m4v, .mov oder .webm";
        if (!Files.isRegularFile(p)) return "Datei nicht gefunden";
        if (!Files.isReadable(p)) return "Datei kann nicht gelesen werden";
        return null;
    }

    /** Erlaubt auch "C:\...\x.mp4" mit Anfuehrungszeichen (Explorer: "Als Pfad kopieren") und file:///-Adressen. */
    private static String cleanPath(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).trim();
        if (s.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                s = Path.of(URI.create(s.replace(" ", "%20"))).toString();
            } catch (RuntimeException ignored) {
                // dann bleibt es beim Text, checkPath meldet den Fehler
            }
        }
        return s;
    }

    private static String extension(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement v = o.get(k);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def) {
        JsonElement v = o.get(k);
        return v != null && v.isJsonPrimitive() ? num(v.getAsString(), def) : def;
    }

    /** Zahl zwischen 0 und 1, sonst der Standardwert. */
    private static double num(String s, double def) {
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? Math.max(0, Math.min(1, d)) : def;
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }
}
