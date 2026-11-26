// Shared Hydrodynamic Velocity Palette, Typography, Easing & Marlin Geometry

export const PALETTES = {
  dark: {
    name: 'dark',
    isDark: true,
    bg: '#090E0C',
    bgAlt: '#0D1117',
    surface: '#0F1915',
    surfaceElev: '#14221C',
    text: '#ECF7F2',
    textMuted: '#94B3A6',
    textDim: '#5B7A6D',
    primary: '#55C99D',
    glow: '#80DBAD',
    deep: '#187052',
    specular: '#C1F0D6',
    cyan: '#38BDF8',
    amber: '#F59E0B',
    crimson: '#F87171',
    hairline: 'rgba(128, 219, 173, 0.16)',
    hairlineStrong: 'rgba(128, 219, 173, 0.34)',
    grid: 'rgba(128, 219, 173, 0.055)',
    cardShadow: 'rgba(0, 0, 0, 0.55)',
  },
  light: {
    name: 'light',
    isDark: false,
    bg: '#F5F9F7',
    bgAlt: '#FFFFFF',
    surface: '#FFFFFF',
    surfaceElev: '#EAF3EF',
    text: '#091511',
    textMuted: '#2B4239',
    textDim: '#5A756A',
    primary: '#136F50',
    glow: '#0EA472',
    deep: '#0D5C43',
    specular: '#5AE0AC',
    cyan: '#0284C7',
    amber: '#D97706',
    crimson: '#DC2626',
    hairline: 'rgba(19, 111, 80, 0.15)',
    hairlineStrong: 'rgba(19, 111, 80, 0.36)',
    grid: 'rgba(19, 111, 80, 0.06)',
    cardShadow: 'rgba(9, 21, 17, 0.14)',
  },
};

export function injectBrandFonts() {
  const style = document.createElement('style');
  style.textContent = `
    @font-face {
      font-family: 'Space Grotesk';
      src: url('/repo/website/vendor/fonts/SpaceGrotesk-var.woff2') format('woff2');
      font-weight: 300 800;
      font-display: swap;
    }
    @font-face {
      font-family: 'IBM Plex Sans';
      src: url('/repo/website/vendor/fonts/IBMPlexSans-Regular.woff2') format('woff2');
      font-weight: 400;
      font-display: swap;
    }
    @font-face {
      font-family: 'IBM Plex Sans';
      src: url('/repo/website/vendor/fonts/IBMPlexSans-Medium.woff2') format('woff2');
      font-weight: 500;
      font-display: swap;
    }
    @font-face {
      font-family: 'IBM Plex Sans';
      src: url('/repo/website/vendor/fonts/IBMPlexSans-SemiBold.woff2') format('woff2');
      font-weight: 600;
      font-display: swap;
    }
    @font-face {
      font-family: 'IBM Plex Sans';
      src: url('/repo/website/vendor/fonts/IBMPlexSans-Bold.woff2') format('woff2');
      font-weight: 700;
      font-display: swap;
    }
    @font-face {
      font-family: 'IBM Plex Mono';
      src: url('/repo/website/vendor/fonts/IBMPlexMono-Regular.woff2') format('woff2');
      font-weight: 400;
      font-display: swap;
    }
    @font-face {
      font-family: 'IBM Plex Mono';
      src: url('/repo/website/vendor/fonts/IBMPlexMono-Medium.woff2') format('woff2');
      font-weight: 500;
      font-display: swap;
    }
  `;
  document.head.appendChild(style);
}

// Easing & timing math
export const clamp01 = (x) => Math.max(0, Math.min(1, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const remap = (t, t0, t1) => clamp01((t - t0) / (t1 - t0));
export const smoothstep = (e0, e1, x) => {
  const t = clamp01((x - e0) / (e1 - e0));
  return t * t * (3 - 2 * t);
};
export const easeOutExpo = (x) => (x >= 1 ? 1 : 1 - Math.pow(2, -10 * clamp01(x)));
export const easeInExpo = (x) => (x <= 0 ? 0 : Math.pow(2, 10 * clamp01(x) - 10));
export const easeInOutCubic = (x) => {
  const t = clamp01(x);
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
};
export const easeOutCubic = (x) => 1 - Math.pow(1 - clamp01(x), 3);
export const easeInOutSine = (x) => -(Math.cos(Math.PI * clamp01(x)) - 1) / 2;
export const easeOutBack = (x) => {
  const t = clamp01(x);
  const c1 = 1.70158;
  const c3 = c1 + 1;
  return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
};

// Exact canonical Black Marlin SVG paths (480x480 viewBox from art/BMD Black Marlin.svg)
export const MARLIN_PATHS = {
  dorsal: 'M125 118C177 88 255 83 312 111C238 158 155 217 128 293C120 252 139 209 166 173C191 139 177 115 125 118Z',
  body: 'M437 15C387 67 362 96 344 125C357 113 371 105 386 99C359 124 339 151 330 180C346 208 354 241 346 274C344 256 339 240 330 229C327 298 278 360 205 387C191 393 176 397 158 399L130 356C167 344 213 354 239 308C263 266 244 219 204 211C169 204 140 235 128 293C119 240 167 179 221 150C252 133 273 119 309 109C352 98 394 48 437 15Z',
  belly: 'M223 179C261 148 306 162 326 191C344 215 351 244 346 274C343 248 332 229 311 215C287 198 252 184 223 179Z',
  tail: 'M49 295C103 296 136 323 176 341C205 354 229 351 255 337C232 360 194 374 165 394C145 414 132 438 124 462C122 423 148 395 133 367C114 337 81 306 49 295Z',
};

export function drawMarlinSculptural(ctx, x, y, scale, opts = {}) {
  const {
    isDark = true,
    fillAlpha = 1,
    strokeAlpha = 1,
    rimProgress = 0,
    rotationRad = 0,
    facetSweep = 0,
  } = opts;

  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(rotationRad);
  ctx.scale(scale, scale);
  ctx.translate(-240, -240);

  const dorsalPath = new Path2D(MARLIN_PATHS.dorsal);
  const bodyPath = new Path2D(MARLIN_PATHS.body);
  const bellyPath = new Path2D(MARLIN_PATHS.belly);
  const tailPath = new Path2D(MARLIN_PATHS.tail);

  if (fillAlpha > 0.005) {
    ctx.save();
    ctx.globalAlpha = fillAlpha;

    // Dorsal hydrodynamic sail gradient
    const gDorsal = ctx.createLinearGradient(140, 95, 290, 260);
    if (isDark) {
      gDorsal.addColorStop(0, '#9BE9C3');
      gDorsal.addColorStop(0.35, '#55C99D');
      gDorsal.addColorStop(1, '#125A41');
    } else {
      gDorsal.addColorStop(0, '#369D77');
      gDorsal.addColorStop(0.45, '#187052');
      gDorsal.addColorStop(1, '#0A3627');
    }
    ctx.fillStyle = gDorsal;
    ctx.fill(dorsalPath);

    // Sculpted primary fuselage with specular sheen sweep
    const gBody = ctx.createLinearGradient(110, 390, 437, 15);
    if (isDark) {
      gBody.addColorStop(0, '#1C7858');
      gBody.addColorStop(clamp01(0.45 + 0.25 * Math.sin(facetSweep * Math.PI * 2)), '#7CE3B8');
      gBody.addColorStop(1, '#D8FAEA');
    } else {
      gBody.addColorStop(0, '#0B3C2B');
      gBody.addColorStop(clamp01(0.45 + 0.25 * Math.sin(facetSweep * Math.PI * 2)), '#187052');
      gBody.addColorStop(1, '#55C99D');
    }
    ctx.fillStyle = gBody;
    ctx.fill(bodyPath);

    // Faceted belly contour
    ctx.fillStyle = isDark ? 'rgba(236, 247, 242, 0.85)' : 'rgba(225, 245, 236, 0.92)';
    ctx.fill(bellyPath);

    // Crescent caudal tail
    const gTail = ctx.createLinearGradient(55, 310, 210, 445);
    if (isDark) {
      gTail.addColorStop(0, '#7CE3B8');
      gTail.addColorStop(0.6, '#359A75');
      gTail.addColorStop(1, '#124D38');
    } else {
      gTail.addColorStop(0, '#278A66');
      gTail.addColorStop(1, '#0A3627');
    }
    ctx.fillStyle = gTail;
    ctx.fill(tailPath);

    ctx.restore();
  }

  // Hydrodynamic vector rim wireframe / flow outline
  if (strokeAlpha > 0.005) {
    ctx.save();
    ctx.globalAlpha = strokeAlpha;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.lineWidth = 4.5;
    ctx.strokeStyle = isDark ? '#C1F0D6' : '#0D5C43';
    if (rimProgress > 0 && rimProgress < 1) {
      ctx.setLineDash([1900 * rimProgress, 1900]);
    }
    ctx.stroke(bodyPath);
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = isDark ? 'rgba(128, 219, 173, 0.7)' : 'rgba(19, 111, 80, 0.65)';
    ctx.stroke(dorsalPath);
    ctx.stroke(tailPath);
    ctx.restore();
  }

  ctx.restore();
}

export function drawRoundedRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}
