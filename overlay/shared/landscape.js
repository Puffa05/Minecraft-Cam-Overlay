/*
 * Endlos scrollende Minecraft-artige Landschaft (Tag oder Nacht) auf einem <canvas>.
 *   MC.landscape(canvasElement, { night: false, speed: 30, block: 64, horizon: 0.62 })
 * Alles wird aus den selbst erzeugten Texturen in textures.js gezeichnet.
 */
(function () {
  "use strict";
  const MC = (window.MC = window.MC || {});

  function hash(i) {
    let x = (i * 374761393) ^ 0x5bf03635;
    x = Math.imul(x ^ (x >>> 13), 1274126177);
    return ((x ^ (x >>> 16)) >>> 0) / 4294967296;
  }

  MC.landscape = function (canvas, opts) {
    const o = Object.assign({ night: false, speed: 30, block: 64, horizon: 0.62, seed: 7 }, opts || {});
    const ctx = canvas.getContext("2d");
    const W = (canvas.width = canvas.clientWidth || 1920);
    const H = (canvas.height = canvas.clientHeight || 1080);
    const B = o.block;
    const t = MC.tex;
    ctx.imageSmoothingEnabled = false;

    // Hoehe einer Spalte (in Bloecken ueber dem unteren Rand), glatt und endlos
    function height(i) {
      const s = o.seed;
      const base = Math.round((H * (1 - o.horizon)) / B);
      return Math.max(2, Math.round(base + 1.6 * Math.sin(i * 0.19 + s) + 1.1 * Math.sin(i * 0.071 + s * 2) + 0.7 * Math.sin(i * 0.43 + s * 3)));
    }
    function hasTree(i) {
      return hash(i + o.seed * 1000) < 0.12 && Math.abs(height(i) - height(i - 1)) < 1 && Math.abs(height(i) - height(i + 1)) < 1
        && !(hash(i - 1 + o.seed * 1000) < 0.12) && !(hash(i - 2 + o.seed * 1000) < 0.12);
    }
    function hasFlower(i) { return !hasTree(i) && hash(i * 7 + 3) < 0.18; }

    // Sterne fuer die Nacht
    const stars = [];
    for (let i = 0; i < 140; i++) stars.push({ x: hash(i * 3 + 1) * W, y: hash(i * 3 + 2) * H * 0.55, p: hash(i * 3 + 3) * 6.28 });

    // Wolken: Gruppen aus Rechtecken
    const clouds = [];
    for (let i = 0; i < 9; i++) {
      const cells = [];
      const w = 3 + Math.floor(hash(i * 11) * 5);
      for (let x = 0; x < w; x++) for (let y = 0; y < 2; y++) if (y === 0 || hash(i * 31 + x) > 0.35) cells.push([x, y]);
      clouds.push({ x: hash(i * 5) * W * 1.6, y: 70 + hash(i * 13) * 220, cells, s: 0.25 + hash(i * 17) * 0.2 });
    }

    function drawSky(time) {
      const g = ctx.createLinearGradient(0, 0, 0, H);
      if (o.night) { g.addColorStop(0, "#050816"); g.addColorStop(0.6, "#13204a"); g.addColorStop(1, "#24366e"); }
      else { g.addColorStop(0, "#6f9ff5"); g.addColorStop(0.65, "#a9c8ff"); g.addColorStop(1, "#d6e6ff"); }
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
      if (o.night) {
        for (const s of stars) {
          ctx.globalAlpha = 0.5 + 0.5 * Math.sin(time * 1.5 + s.p);
          ctx.fillStyle = "#fff";
          ctx.fillRect(Math.round(s.x / 4) * 4, Math.round(s.y / 4) * 4, 4, 4);
        }
        ctx.globalAlpha = 1;
        // Mond
        ctx.fillStyle = "#d9dde8"; ctx.fillRect(W * 0.78, 110, 112, 112);
        ctx.fillStyle = "#b9bfcc"; ctx.fillRect(W * 0.78 + 20, 130, 24, 24); ctx.fillRect(W * 0.78 + 64, 170, 28, 20);
      } else {
        // Sonne
        ctx.fillStyle = "rgba(255,250,200,.35)"; ctx.fillRect(W * 0.78 - 24, 86, 176, 176);
        ctx.fillStyle = "#fff7a1"; ctx.fillRect(W * 0.78, 110, 128, 128);
        ctx.fillStyle = "#fffbd9"; ctx.fillRect(W * 0.78 + 24, 134, 80, 80);
      }
    }

    function drawClouds(dt) {
      ctx.fillStyle = o.night ? "rgba(150,165,210,.16)" : "rgba(255,255,255,.85)";
      for (const c of clouds) {
        c.x -= o.speed * c.s * dt;
        if (c.x < -600) c.x += W + 900;
        for (const [x, y] of c.cells) ctx.fillRect(Math.round(c.x + x * 64), Math.round(c.y + y * 32), 64, 32);
      }
    }

    function block(img, x, y) {
      ctx.drawImage(img, x, y, B, B);
      if (o.night) { ctx.fillStyle = "rgba(10,15,45,.55)"; ctx.fillRect(x, y, B, B); }   // nachts abdunkeln
    }

    function drawTerrain(offset) {
      const first = Math.floor(offset / B) - 3;
      const cols = Math.ceil(W / B) + 6;
      for (let k = 0; k < cols; k++) {
        const i = first + k;
        const x = Math.round(i * B - offset);
        const h = height(i);
        for (let j = 0; j < h; j++) {
          const y = H - (j + 1) * B;
          const depth = h - 1 - j;
          block(depth === 0 ? t.grassSide : depth < 4 ? t.dirt : t.stone, x, y);
        }
      }
      // Baeume und Blumen
      for (let k = 0; k < cols; k++) {
        const i = first + k;
        const x = Math.round(i * B - offset);
        const ground = H - height(i) * B;
        if (hasTree(i)) {
          for (let j = 1; j <= 4; j++) block(t.log, x, ground - j * B);
          const top = ground - 4 * B;
          for (let dx = -2; dx <= 2; dx++) for (let dy = 0; dy <= 1; dy++) block(t.leaves, x + dx * B, top - dy * B);
          for (let dx = -1; dx <= 1; dx++) block(t.leaves, x + dx * B, top - 2 * B);
          block(t.leaves, x, top - 3 * B);
        } else if (hasFlower(i)) {
          const fx = x + B / 2 - 8, fy = ground - 28;
          ctx.fillStyle = "#3d7a1f"; ctx.fillRect(fx + 6, fy + 8, 4, 20);
          ctx.fillStyle = hash(i) < 0.5 ? "#e8322b" : "#f2d22e"; ctx.fillRect(fx, fy, 16, 12);
          ctx.fillStyle = "#3a2a00"; ctx.fillRect(fx + 6, fy + 4, 4, 4);
        }
      }
    }

    let offset = 0, last = performance.now();
    function frame(now) {
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      offset += o.speed * dt;
      drawSky(now / 1000);
      drawClouds(dt);
      drawTerrain(offset);
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  };
})();
