/*
 * Verbindung zum Damage-Alert-Mod. Fragt ~10x pro Sekunde http://127.0.0.1:7475/state ab.
 *
 *   MC.live.on("state", s => ...)        jede Abfrage (auch ohne Minecraft: inWorld=false)
 *   MC.live.on("damage", d => ...)       Schaden: d.amount, d.health
 *   MC.live.on("death", s => ...)        Tod (s.deaths.session = neuer Zaehlerstand)
 *   MC.live.on("biome", s => ...)        Biom hat gewechselt (stabil fuer 1,5 s)
 *   MC.live.on("dimension", s => ...)    Dimension hat gewechselt
 *   MC.live.on("connection", online => ...)
 *
 * URL-Parameter fuer jedes Overlay:
 *   ?demo        Demo-Modus: simuliert Spielgeschehen, zum Anschauen ohne Minecraft
 *   ?port=7475   anderer Port (wie state.port in der Mod-Config)
 * Weitere Parameter stehen oben in der jeweiligen Overlay-Datei.
 */
(function () {
  "use strict";
  const MC = (window.MC = window.MC || {});
  const params = new URLSearchParams(location.search);
  const PORT = params.get("port") || "7475";
  const STATE_URL = `http://127.0.0.1:${PORT}/state`;
  const DEMO = params.has("demo");
  const POLL_MS = 100;

  const listeners = {};
  function on(type, fn) { (listeners[type] = listeners[type] || []).push(fn); }
  function emit(type, arg) {
    for (const fn of listeners[type] || []) {
      try { fn(arg); } catch (e) { console.error(e); }
    }
  }

  // Letzter bekannter Todeszaehler, damit z. B. die Ende-Szene ihn auch nach dem Schliessen von Minecraft zeigt
  function cachedDeaths() {
    try { return JSON.parse(localStorage.getItem("damagealert.deaths")) || null; } catch (e) { return null; }
  }
  function storeDeaths(d) {
    try { localStorage.setItem("damagealert.deaths", JSON.stringify(d)); } catch (e) { /* egal */ }
  }

  function offlineState() {
    return {
      inWorld: false, underwater: false, inLava: false, freeze: 0, biome: null, biomeName: null,
      dimension: null, y: null, raining: false, thundering: false, inPortal: false, health: null, maxHealth: null,
      deaths: cachedDeaths(), events: null, offline: true,
    };
  }

  let seen = null;          // zuletzt gesehene Ereignis-Nummern
  let online = null;
  let biome = { current: null, since: 0, announced: null };
  let lastDimension = null;
  let last = offlineState();

  function handle(s) {
    const ev = s.events;
    if (ev) {
      if (seen && seen.epoch === ev.epoch) {
        if (ev.damage.seq > seen.damage) emit("damage", ev.damage);
        if (ev.death.seq > seen.death) emit("death", s);
      }
      seen = { epoch: ev.epoch, damage: ev.damage.seq, death: ev.death.seq };
    }
    if (s.deaths) storeDeaths(s.deaths);

    // Biom-Wechsel erst melden, wenn das neue Biom 1,5 s stabil ist (sonst flackert es an Grenzen)
    const now = performance.now();
    const key = s.inWorld ? s.biome : null;
    if (key !== biome.current) { biome.current = key; biome.since = now; }
    if (key && key !== biome.announced && now - biome.since > 1500) {
      const first = biome.announced === null;
      biome.announced = key;
      if (!first) emit("biome", s);
    }
    if (!s.inWorld) biome.announced = null;

    if (s.inWorld && s.dimension && s.dimension !== lastDimension) {
      if (lastDimension) emit("dimension", s);
      lastDimension = s.dimension;
    }
    if (!s.inWorld) lastDimension = null;

    last = s;
    emit("state", s);
  }

  function setOnline(v) {
    if (v !== online) { online = v; emit("connection", v); }
  }

  async function poll() {
    try {
      const r = await fetch(STATE_URL, { cache: "no-store" });
      const s = await r.json();
      setOnline(true);
      handle(s);
    } catch (e) {
      setOnline(false);
      handle(offlineState());
    }
    setTimeout(poll, POLL_MS);
  }

  // ---------------------------------------------------------------- Demo-Modus
  const DEMO_BIOMES = [
    ["minecraft:plains", "Ebene"], ["minecraft:cherry_grove", "Kirschberghain"], ["minecraft:forest", "Wald"],
    ["minecraft:desert", "Wüste"], ["minecraft:snowy_plains", "Verschneite Ebene"],
  ];
  function startDemo() {
    const t0 = performance.now();
    const deaths = { total: 41, session: 3 };
    const events = { epoch: 1, damage: { seq: 0, amount: 0, health: 20 }, death: { seq: 0 } };
    let health = 20, lastSec = -1;
    setOnline(true);
    setInterval(() => {
      const t = (performance.now() - t0) / 1000;
      const c = t % 32;
      const sec = Math.floor(t);
      if (sec !== lastSec) {
        lastSec = sec;
        const cs = sec % 32;
        if ([3, 8, 13, 24].includes(cs)) {
          const amount = [2, 4, 1.5, 6][[3, 8, 13, 24].indexOf(cs)];
          health = Math.max(1, health - amount);
          events.damage = { seq: events.damage.seq + 1, amount, health };
        }
        if (cs === 29) { deaths.total++; deaths.session++; events.death = { seq: events.death.seq + 1 }; health = 20; }
      }
      const nether = c >= 20 && c < 28;
      const b = nether ? ["minecraft:nether_wastes", "Nether-Ödland"] : DEMO_BIOMES[Math.floor(t / 8) % DEMO_BIOMES.length];
      handle({
        inWorld: true,
        underwater: c >= 1 && c < 6,
        inLava: c >= 7 && c < 10,
        freeze: c >= 10 && c < 16 ? Math.min(1, (c - 10) / 4) : 0,
        biome: b[0], biomeName: b[1],
        dimension: nether ? "minecraft:the_nether" : "minecraft:overworld",
        y: Math.round(64 + 10 * Math.sin(t / 5)),
        raining: c >= 28 || c < 1, thundering: c >= 29,
        inPortal: c >= 17 && c < 20,
        health, maxHealth: 20,
        deaths: { ...deaths }, events: JSON.parse(JSON.stringify(events)),
      });
    }, POLL_MS);
  }

  /** Einstellung aus der URL lesen (?name=...), sonst Standardwert. */
  MC.param = function (name, fallback) {
    const v = params.get(name);
    if (v === null) return fallback;
    if (typeof fallback === "number") { const n = parseFloat(v); return isNaN(n) ? fallback : n; }
    if (typeof fallback === "boolean") return v !== "0" && v !== "false" && v !== "nein";
    return v;
  };

  MC.live = {
    on,
    get state() { return last; },
    demo: DEMO,
    /** Name einer Dimension auf Deutsch. */
    dimensionName(id) {
      return ({ "minecraft:overworld": "Oberwelt", "minecraft:the_nether": "Nether", "minecraft:the_end": "Das Ende" })[id]
        || (id ? id.replace(/^.*:/, "").replace(/_/g, " ") : "–");
    },
  };

  // Start, sobald die Seite (und alle on(...)-Registrierungen) geladen ist
  window.addEventListener("DOMContentLoaded", () => (DEMO ? startDemo() : poll()));
})();
