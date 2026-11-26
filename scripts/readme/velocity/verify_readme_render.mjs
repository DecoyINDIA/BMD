import fs from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer-core';
import { findBrowser, startStaticServer } from './render.mjs';

const REPO_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', '..', '..');
const REVIEW_DIR = 'C:\\Users\\subha\\.gemini\\antigravity\\brain\\22023cbd-5916-4595-8b3f-30b158a4c07c\\scratch\\review\\cycle-3';
const README_MD = fs.readFileSync(path.join(REPO_ROOT, 'README.md'), 'utf8');

function mdToPreviewHtml(md) {
  const lines = md.split(/\r?\n/);
  const out = [];
  let inTable = false;
  for (const line of lines) {
    if (/^\s*\|.*\|\s*$/.test(line)) {
      if (/^\s*\|[\s:|\-]+\|\s*$/.test(line)) continue;
      if (!inTable) { out.push('<table>'); inTable = true; }
      const cells = line.trim().slice(1, -1).split('|').map((c) => `<td>${c.trim()}</td>`).join('');
      out.push(`<tr>${cells}</tr>`);
      continue;
    }
    if (inTable) { out.push('</table>'); inTable = false; }
    if (line.startsWith('## ')) out.push(`<h2>${line.slice(3)}</h2>`);
    else if (line.startsWith('### ')) out.push(`<h3>${line.slice(4)}</h3>`);
    else if (line.trim() === '---') out.push('<hr/>');
    else out.push(line);
  }
  if (inTable) out.push('</table>');
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<base href="/repo/"/>
<style>
  :root { color-scheme: light dark; }
  body {
    margin: 0 auto;
    padding: 24px 20px;
    max-width: 1012px;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
    line-height: 1.6;
    overflow-wrap: anywhere;
  }
  @media (prefers-color-scheme: dark) {
    body { background: #0d1117; color: #e6edf3; }
    a { color: #58a6ff; }
    hr { border: 0; border-top: 1px solid #30363d; margin: 24px 0; }
    code { background: rgba(110,118,129,0.25); padding: 2px 5px; border-radius: 4px; }
  }
  @media (prefers-color-scheme: light) {
    body { background: #ffffff; color: #1f2328; }
    a { color: #0969da; }
    hr { border: 0; border-top: 1px solid #d0d7de; margin: 24px 0; }
    code { background: rgba(175,184,193,0.25); padding: 2px 5px; border-radius: 4px; }
  }
  img { max-width: 100%; height: auto; }
  table { border-collapse: collapse; width: 100%; margin: 16px 0; display: block; overflow-x: auto; }
  td, th { padding: 8px 12px; }
</style>
</head>
<body>${out.join('\n')}</body>
</html>`;
}

const previewHtml = mdToPreviewHtml(README_MD);
fs.mkdirSync(REVIEW_DIR, { recursive: true });
const previewPath = path.join(REVIEW_DIR, '_readme_preview.html');
fs.writeFileSync(previewPath, previewHtml, 'utf8');

const { server, port } = await startStaticServer([
  { prefix: '/review/', dir: REVIEW_DIR },
  { prefix: '/repo/', dir: REPO_ROOT },
]);

const browser = await puppeteer.launch({
  executablePath: findBrowser(),
  headless: 'new',
  args: ['--no-sandbox', '--disable-setuid-sandbox'],
});

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 900 },
  { name: 'mobile',  width: 380,  height: 844 },
];

try {
  for (const vp of VIEWPORTS) {
    for (const scheme of ['dark', 'light']) {
      const page = await browser.newPage();
      await page.setViewport({ width: vp.width, height: vp.height, deviceScaleFactor: 1 });
      await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: scheme }]);

      const broken = [];
      page.on('response', (res) => {
        if (res.status() >= 400 && res.url().includes('127.0.0.1')) {
          broken.push(`${res.status()} ${res.url()}`);
        }
      });

      await page.goto(`http://127.0.0.1:${port}/review/_readme_preview.html`, { waitUntil: 'networkidle0' });
      await page.evaluate(() => {
        document.querySelectorAll('details').forEach((d) => { d.open = true; });
      });
      await new Promise((r) => setTimeout(r, 450));

      const stats = await page.evaluate(() => {
        const imgs = Array.from(document.querySelectorAll('img'));
        const results = imgs.map((im) => ({
          src: im.currentSrc || im.getAttribute('src'),
          ok: im.complete && im.naturalWidth > 0,
        }));
        const overflow = document.documentElement.scrollWidth > window.innerWidth;
        const maxH = document.documentElement.scrollHeight;
        return { totalImgs: imgs.length, failed: results.filter((r) => !r.ok), overflow, maxH };
      });

      // Capture top, mid, and bottom viewport sections
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: path.join(REVIEW_DIR, `readme-${vp.name}-${scheme}-top.png`) });

      await page.evaluate((y) => window.scrollTo(0, y), Math.round(stats.maxH * 0.42));
      await new Promise((r) => setTimeout(r, 150));
      await page.screenshot({ path: path.join(REVIEW_DIR, `readme-${vp.name}-${scheme}-mid.png`) });

      await page.evaluate((y) => window.scrollTo(0, y), Math.max(0, stats.maxH - vp.height));
      await new Promise((r) => setTimeout(r, 150));
      await page.screenshot({ path: path.join(REVIEW_DIR, `readme-${vp.name}-${scheme}-bot.png`) });

      console.log(`[${vp.name} ${scheme}] imgs=${stats.totalImgs}, failed=${stats.failed.length}, overflow=${stats.overflow}, httpBroken=${broken.length}`);
      if (stats.failed.length > 0 || broken.length > 0 || stats.overflow) {
        console.error('Issues:', { failed: stats.failed, broken, overflow: stats.overflow });
        process.exitCode = 1;
      }
      await page.close();
    }
  }
} finally {
  await browser.close();
  server.close();
  fs.rmSync(previewPath, { force: true });
}
