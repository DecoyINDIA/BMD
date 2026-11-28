import fs from 'node:fs';
import path from 'node:path';
import {
  captureSceneFrames,
  encodeWebpWithBudget,
  makeContactSheet,
  makeBoundaryStrip,
  makeThemeParityCheck,
  makeMobilePreview,
  extractPoster,
} from './render.mjs';

const REPO_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', '..', '..');
const SCENES_DIR = path.join(REPO_ROOT, 'scripts', 'readme', 'velocity', 'scenes');
const ASSETS_OUT = path.join(REPO_ROOT, 'assets', 'readme');
const SCRATCH_ROOT = path.join(REPO_ROOT, 'target', 'readme-velocity');
const FRAMES_ROOT = path.join(SCRATCH_ROOT, 'frames');
const REVIEW_ROOT = path.join(SCRATCH_ROOT, 'review');

export const SCENE_SPECS = [
  // LEVEL 5 SIGNATURE COMPOSITIONS
  {
    id: 'hero-velocity',
    tier: 'L5',
    html: 'hero-velocity.html',
    outSubdir: 'hero',
    width: 1200,
    height: 560,
    scaleWidth: 960,
    fps: 24,
    durationSec: 12.0,
    maxBytes: 3.5 * 1024 * 1024,
    posterFrameFrac: 0.72,
  },
  {
    id: 'engine-planner',
    tier: 'L5',
    html: 'engine-planner.html',
    outSubdir: 'animations',
    width: 1200,
    height: 520,
    scaleWidth: 960,
    fps: 25,
    durationSec: 14.0,
    maxBytes: 3.2 * 1024 * 1024,
    posterFrameFrac: 0.56,
  },
  {
    id: 'benchmark-telemetry',
    tier: 'L5',
    html: 'benchmark-telemetry.html',
    outSubdir: 'animations',
    width: 1200,
    height: 620,
    scaleWidth: 960,
    fps: 24,
    durationSec: 12.0,
    maxBytes: 3.2 * 1024 * 1024,
    posterFrameFrac: 0.76,
  },
  {
    id: 'living-adoption',
    tier: 'L5',
    html: 'living-adoption.html',
    outSubdir: 'analytics',
    width: 1200,
    height: 480,
    scaleWidth: 960,
    fps: 24,
    durationSec: 10.0,
    maxBytes: 2.8 * 1024 * 1024,
    posterFrameFrac: 0.75,
  },
  {
    id: 'closing-horizon',
    tier: 'L5',
    html: 'closing-horizon.html',
    outSubdir: 'branding',
    width: 1200,
    height: 440,
    scaleWidth: 960,
    fps: 24,
    durationSec: 10.0,
    maxBytes: 3.0 * 1024 * 1024,
    posterFrameFrac: 0.72,
  },

  // LEVEL 4 ADVANCED MOTION COMPOSITIONS
  {
    id: 'product-cinema',
    tier: 'L4',
    html: 'product-cinema.html',
    outSubdir: 'screenshots',
    width: 1200,
    height: 620,
    scaleWidth: 960,
    fps: 24,
    durationSec: 14.0,
    maxBytes: 3.2 * 1024 * 1024,
    posterFrameFrac: 0.80,
  },
  {
    id: 'bittorrent-swarm',
    tier: 'L4',
    html: 'bittorrent-swarm.html',
    outSubdir: 'animations',
    width: 1200,
    height: 460,
    scaleWidth: 960,
    fps: 24,
    durationSec: 10.0,
    maxBytes: 2.5 * 1024 * 1024,
    posterFrameFrac: 0.72,
  },
  {
    id: 'browser-to-desktop',
    tier: 'L4',
    html: 'browser-to-desktop.html',
    outSubdir: 'animations',
    width: 1200,
    height: 400,
    scaleWidth: 960,
    fps: 24,
    durationSec: 10.0,
    maxBytes: 2.4 * 1024 * 1024,
    posterFrameFrac: 0.78,
  },
  {
    id: 'architecture-blueprint',
    tier: 'L4',
    html: 'architecture-blueprint.html',
    outSubdir: 'illustrations',
    width: 1200,
    height: 540,
    scaleWidth: 960,
    fps: 24,
    durationSec: 12.0,
    maxBytes: 2.5 * 1024 * 1024,
    posterFrameFrac: 0.76,
  },
  {
    id: 'global-rtl',
    tier: 'L4',
    html: 'global-rtl.html',
    outSubdir: 'features',
    width: 1200,
    height: 440,
    scaleWidth: 960,
    fps: 24,
    durationSec: 10.0,
    maxBytes: 2.4 * 1024 * 1024,
    posterFrameFrac: 0.60,
  },
  {
    id: 'download-platforms',
    tier: 'L4',
    html: 'download-platforms.html',
    outSubdir: 'features',
    width: 1200,
    height: 380,
    scaleWidth: 960,
    fps: 24,
    durationSec: 8.0,
    maxBytes: 2.2 * 1024 * 1024,
    posterFrameFrac: 0.75,
  },
  {
    id: 'privacy-freedom',
    tier: 'L4',
    html: 'privacy-freedom.html',
    outSubdir: 'features',
    width: 1200,
    height: 400,
    scaleWidth: 960,
    fps: 24,
    durationSec: 9.0,
    maxBytes: 2.2 * 1024 * 1024,
    posterFrameFrac: 0.70,
  },
];

export async function runBuild() {
  const args = process.argv.slice(2);
  const onlyIdx = args.indexOf('--only');
  const onlySet = onlyIdx >= 0 ? new Set(args[onlyIdx + 1].split(',').map((s) => s.trim())) : null;
  const themesIdx = args.indexOf('--themes');
  const themes = themesIdx >= 0 ? args[themesIdx + 1].split(',') : ['dark', 'light'];

  const report = [];

  for (const spec of SCENE_SPECS) {
    if (onlySet && !onlySet.has(spec.id)) continue;
    const htmlPath = path.join(SCENES_DIR, spec.html);
    const totalFrames = Math.round(spec.fps * spec.durationSec);
    const keyFrameIdx = Math.min(totalFrames - 1, Math.round(totalFrames * (spec.posterFrameFrac || 0.7)));

    const themeKeyFrames = {};

    for (const theme of themes) {
      const framesDir = path.join(FRAMES_ROOT, `${spec.id}-${theme}`);
      console.log(`\n>>> Capturing ${spec.id} (${theme}) ${spec.width}x${spec.height} @ ${spec.fps}fps (${spec.durationSec}s = ${totalFrames}f)...`);
      const cap = await captureSceneFrames({
        htmlPath,
        outDir: framesDir,
        width: spec.width,
        height: spec.height,
        fps: spec.fps,
        durationSec: spec.durationSec,
        theme,
        repoRoot: REPO_ROOT,
      });

      const outWebp = path.join(ASSETS_OUT, spec.outSubdir, `${spec.id}-${theme}.webp`);
      const enc = encodeWebpWithBudget({
        framesDir,
        outPath: outWebp,
        fps: spec.fps,
        scaleWidth: spec.scaleWidth,
        maxBytes: spec.maxBytes,
      });

      // Also export static PNG fallback right alongside the animated WebP
      const staticPng = path.join(ASSETS_OUT, spec.outSubdir, `${spec.id}-static-${theme}.png`);
      extractPoster({ framesDir, outPath: staticPng, frameIndex: keyFrameIdx, scaleWidth: spec.scaleWidth });

      const revDir = path.join(REVIEW_ROOT, spec.id);
      makeContactSheet({
        framesDir,
        outPath: path.join(revDir, `${spec.id}-${theme}-contact.png`),
        cols: 4,
        rows: 4,
        totalFrames,
      });
      makeBoundaryStrip({
        framesDir,
        outPath: path.join(revDir, `${spec.id}-${theme}-loop-seam.png`),
        totalFrames,
      });
      const keyFramePng = path.join(revDir, `${spec.id}-${theme}-keyframe.png`);
      extractPoster({ framesDir, outPath: keyFramePng, frameIndex: keyFrameIdx, scaleWidth: spec.scaleWidth });
      themeKeyFrames[theme] = keyFramePng;

      if (theme === 'dark') {
        makeMobilePreview({
          framePath: keyFramePng,
          outPath: path.join(revDir, `${spec.id}-mobile-380w.png`),
          width: 380,
        });
      }

      report.push({
        scene: spec.id,
        tier: spec.tier,
        theme,
        frames: totalFrames,
        captureSec: (cap.elapsedMs / 1000).toFixed(1),
        sizeKB: (enc.bytes / 1024).toFixed(1),
        quality: enc.quality,
        withinBudget: enc.withinBudget,
      });
    }

    if (themeKeyFrames.light && themeKeyFrames.dark) {
      makeThemeParityCheck({
        lightFrame: themeKeyFrames.light,
        darkFrame: themeKeyFrames.dark,
        outPath: path.join(REVIEW_ROOT, spec.id, `${spec.id}-parity-overlay.png`),
      });
    }
  }

  console.log('\n=== VELOCITY BUILD SUMMARY ===');
  console.table(report);
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
if (isMain) {
  await runBuild();
}
