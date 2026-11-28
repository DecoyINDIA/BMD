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

// Load the approved SVG directly so regenerated scenes cannot restore an obsolete mark.
const logoResponse = await fetch('/repo/art/BMD%20Black%20Marlin%20Icon.svg');
if (!logoResponse.ok) throw new Error(`Could not load BMD logo: ${logoResponse.status}`);
const logoSvg = new DOMParser().parseFromString(await logoResponse.text(), 'image/svg+xml');
const logoTransform = logoSvg.querySelector('g').transform.baseVal.consolidate()?.matrix;
if (!logoTransform) throw new Error('Expected a transform on the approved BMD mark');
const logoPaths = [...logoSvg.querySelectorAll('g path')].map((path) => ({
  path: new Path2D(path.getAttribute('d')),
  color: path.getAttribute('fill'),
}));
if (logoPaths.length !== 3) throw new Error('Expected three paths in the approved BMD mark');

export function drawMarlinSculptural(ctx, x, y, scale, opts = {}) {
  ctx.save();
  ctx.translate(x, y);
  ctx.scale(scale * 480 / 1024, scale * 480 / 1024);
  ctx.translate(-512, -512);
  ctx.transform(logoTransform.a, logoTransform.b, logoTransform.c, logoTransform.d, logoTransform.e, logoTransform.f);
  ctx.globalAlpha = Math.max(opts.fillAlpha ?? 1, opts.strokeAlpha ?? 0);
  for (const { path, color } of logoPaths) {
    ctx.fillStyle = color === '#C7CAC5' && !opts.isDark ? '#354230' : color;
    ctx.fill(path);
  }
  ctx.restore();
}

export function drawRoundedRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}
