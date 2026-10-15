/*
 * Effekt-Ebene: reagiert auf Zustaende und Ereignisse vom Mod (siehe damagealert.js).
 *   MC.effects.mount(element, { toasts: "left" | "right" | false, toastTop: 40, damageText: true })
 */
(function () {
  "use strict";
  const MC = (window.MC = window.MC || {});

  function el(tag, cls, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html !== undefined) e.innerHTML = html;
    return e;
  }

  /** Frostrand: Eiskristalle aus groben Pixeln, dicht am Rand, zur Mitte hin auslaufend (240x135, x8). */
  function frostCanvas() {
    const W = 240, H = 135;
    const c = document.createElement("canvas");
    c.width = W; c.height = H;
    const ctx = c.getContext("2d");
    let s = 12345;
    const r = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
    const colors = ["rgba(255,255,255,.92)", "rgba(230,246,255,.85)", "rgba(190,228,255,.8)", "rgba(150,205,250,.65)"];
    // Abstand zum Rand (0 = Rand), oben/unten etwas schmaler
    const edge = (x, y) => Math.min(x / W, (W - 1 - x) / W, (y / H) * 0.62, ((H - 1 - y) / H) * 0.62);
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const p = Math.pow(Math.max(0, 1 - edge(x, y) / 0.075), 1.6);
        if (r() < p) {
          ctx.fillStyle = colors[Math.floor(r() * colors.length)];
          ctx.fillRect(x, y, 1, 1);
        }
      }
    }
    // Ein paar Eiskristall-Aeste, die vom Rand ins Bild wachsen
    ctx.fillStyle = "rgba(235,248,255,.8)";
    for (let i = 0; i < 70; i++) {
      let x = r() < 0.5 ? (r() < 0.5 ? 0 : W - 1) : Math.floor(r() * W);
      let y = x === 0 || x === W - 1 ? Math.floor(r() * H) : (r() < 0.5 ? 0 : H - 1);
      const dx = x === 0 ? 1 : x === W - 1 ? -1 : 0, dy = y === 0 ? 1 : y === H - 1 ? -1 : 0;
      const len = 4 + Math.floor(r() * 12);
      for (let k = 0; k < len; k++) {
        ctx.fillRect(x, y, 1, 1);
        x += dx || (r() < 0.5 ? -1 : 1) * (r() < 0.4 ? 1 : 0);
        y += dy || (r() < 0.5 ? -1 : 1) * (r() < 0.4 ? 1 : 0);
      }
    }
    return c;
  }

  /** Weiche Lichtflecken fuer Unterwasser (groessere Pixel-Bloecke). */
  function causticsTexture() {
    const c = document.createElement("canvas");
    c.width = 32; c.height = 32;
    const ctx = c.getContext("2d");
    for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
      const v = Math.sin(x * 0.55) * Math.sin(y * 0.45 + x * 0.2) + Math.sin((x + y) * 0.31);
      if (v > 0.9) { ctx.fillStyle = `rgba(190,235,255,${Math.min(0.5, (v - 0.9) * 0.6)})`; ctx.fillRect(x, y, 1, 1); }
    }
    return c.toDataURL();
  }

  /** Lila Pixel-Rauschen fuer den Portal-Wirbel. */
  function portalTexture() {
    const c = document.createElement("canvas");
    c.width = 24; c.height = 24;
    const ctx = c.getContext("2d");
    const pal = ["#2a0a4a", "#5a1a9a", "#8b2be2", "#a64dff", "#c78bff", "#3d0f6b"];
    let s = 777;
    const r = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
    for (let y = 0; y < 24; y++) for (let x = 0; x < 24; x++) {
      const v = (Math.sin(x * 0.53 + y * 0.29) + Math.sin(y * 0.71 - x * 0.37 + 1.3)) * 0.25 + 0.5;
      ctx.fillStyle = pal[Math.min(pal.length - 1, Math.floor((v * 0.45 + r() * 0.55) * pal.length))];
      ctx.fillRect(x, y, 1, 1);
    }
    return c.toDataURL();
  }

  MC.effects = {
    /**
     * @param options.exclude  Bereich {x, y, w, h}, in dem keine Effekte liegen sollen (z. B. die Kamera)
     */
    mount(root, options) {
      const o = Object.assign({ toasts: "left", toastTop: 150, toastSide: 40, damageText: true, exclude: null }, options || {});
      const live = MC.live;
      root.classList.add("fx-root");
      if (o.exclude) {
        // Loch in der Effekt-Ebene, damit z. B. die Webcam immer klar bleibt
        const e = o.exclude;
        const mask = `linear-gradient(#000,#000) ${e.x}px ${e.y}px / ${e.w}px ${e.h}px no-repeat, linear-gradient(#000,#000)`;
        root.style.webkitMask = mask;
        root.style.webkitMaskComposite = "xor";
        root.style.mask = mask;
        root.style.maskComposite = "exclude";
      }
      document.documentElement.style.setProperty("--portal-tex", `url(${portalTexture()})`);
      document.documentElement.style.setProperty("--caustics-tex", `url(${causticsTexture()})`);

      const water = el("div", "fx-layer fx-water", '<div class="caustics"></div><div class="caustics b"></div>');
      const lava = el("div", "fx-layer fx-lava");
      const frostTint = el("div", "fx-layer fx-frost-tint");
      const frost = frostCanvas();
      frost.className = "fx-layer fx-frost";
      const portal = el("div", "fx-layer fx-portal", '<div class="tint"></div><div class="swirl"></div>');
      const flash = el("div", "fx-layer fx-flash");
      const hurt = el("div", "fx-layer fx-hurt");
      const death = el("div", "fx-layer fx-death",
        '<div class="big mc-title">Du bist gestorben!</div>' +
        '<div class="sub mc-text"><span data-icon="skull"></span><span class="txt"></span><span data-icon="skull"></span></div>');
      const particles = el("div", "fx-layer");
      particles.style.opacity = 1;
      root.append(water, lava, frostTint, frost, portal, particles, flash, hurt, death);
      MC.applyIcons(death);

      let toastBox = null;
      if (o.toasts) {
        toastBox = el("div", "fx-toasts " + o.toasts);
        toastBox.style.top = o.toastTop + "px";
        toastBox.style[o.toasts] = o.toastSide + "px";
        root.append(toastBox);
      }

      // ---- Partikel
      function spawn(cls, x, duration, extra) {
        const p = el("div", cls);
        p.style.left = x + "px";
        p.style.animationDuration = duration + "s";
        if (extra) extra(p);
        particles.append(p);
        setTimeout(() => p.remove(), duration * 1000 + 100);
      }
      let state = live.state;
      setInterval(() => {
        if (state.underwater && Math.random() < 0.6) spawn("fx-bubble", Math.random() * 1920, 5 + Math.random() * 4);
        if (state.inLava && Math.random() < 0.8) spawn("fx-ember", Math.random() * 1920, 2.5 + Math.random() * 2);
        if (state.inPortal) {
          for (let i = 0; i < 2; i++) {
            const a = Math.random() * Math.PI * 2, rad = 700 + Math.random() * 300;
            spawn("fx-spark", 960 + Math.cos(a) * rad, 1.6, p => {
              p.style.top = 540 + Math.sin(a) * rad * 0.6 + "px";
              p.style.setProperty("--dx", -Math.cos(a) * rad * 0.7 + "px");
              p.style.setProperty("--dy", -Math.sin(a) * rad * 0.42 + "px");
            });
          }
        }
      }, 120);

      // ---- Blitze bei Gewitter (zufaellig alle 5-12 s)
      (function lightning() {
        if (state.thundering) {
          flash.classList.remove("go"); void flash.offsetWidth; flash.classList.add("go");
        }
        setTimeout(lightning, 5000 + Math.random() * 7000);
      })();

      // ---- Zustaende
      live.on("state", s => {
        state = s;
        water.style.opacity = s.underwater ? 1 : 0;
        lava.style.opacity = s.inLava ? 1 : 0;
        frost.style.opacity = s.freeze;
        frostTint.style.opacity = s.freeze;
        portal.style.opacity = s.inPortal ? 1 : 0;
      });

      // ---- Schaden
      live.on("damage", d => {
        if (d.health <= 0) return;   // Tod hat einen eigenen Effekt
        hurt.classList.remove("go"); void hurt.offsetWidth; hurt.classList.add("go");
        if (o.damageText) {
          const t = el("div", "fx-dmg-text", `−${d.amount % 1 === 0 ? d.amount : d.amount.toFixed(1)} <span data-icon="heart"></span>`);
          t.style.left = 760 + Math.random() * 120 + "px";
          t.style.top = 800 + "px";
          MC.applyIcons(t);
          t.querySelector(".icon").style.cssText += "width:48px;height:42px";
          particles.append(t);
          setTimeout(() => t.remove(), 1500);
        }
      });

      // ---- Tod
      let deathTimer = null;
      live.on("death", s => {
        const n = s.deaths ? s.deaths.session : null;
        death.querySelector(".txt").textContent = n ? `Tod Nr. ${n} in diesem Stream` : "Respawn …";
        death.classList.add("show");
        death.style.opacity = 1;
        clearTimeout(deathTimer);
        deathTimer = setTimeout(() => { death.style.opacity = 0; death.classList.remove("show"); }, 4500);
      });

      // ---- Toasts (Biom / Dimension)
      const queue = [];
      let busy = false;
      function toast(icon, line1, line2) {
        if (!toastBox) return;
        queue.push([icon, line1, line2]);
        if (!busy) nextToast();
      }
      function nextToast() {
        const item = queue.shift();
        if (!item) { busy = false; return; }
        busy = true;
        const t = el("div", "mc-toast",
          `<div class="mc-slot"><span data-icon="${item[0]}" style="width:44px;height:44px"></span></div>` +
          `<div><div class="t1 mc-text">${item[1]}</div><div class="t2 mc-text"></div></div>`);
        t.querySelector(".t2").textContent = item[2];
        MC.applyIcons(t);
        toastBox.append(t);
        requestAnimationFrame(() => requestAnimationFrame(() => t.classList.add("show")));
        setTimeout(() => t.classList.remove("show"), 4200);
        setTimeout(() => { t.remove(); nextToast(); }, 4800);
      }
      const dimIcon = id => (id === "minecraft:the_nether" ? "portal" : id === "minecraft:the_end" ? "globe" : "grass");
      live.on("biome", s => toast(dimIcon(s.dimension), "Neues Biom", s.biomeName || s.biome));
      live.on("dimension", s => toast(dimIcon(s.dimension), "Neue Dimension",
        s.dimension === "minecraft:overworld" ? "Zurück in der Oberwelt" : "Willkommen: " + live.dimensionName(s.dimension)));

      MC.toast = toast;
    },
  };
})();
