import path from 'node:path';
import { SCENE_SPECS } from './build.mjs';
import { extractPoster } from './render.mjs';

const REPO_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', '..', '..');
const ASSETS_OUT = path.join(REPO_ROOT, 'assets', 'readme');
const FRAMES_ROOT = 'C:\\Users\\subha\\.gemini\\antigravity\\brain\\22023cbd-5916-4595-8b3f-30b158a4c07c\\scratch\\frames';

for (const spec of SCENE_SPECS) {
  const totalFrames = Math.round(spec.fps * spec.durationSec);
  const keyFrameIdx = Math.min(totalFrames - 1, Math.round(totalFrames * (spec.posterFrameFrac || 0.7)));
  for (const theme of ['dark', 'light']) {
    const framesDir = path.join(FRAMES_ROOT, `${spec.id}-${theme}`);
    const outPng = path.join(ASSETS_OUT, spec.outSubdir, `${spec.id}-static-${theme}.png`);
    extractPoster({ framesDir, outPath: outPng, frameIndex: keyFrameIdx, scaleWidth: spec.scaleWidth });
    console.log(`exported fallback: ${path.relative(REPO_ROOT, outPng)}`);
  }
}
