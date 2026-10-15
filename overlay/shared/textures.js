/*
 * Selbst erzeugte Pixel-Texturen und Pixel-Icons (keine Mojang-Grafiken).
 * Jede Textur ist 16x16 Pixel Zufallsrauschen aus einer kleinen Farbpalette,
 * per CSS hochskaliert mit image-rendering: pixelated.
 * Stellt bereit: window.MC.tex.*, window.MC.icon(name, size) und CSS-Variablen --tex-*.
 */
(function () {
  "use strict";
  const MC = (window.MC = window.MC || {});

  // Kleiner deterministischer Zufallsgenerator, damit die Texturen immer gleich aussehen
  function rng(seed) {
    let s = seed >>> 0;
    return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  }

  function canvas(w, h) {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    return c;
  }

  /** Malt ein 16x16-Feld mit gewichteten Zufallsfarben. */
  function noise(ctx, x0, y0, w, h, palette, seed) {
    const r = rng(seed);
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        ctx.fillStyle = palette[Math.floor(Math.pow(r(), 1.3) * palette.length)];
        ctx.fillRect(x0 + x, y0 + y, 1, 1);
      }
    }
  }

  const P = {
    dirt: ["#8b6644", "#7a5939", "#94704d", "#6b4c31", "#a27b55", "#5c412a"],
    grass: ["#6aa83b", "#5c9a33", "#79b847", "#4f8a2c", "#88c652"],
    stone: ["#8a8a8a", "#7c7c7c", "#949494", "#6f6f6f", "#9e9e9e", "#646464"],
    planks: ["#a5834f", "#9a7846", "#b08d58", "#8f6f40"],
    obsidian: ["#140f1f", "#0f0b17", "#1d1530", "#2b2045", "#3a2c5c"],
    leaves: ["#3f7d23", "#4a8b2a", "#356b1d", "#58a032", "#2c5a18"],
    log: ["#6b5232", "#5c462a", "#76603b", "#4e3b22"],
    netherrack: ["#6b2a2a", "#5c2222", "#7a3232", "#4f1b1b", "#873b3b"],
    sand: ["#dbd3a0", "#d1c891", "#e3dcae", "#c9bf85"],
    water: ["#3a5fd9", "#3456c9", "#4169e1", "#2f4fb8"],
  };

  function texture(draw) {
    const c = canvas(16, 16);
    draw(c.getContext("2d"));
    return c;
  }

  const tex = {
    dirt: texture(ctx => noise(ctx, 0, 0, 16, 16, P.dirt, 11)),
    stone: texture(ctx => noise(ctx, 0, 0, 16, 16, P.stone, 23)),
    obsidian: texture(ctx => noise(ctx, 0, 0, 16, 16, P.obsidian, 31)),
    netherrack: texture(ctx => noise(ctx, 0, 0, 16, 16, P.netherrack, 37)),
    sand: texture(ctx => noise(ctx, 0, 0, 16, 16, P.sand, 41)),
    leaves: texture(ctx => {
      noise(ctx, 0, 0, 16, 16, P.leaves, 43);
      const r = rng(44);
      for (let i = 0; i < 18; i++) { ctx.clearRect(Math.floor(r() * 16), Math.floor(r() * 16), 1, 1); }
    }),
    log: texture(ctx => {
      noise(ctx, 0, 0, 16, 16, P.log, 47);
      ctx.fillStyle = "#3f2f1a";
      for (let x = 1; x < 16; x += 4) { ctx.fillRect(x, 0, 1, 16); }
    }),
    planks: texture(ctx => {
      noise(ctx, 0, 0, 16, 16, P.planks, 53);
      ctx.fillStyle = "#6e5430";
      for (let y = 3; y < 16; y += 4) { ctx.fillRect(0, y, 16, 1); }
      ctx.fillRect(7, 0, 1, 3); ctx.fillRect(13, 4, 1, 3); ctx.fillRect(3, 8, 1, 3); ctx.fillRect(10, 12, 1, 3);
    }),
    grassTop: texture(ctx => noise(ctx, 0, 0, 16, 16, P.grass, 59)),
    grassSide: texture(ctx => {
      noise(ctx, 0, 0, 16, 16, P.dirt, 61);
      noise(ctx, 0, 0, 16, 3, P.grass, 62);
      const r = rng(63);
      ctx.fillStyle = P.grass[1];
      for (let x = 0; x < 16; x++) {          // ausgefranste Graskante
        const d = Math.floor(r() * 3);
        ctx.fillRect(x, 3, 1, d);
      }
    }),
    water: texture(ctx => noise(ctx, 0, 0, 16, 16, P.water, 67)),
  };
  MC.tex = tex;

  // CSS-Variablen, damit Stylesheets die Texturen benutzen koennen
  const root = document.documentElement.style;
  for (const [name, c] of Object.entries(tex)) {
    root.setProperty("--tex-" + name.replace(/[A-Z]/g, m => "-" + m.toLowerCase()), `url(${c.toDataURL()})`);
  }

  // ---------------------------------------------------------------- Pixel-Icons
  // Jedes Zeichen ist ein Pixel: "." = durchsichtig, Buchstaben = Farbe aus der Palette
  const ICONS = {
    heart: {
      p: { K: "#000", R: "#e01b1b", D: "#a00d0d", W: "#ffb3b3" },
      rows: [
        ".KK...KK.",
        "KRRK.KRRK",
        "KRWRKRRDK",
        "KRRRRRRDK",
        ".KRRRRDK.",
        "..KRRDK..",
        "...KDK...",
        "....K....",
      ],
    },
    skull: {
      p: { K: "#1b1b1b", W: "#e8e8e0", G: "#b9b9ad" },
      rows: [
        "..KKKKK..",
        ".KWWWWWK.",
        "KWWWWWWGK",
        "KWKKWKKGK",
        "KWKKWKKGK",
        "KWWWKWWGK",
        ".KWWWWGK.",
        "..KWKGK..",
        "..KKKKK..",
      ],
    },
    clock: {
      p: { K: "#7a5a00", Y: "#e8b923", W: "#fffbe8", H: "#2b2b2b" },
      rows: [
        "..KKKKK..",
        ".KYYYYYK.",
        "KYWWHWWYK",
        "KYWWHWWYK",
        "KYWWHHHYK",
        "KYWWWWWYK",
        "KYWWWWWYK",
        ".KYYYYYK.",
        "..KKKKK..",
      ],
    },
    chat: {
      p: { K: "#000", W: "#fff", G: "#c6c6c6" },
      rows: [
        "KKKKKKKKK",
        "KWWWWWWWK",
        "KWGGGGGWK",
        "KWWWWWWWK",
        "KWGGGGWWK",
        "KWWWWWWWK",
        "KKKWKKKKK",
        "..KWK....",
        "..KK.....",
      ],
    },
    camera: {
      p: { K: "#000", G: "#555", L: "#888", B: "#5fa8ff", W: "#fff" },
      rows: [
        "..KKK....",
        "KKKKKKKKK",
        "KGGGGGGLK",
        "KGGKKKGLK",
        "KGKBWBKLK",
        "KGKBBBKLK",
        "KGGKKKGLK",
        "KKKKKKKKK",
      ],
    },
    portal: {
      p: { K: "#140f1f", P: "#8b2be2", L: "#c78bff", D: "#5a1a9a" },
      rows: [
        "KKKKKKK",
        "KPLPDPK",
        "KDPLPDK",
        "KPDPLPK",
        "KLPDPDK",
        "KPLPDPK",
        "KDPLPLK",
        "KKKKKKK",
      ],
    },
    compass: {
      p: { K: "#2b2b2b", G: "#9a9a9a", L: "#cfcfcf", R: "#d42020", W: "#f0f0f0" },
      rows: [
        "..KKKKK..",
        ".KGLLLGK.",
        "KGLLRLLGK",
        "KLLLRLLLK",
        "KLLLKLLLK",
        "KLLLWLLLK",
        "KGLLWLLGK",
        ".KGLLLGK.",
        "..KKKKK..",
      ],
    },
    globe: {
      p: { K: "#0b2a52", B: "#3a7bd5", G: "#5fbf3a", L: "#7fd655" },
      rows: [
        "..KKKKK..",
        ".KBGGBBK.",
        "KBGLGBBBK",
        "KBBGGBGBK",
        "KBBBBGLGK",
        "KGBBBBGBK",
        "KLGBBBBBK",
        ".KGGBBBK.",
        "..KKKKK..",
      ],
    },
    grass: {
      p: { K: "#000", G: "#6aa83b", L: "#88c652", D: "#8b6644", E: "#6b4c31" },
      rows: [
        "KKKKKKKKK",
        "KLGLGGLGK",
        "KGGDGGGLK",
        "KDGDDGDGK",
        "KDDEDDDEK",
        "KDEDDEDDK",
        "KDDDEDDDK",
        "KEDDDDEDK",
        "KKKKKKKKK",
      ],
    },
  };

  const iconCache = {};
  /** Liefert ein Icon als Data-URL (fuer background-image oder <img src>). */
  MC.icon = function (name) {
    if (iconCache[name]) return iconCache[name];
    const def = ICONS[name];
    const w = def.rows[0].length, h = def.rows.length;
    const c = canvas(w, h);
    const ctx = c.getContext("2d");
    def.rows.forEach((row, y) => [...row].forEach((ch, x) => {
      if (ch !== ".") { ctx.fillStyle = def.p[ch]; ctx.fillRect(x, y, 1, 1); }
    }));
    return (iconCache[name] = c.toDataURL());
  };

  /** Setzt in allen Elementen mit data-icon="name" das passende Icon. */
  MC.applyIcons = function (rootEl) {
    for (const el of (rootEl || document).querySelectorAll("[data-icon]")) {
      el.classList.add("icon");
      el.style.backgroundImage = `url(${MC.icon(el.dataset.icon)})`;
    }
  };
})();
