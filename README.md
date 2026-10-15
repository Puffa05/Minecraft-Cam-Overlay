# Damage Alert (Fabric-Client-Mod, Minecraft 1.21.11)

Liest Ingame-Zustände aus und gibt sie an den Stream weiter. **Nur lesend:** keine Pakete,
keine Eingaben, keine Mixins, keine Gameplay-Änderung.

1. **Einmalige Events → Streamer.bot** (HTTP POST an `http://127.0.0.1:7474/DoAction`)
   - `Minecraft Schaden` mit Args `damage` und `health` (Cooldown 1000 ms)
   - `Portal Start` / `Portal Ende` (Nether-Portal)
2. **Dauerzustände → OBS-Overlay**: `GET http://127.0.0.1:7475/state`
   ```json
   {"inWorld":true,"underwater":false,"inLava":false,"freeze":0.56,
    "biome":"minecraft:cherry_grove","dimension":"minecraft:overworld",
    "y":64,"raining":false,"thundering":false}
   ```

## Installation (NoRisk Client)
- `build/libs/damagealert-1.0.0.jar` ins Mod-Profil legen (**nicht** die `-sources.jar`).
- **Fabric API** (`0.141.6+1.21.11` oder neuer für 1.21.11) muss im Profil sein.
- In Streamer.bot: *Servers/Clients → HTTP Server* aktivieren (Port 7474) und Actions
  mit exakt diesen Namen anlegen. Die Args stehen in der Action als `%damage%` / `%health%` bereit.

## Effekte an/aus und testen – im Spiel oder im Browser
Beide Wege nutzen dieselben Einstellungen und zeigen Änderungen des anderen sofort an.
- **Im Spiel:** Taste **J** öffnet das Damage-Alert-Menü (Taste änderbar unter
  *Optionen → Steuerung → Damage Alert*). Reine Client-Anzeige, es geht nichts an den Server.
- **Im Browser:** Solange Minecraft mit dem Mod läuft, **http://127.0.0.1:7475/** öffnen
  (geht auch im Hauptmenü).
- Jeder Effekt (Schaden, Portal, Unterwasser, Lava, Frieren, Regen, Gewitter, Biom, Dimension, Y)
  lässt sich einzeln abschalten. Das wirkt sofort und wird in der Config gespeichert.
- **Test-Buttons:** Events werden einmal an Streamer.bot geschickt (mit Rückmeldung, ob es geklappt hat).
  Zustände werden 5 Sekunden lang als „an“ gemeldet, auch im Hauptmenü, zum Prüfen des Overlays.
- Schalten geht nur von dieser Seite aus; fremde Webseiten können weder schalten noch Tests auslösen.

## Config
`config/damagealert.properties` wird beim ersten Start angelegt (Ports, Action-Namen,
Cooldown, `enabled.*`-Schalter). Ports, Namen und Cooldown gelten nach einem Neustart,
die Schalter ändert die Oberfläche live.

## Overlay-Beispiel (OBS-Browserquelle)
```html
<script>
setInterval(async () => {
  try {
    const s = await (await fetch("http://127.0.0.1:7475/state")).json();
    document.body.classList.toggle("underwater", s.underwater);
    document.body.style.setProperty("--freeze", s.freeze);
  } catch (e) { /* Spiel laeuft nicht */ }
}, 250);
</script>
```

## Bauen & Testen
Voraussetzung: JDK 21.
```sh
./gradlew build                          # -> build/libs/damagealert-1.0.0.jar
java tools/MockStreamerBot.java          # Fake-Streamer.bot auf Port 7474, zeigt Requests an
./gradlew runClient                      # Minecraft mit dem Mod starten
curl http://127.0.0.1:7475/state         # Zustand abfragen
./gradlew test                           # Unit-Tests (Config, JSON, Oberflächen-API, Streamer.bot aus/hängt)
./gradlew runClientGameTest              # automatischer Ingame-Test (Schaden, Portal, Zustände, Schalter)
```

## Versionen
Minecraft 1.21.11 · Fabric Loader 0.19.5 · Fabric API 0.141.6+1.21.11 · Yarn 1.21.11+build.6 ·
Loom 1.17.21 (Loom 1.18 bräuchte Java 25 zum Bauen) · Gradle 9.7.1
