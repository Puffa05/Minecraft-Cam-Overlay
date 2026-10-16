# Minecraft-Cam-Overlay

Wenn in Minecraft etwas passiert (Schaden, Portal, Unterwasser, Frieren, Kirschblüten-Biom, Blitze),
verändert sich deine Webcam im Stream passend: z. B. wirst du bei Schaden kurz rot wie ein
getroffener Minecraft-Spieler und dunkelrote Schadens-Herzen fliegen hoch, unter Wasser schwimmen Fische über die Cam.

```
Minecraft-Mod  ->  lokaler HTTP-Server  ->  OBS-Browser-Quelle (cam-themes.html)
```

1. Der Fabric-Client-Mod liest jeden Tick den Spielzustand.
2. Er stellt ihn als JSON bereit: `GET http://127.0.0.1:7475/state`
3. `cam-themes.html` liegt in OBS als Browser-Quelle über der Cam, fragt alle 100 ms den Zustand ab
   und zeichnet die Effekte selbst.

Kein Zwischenprogramm (kein Streamer.bot). Der Mod **liest nur**: Er schickt nichts an den
Minecraft-Server, simuliert keine Eingaben, ändert nichts am Gameplay und nutzt keine Mixins.
Fehler im Mod (auch ein ungültiger Port in der Config) werden abgefangen und nur geloggt,
das Spiel läuft immer weiter.

---

## Herunterladen

Im Ordner [`download/`](download/) liegt immer der aktuelle Stand
(Datei anklicken → „Download raw file“):

| Datei | Wofür |
|---|---|
| `damagealert-1.0.0.jar` | Die Mod (**nicht** die `-sources.jar`, die liegt dort gar nicht erst) |
| `cam-themes.html` | Das OBS-Overlay für die Kamera |

## Einrichten

### 1. Mod installieren (NoRisk Client)
- `damagealert-1.0.0.jar` als eigene Mod in dein Minecraft-1.21.11-Profil legen.
- **Fabric API** muss im selben Profil installiert sein (`0.141.6+1.21.11` oder neuer).
  Der Mod nutzt deren Tick- und Lifecycle-Events, ohne Fabric API startet er nicht.
- Beim Start steht im Log: `Zustands-Server läuft auf http://127.0.0.1:7475/state`

### 2. Overlay in OBS
1. In der Szene mit deiner Kamera: **Quelle hinzufügen → Browser**.
2. **Lokale Datei** anhaken und `cam-themes.html` auswählen.
3. Breite und Höhe **genau wie deine Kamera-Quelle** einstellen (z. B. 1280 × 720).
4. Die Browser-Quelle **direkt über** die Kamera legen, gleiche Position und Größe.

### 3. Prüfen
- Läuft Minecraft mit dem Mod, zeigt http://127.0.0.1:7475/state im Browser den aktuellen Zustand.
- Overlay ohne Minecraft ansehen: im Browser `cam-themes.html?demo` öffnen (wechselt alle 6 s das Theme)
  oder gezielt `?theme=portal`, `?theme=underwater`, `?theme=freeze`, `?theme=cherry`, `?theme=lightning`,
  `?theme=damage` (Treffer alle 2 s).

### Schaden
Bei jedem HP-Verlust:
- **Du wirst 0,5 s rot**, nur du, nicht der Hintergrund. Genau so färbt Minecraft getroffene Spieler
  und Mobs (10 Ticks). Dafür ist eine einmalige Einrichtung in OBS nötig, siehe
  [Rot bei Schaden](#rot-bei-schaden-nur-du-nicht-der-hintergrund). Ohne sie gibt es den Rest trotzdem.
- **Dunkelrote Schadens-Herzen** (die Original-Partikel aus dem Spiel) fliegen links und rechts neben
  dir hoch, mehr bei mehr Schaden (bis `HIT_PARTICLES`).
- Ein kurzer **roter Rahmen** (`HIT_ALPHA`, `0` = aus).

### Rot bei Schaden (nur du, nicht der Hintergrund)
Das Overlay sieht dein Kamerabild nicht, es liegt nur darüber. Deshalb übernimmt OBS das Ausschneiden:
Eine zweite, ausgeschnittene Kopie von dir liegt deckungsgleich über deiner Cam. Bei einem Treffer
schaltet das Overlay daran über obs-websocket einen Rot-Filter kurz ein. Ist er aus, sieht die Kopie
genauso aus wie du, man bemerkt sie also nicht. Eine Kamera-Berechtigung braucht das Overlay dafür nicht.

Voraussetzungen: **OBS 28 oder neuer** und eine Personenerkennung als Filter:
- das kostenlose Plugin **Background Removal** (obs-backgroundremoval, im OBS-Forum unter
  „Plugins“), läuft auf jeder Grafikkarte, kostet aber etwas CPU, oder
- mit NVIDIA-RTX-Karte den Filter **NVIDIA Background Removal**, falls er bei dir in der Filterliste steht.

Einrichten (einmalig):
1. **Neue Szene `Cam-Rot`** anlegen. Darin: Quelle hinzufügen → *Vorhandene hinzufügen* → deine
   Kamera. Rechtsklick auf die Kamera → *Transformieren* → *An Bildschirm anpassen* (Strg+F).
2. In der Szenenliste Rechtsklick auf `Cam-Rot` → **Filter**, dann zwei Effektfilter in dieser Reihenfolge:
   1. **Background Removal** (bzw. NVIDIA Background Removal): macht den Hintergrund durchsichtig.
   2. **Farbkorrektur**, Name genau **`Schaden-Rot`**: *Farbe multiplizieren* `#FFB3B3`,
      *Farbe hinzufügen* `#4D0000` (das ergibt ca. 30 % Rot, wie im Spiel). Danach am Augen-Symbol
      ausschalten (das Overlay macht das beim Verbinden aber auch selbst).
3. In deiner **normalen Szene**: Quelle hinzufügen → *Szene* → `Cam-Rot`. Sie gehört **über** die
   Kamera und **unter** das Browser-Overlay. Dann bei der Kamera Rechtsklick → *Transformieren* →
   *Transformation kopieren*, bei `Cam-Rot` → *Transformation einfügen*. Hast du die Kamera zugeschnitten
   oder ist sie nicht 16:9, musst du `Cam-Rot` von Hand genau darüberlegen.
4. **Werkzeuge → WebSocket-Server-Einstellungen**: *WebSocket-Server aktivieren* anhaken,
   *Verbindungsinformationen anzeigen* → Passwort kopieren und oben in `cam-themes.html` bei
   `OBS_PASSWORD` eintragen. Port und Namen stehen dort ebenfalls (`OBS_WS_URL`, `OBS_SOURCE`, `OBS_FILTER`).
   Achtung: Mit dem Passwort kann man OBS fernsteuern. Gib die Datei mit eingetragenem Passwort
   also nicht weiter.
5. Browser-Quelle in OBS neu laden (Eigenschaften → *Cache der aktuellen Seite aktualisieren*).

Testen: Während OBS läuft, `cam-themes.html?theme=damage` im normalen Browser öffnen. Du solltest
in OBS alle 2 s kurz rot werden. Wenn nicht: In der Browser-Konsole (F12) steht, ob das Passwort
falsch ist oder Quelle bzw. Filter anders heißen.

### Blitze
Bei jedem echten Blitz im Spiel zuckt am Rand der Cam ein Minecraft-Blitz herab und das Bild hellt
kurz auf, nahe Blitze stärker als ferne. Es kommen nur Blitze an, die dein Spiel auch anzeigt: Wie
weit das reicht, bestimmt die Sichtweite des Servers (im Test: bis ca. 60–100 Blöcke).
Stärke und Reichweite stellst du oben in `cam-themes.html` mit `LIGHTNING_ALPHA` und `LIGHTNING_FAR` ein.

## Was der Mod liefert

`GET http://127.0.0.1:7475/state`, z. B.:
```json
{"inWorld":true,"inPortal":false,"underwater":true,"inLava":false,"onFire":false,
 "freeze":0.000,"health":16.0,"maxHealth":20.0,"hits":3,"damageTotal":12.0,
 "biome":"minecraft:cherry_grove","dimension":"minecraft:overworld","y":64.0,
 "raining":false,"thundering":false,"lightning":2,"lightningDist":38.5}
```

| Feld | Bedeutung |
|---|---|
| `inWorld` | in einer Welt (sonst Hauptmenü/Laden: nur wenige Felder, alles „aus“) |
| `inPortal` | die Hitbox berührt ein Nether-Portal (erst nachdem man einmal außerhalb stand, damit der Effekt im Ziel-Portal nicht sofort neu startet) |
| `underwater` | Augen unter Wasser |
| `inLava` / `onFire` | in Lava / brennt |
| `freeze` | Gefrier-Fortschritt 0 bis 1 (wie das Frost-Overlay im Spiel) |
| `health` / `maxHealth` | Lebenspunkte (20 = 10 Herzen) |
| `hits` | Trefferzähler, steigt bei jedem HP-Verlust. 40 Ticks Ruhe nach Join, Respawn und Dimensionswechsel. |
| `damageTotal` | Summe aller verlorenen HP (steigt nur). Das Overlay berechnet daraus, wie stark ein Treffer war. |
| `biome` | Biom-ID an der Spielerposition |
| `dimension` | `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` |
| `y` | Höhe |
| `raining` / `thundering` | Regen / Gewitter |
| `lightning` | Blitz-Zähler, steigt bei jedem Blitz, den dein Spiel anzeigt |
| `lightningDist` | Entfernung des letzten Blitzes in Blöcken (`-1` = noch keiner) |

### Original-Texturen
Alle Themes benutzen die Grafiken aus deinem Minecraft: die animierte Portal-Textur, Wasser,
Blasen, Kabeljau, Lachs, Tropenfisch und Kugelfisch, den Pulverschnee-Frostrand mit Schneeflocken
die Kirschblätter-Partikel und die Schadens-Herzen. Der Mod liefert sie unter
`http://127.0.0.1:7475/texture/<name>.png` (nur diese feste Liste, auch aus deinem Ressourcenpaket).
Im Repo liegen keine Mojang-Dateien. Läuft Minecraft nicht (z. B. beim Testen mit `?demo`), nimmt
das Overlay selbst gezeichnete Pixel-Ersatzgrafiken.

Der Portal-Effekt verhält sich wie im Spiel: Er steigt im Portal in 4 s auf volle Stärke und klingt
draußen in 1 s ab. Wie stark er höchstens wird, stellst du oben in `cam-themes.html` mit
`PORTAL_MAX` ein (1.0 = wie im Spiel).

## Einstellungen

`config/damagealert.properties` im Profil-Ordner, wird beim ersten Start angelegt:
```properties
port=7475
```
Der Port muss zu `STATE_URL` oben in `cam-themes.html` passen. Effekt-Einstellungen (Stärke,
Dauer, Überblenden) stehen ebenfalls oben in `cam-themes.html`.

> Hattest du eine ältere Version dieses Mods installiert, liegt dort evtl. noch eine alte
> `damagealert.properties` (mit `state.port=…`, Streamer.bot-Einträgen usw.). Die kannst du
> löschen; der Mod legt dann eine neue mit `port=7475` an. Solange du den Port nicht ändern
> willst, funktioniert es aber auch mit der alten Datei (dann gilt der Standard 7475).

## Selbst bauen

Voraussetzung: **JDK 21**. Unter Windows im Projektordner:
```bat
gradlew.bat build        :: baut die Mod und aktualisiert den Ordner download/
gradlew.bat runClient    :: startet Minecraft mit dem Mod zum Ausprobieren
```

```
src/client/java/de/puffa/damagealert/
  DamageAlertClient.java   liest jeden Tick den Zustand, baut das JSON
  StateServer.java         Mini-HTTP-Server, nur an 127.0.0.1
src/main/resources/fabric.mod.json
cam-themes.html            OBS-Overlay
download/                  fertige Dateien (von "build" befüllt)
```

**Versionen:** Minecraft 1.21.11 · Fabric Loader 0.19.5 · Fabric API 0.141.6+1.21.11 ·
Yarn 1.21.11+build.6 · Fabric Loom 1.17.21 (Loom 1.18 bräuchte Java 25 zum Bauen) · Gradle 9.7.1
