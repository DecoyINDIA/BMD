import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import puppeteer from 'puppeteer-core';

const CHROME_PATHS = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
];

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
};

export function findBrowser() {
  for (const p of CHROME_PATHS) {
    if (fs.existsSync(p)) return p;
  }
  throw new Error('No Chrome/Edge executable found');
}

export function startStaticServer(roots) {
  const server = http.createServer((req, res) => {
    const cleanUrl = (req.url || '/').split('?')[0];
    if (cleanUrl === '/favicon.ico') {
      res.writeHead(204);
      res.end();
      return;
    }
    for (const { prefix, dir } of roots) {
      if (cleanUrl.startsWith(prefix)) {
        const rel = decodeURIComponent(cleanUrl.slice(prefix.length)).replace(/^\/+/, '');
        const full = path.resolve(dir, rel);
        if (!full.startsWith(path.resolve(dir))) {
          res.writeHead(403);
          res.end('Forbidden');
          return;
        }
        if (fs.existsSync(full) && fs.statSync(full).isFile()) {
          const ext = path.extname(full).toLowerCase();
          res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
          fs.createReadStream(full).pipe(res);
          return;
        }
      }
    }
    res.writeHead(404);
    res.end('Not found: ' + cleanUrl);
  });

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      resolve({ server, port: addr.port });
    });
  });
}

export async function launchRenderer({ width, height, deviceScaleFactor = 1 }) {
  const executablePath = findBrowser();
  const browser = await puppeteer.launch({
    executablePath,
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--use-gl=angle',
      '--use-angle=swiftshader-webgl',
      '--enable-webgl',
      '--enable-unsafe-swiftshader',
      '--ignore-gpu-blocklist',
      '--disable-background-timer-throttling',
      '--disable-renderer-backgrounding',
      `--window-size=${width},${height}`,
    ],
  });
  const page = await browser.newPage();
  await page.setViewport({ width, height, deviceScaleFactor });
  return { browser, page };
}

export async function captureSceneFrames({
  htmlPath,
  outDir,
  width,
  height,
  fps,
  durationSec,
  theme = 'dark',
  params = {},
  deviceScaleFactor = 1,
  repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', '..', '..'),
}) {
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  const totalFrames = Math.round(fps * durationSec);
  const absHtml = path.resolve(htmlPath);
  const htmlDir = path.dirname(absHtml);
  const htmlFile = path.basename(absHtml);
  const velocityDir = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')));

  const { server, port } = await startStaticServer([
    { prefix: '/scene/', dir: htmlDir },
    { prefix: '/velocity/', dir: velocityDir },
    { prefix: '/repo/', dir: repoRoot },
  ]);

  const { browser, page } = await launchRenderer({ width, height, deviceScaleFactor });
  const errors = [];
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(`console.error: ${msg.text()}`);
  });
  page.on('response', (res) => {
    if (res.status() >= 400) errors.push(`HTTP ${res.status()}: ${res.url()}`);
  });

  try {
    await page.goto(`http://127.0.0.1:${port}/scene/${encodeURIComponent(htmlFile)}`, { waitUntil: 'networkidle0' });

    const info = await page.evaluate(
      async ({ theme, params, width, height, fps, totalFrames }) => {
        if (document.fonts && document.fonts.ready) {
          await document.fonts.ready;
        }
        if (typeof window.initScene === 'function') {
          await window.initScene({ theme, params, width, height, fps, totalFrames });
        }
        const glCanvas = document.querySelector('canvas');
        let webglInfo = 'no-canvas';
        if (glCanvas) {
          const gl = glCanvas.getContext('webgl2') || glCanvas.getContext('webgl');
          if (gl) {
            const dbg = gl.getExtension('WEBGL_debug_renderer_info');
            webglInfo = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
          } else {
            webglInfo = '2d-canvas';
          }
        }
        return { webglInfo, hasRenderFrame: typeof window.renderFrame === 'function' };
      },
      { theme, params, width, height, fps, totalFrames }
    );

    if (!info.hasRenderFrame) {
      throw new Error(`Scene ${htmlPath} did not define window.renderFrame(). Errors: ${errors.join('; ')}`);
    }

    const t0 = performance.now();
    for (let i = 0; i < totalFrames; i++) {
      const t = i / fps;
      const progress = i / totalFrames;
      await page.evaluate(
        async ({ frame, totalFrames, t, progress }) => {
          await window.renderFrame(t, progress, frame, totalFrames);
        },
        { frame: i, totalFrames, t, progress }
      );
      const frameFile = path.join(outDir, `frame_${String(i).padStart(4, '0')}.png`);
      await page.screenshot({ path: frameFile, type: 'png', omitBackground: false });
    }
    const elapsedMs = Math.round(performance.now() - t0);
    if (errors.length > 0) {
      throw new Error(`Scene errors during capture: ${errors.join(' | ')}`);
    }
    return { totalFrames, elapsedMs, webglInfo: info.webglInfo };
  } finally {
    await browser.close();
    server.close();
  }
}

export function encodeWebp({ framesDir, outPath, fps, quality = 85, lossless = false, scaleWidth = null }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const pattern = path.join(framesDir, 'frame_%04d.png');
  const vf = scaleWidth ? `scale=${scaleWidth}:-2:flags=lanczos` : 'null';
  const args = [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-framerate', String(fps),
    '-i', pattern,
    '-vf', vf,
    '-c:v', 'libwebp_anim',
    '-loop', '0',
  ];
  if (lossless) {
    args.push('-lossless', '1', '-compression_level', '6');
  } else {
    args.push('-lossless', '0', '-q:v', String(quality), '-compression_level', '5', '-preset', 'default');
  }
  args.push(outPath);
  execFileSync('ffmpeg', args, { stdio: 'inherit' });
  return fs.statSync(outPath).size;
}

export function encodeWebpWithBudget({
  framesDir,
  outPath,
  fps,
  scaleWidth = 960,
  maxBytes = 3.5 * 1024 * 1024,
  qualities = [86, 82, 78, 72],
}) {
  let finalBytes = 0;
  let usedQ = qualities[0];
  for (const q of qualities) {
    usedQ = q;
    finalBytes = encodeWebp({ framesDir, outPath, fps, quality: q, scaleWidth });
    if (finalBytes <= maxBytes) {
      return { bytes: finalBytes, quality: usedQ, withinBudget: true };
    }
  }
  return { bytes: finalBytes, quality: usedQ, withinBudget: finalBytes <= maxBytes };
}

export function encodeApng({ framesDir, outPath, fps, scaleWidth = null }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const pattern = path.join(framesDir, 'frame_%04d.png');
  const vf = scaleWidth ? `scale=${scaleWidth}:-2:flags=lanczos` : 'null';
  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-framerate', String(fps),
    '-i', pattern,
    '-vf', vf,
    '-plays', '0',
    '-f', 'apng',
    outPath,
  ], { stdio: 'inherit' });
  return fs.statSync(outPath).size;
}

export function encodeGif({ framesDir, outPath, fps, scaleWidth = null, statsMode = 'diff', dither = 'sierra2_4a', maxColors = 256 }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const pattern = path.join(framesDir, 'frame_%04d.png');
  const palettePath = path.join(framesDir, `_palette_${statsMode}.png`);
  const scaleFilter = scaleWidth ? `scale=${scaleWidth}:-2:flags=lanczos,` : '';

  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-framerate', String(fps),
    '-i', pattern,
    '-vf', `${scaleFilter}palettegen=max_colors=${maxColors}:stats_mode=${statsMode}`,
    '-frames:v', '1',
    palettePath,
  ], { stdio: 'inherit' });

  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-framerate', String(fps),
    '-i', pattern,
    '-i', palettePath,
    '-lavfi', `${scaleFilter}paletteuse=dither=${dither}:diff_mode=rectangle`,
    '-loop', '0',
    outPath,
  ], { stdio: 'inherit' });

  return fs.statSync(outPath).size;
}

export function makeContactSheet({ framesDir, outPath, cols = 4, rows = 4, totalFrames }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const count = cols * rows;
  const step = Math.max(1, Math.floor(totalFrames / count));
  const pattern = path.join(framesDir, 'frame_%04d.png');
  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-i', pattern,
    '-vf', `select='not(mod(n\\,${step}))',scale=400:-2:flags=lanczos,tile=${cols}x${rows}`,
    '-frames:v', '1',
    outPath,
  ], { stdio: 'inherit' });
}

export function makeBoundaryStrip({ framesDir, outPath, totalFrames }) {
  // Stitches frames [N-3, N-2, N-1, 0, 1, 2] horizontally so loop seam continuity is obvious
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const indices = [
    Math.max(0, totalFrames - 3),
    Math.max(0, totalFrames - 2),
    Math.max(0, totalFrames - 1),
    0,
    Math.min(totalFrames - 1, 1),
    Math.min(totalFrames - 1, 2),
  ];
  const inputs = indices.flatMap((idx) => ['-i', path.join(framesDir, `frame_${String(idx).padStart(4, '0')}.png`)]);
  const scales = indices.map((_, k) => `[${k}:v]scale=320:-2:flags=lanczos[v${k}]`).join(';');
  const hstack = indices.map((_, k) => `[v${k}]`).join('') + `hstack=inputs=${indices.length}[out]`;
  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    ...inputs,
    '-filter_complex', `${scales};${hstack}`,
    '-map', '[out]',
    '-frames:v', '1',
    outPath,
  ], { stdio: 'inherit' });
}

export function makeThemeParityCheck({ lightFrame, darkFrame, outPath }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-i', lightFrame,
    '-i', darkFrame,
    '-filter_complex', '[0:v][1:v]blend=all_mode=average,scale=960:-2:flags=lanczos[out]',
    '-map', '[out]',
    '-frames:v', '1',
    outPath,
  ], { stdio: 'inherit' });
}

export function makeMobilePreview({ framePath, outPath, width = 380 }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-i', framePath,
    '-vf', `scale=${width}:-2:flags=lanczos`,
    '-frames:v', '1',
    outPath,
  ], { stdio: 'inherit' });
}

export function extractPoster({ framesDir, outPath, frameIndex = 0, scaleWidth = 960 }) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  const src = path.join(framesDir, `frame_${String(frameIndex).padStart(4, '0')}.png`);
  execFileSync('ffmpeg', [
    '-y', '-hide_banner', '-loglevel', 'error',
    '-i', src,
    '-vf', `scale=${scaleWidth}:-2:flags=lanczos`,
    '-frames:v', '1',
    outPath,
  ], { stdio: 'inherit' });
}
