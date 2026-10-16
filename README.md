# Minecraft-Cam-Overlay

Wenn in Minecraft etwas passiert (Schaden, Portal, Unterwasser, Frieren, Kirschblüten-Biom),
verändert sich deine Webcam im Stream passend: z. B. ein kurzer roter Rahmen bei Schaden oder
Fische über der Cam unter Wasser.

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
  oder gezielt `?theme=portal`, `?theme=underwater`, `?theme=freeze`, `?theme=cherry`.

## Was der Mod liefert

`GET http://127.0.0.1:7475/state`, z. B.:
```json
{"inWorld":true,"inPortal":false,"underwater":true,"inLava":false,"onFire":false,
 "freeze":0.000,"health":16.0,"maxHealth":20.0,"hits":3,
 "biome":"minecraft:cherry_grove","dimension":"minecraft:overworld","y":64.0,
 "raining":false,"thundering":false}
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
| `biome` | Biom-ID an der Spielerposition |
| `dimension` | `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` |
| `y` | Höhe |
| `raining` / `thundering` | Regen / Gewitter |

### Original-Texturen
Portal und Unterwasser benutzen die Grafiken aus deinem Minecraft: die animierte Portal-Textur,
Wasser, Blasen sowie Kabeljau, Lachs, Tropenfisch und Kugelfisch. Der Mod liefert sie unter
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
