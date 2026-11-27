//! The main window's animated backdrop: slow simplex-noise clouds in the brand colours behind
//! the canvas (the menu bar, toolbar, categories and status strip). The download list stays a
//! solid surface over it. A port of the Velaris WebGL background (three warped noise fields
//! blended over a base colour, a glow at the centre, darker edges) to the CPU: drawn small and
//! scaled up, which suits a field of soft clouds and works with every renderer BMD draws with
//! (OpenGL, Direct3D, Vulkan, Metal, software).
//!
//! Its colours stay close to the canvas colour, so every text drawn on the canvas keeps its
//! contrast whatever the clouds do (see the test below). It moves only while the window has the
//! focus and the system's animations are on; otherwise it holds still.

use egui::{Color32, ColorImage, Rect, TextureHandle, TextureOptions};
use std::sync::atomic::{AtomicBool, Ordering};

/// One texel of the backdrop covers this many points of the window.
const CELL: f32 = 8.0;
/// The largest backdrop drawn, in texels (a 4K window at CELL 8 is about 480 × 270).
const MAX_W: usize = 320;
const MAX_H: usize = 200;
/// Frames a second while it moves.
const FPS: f64 = 20.0;
/// Noise time per second (the shader's speed 2 × 0.001 × 0.1, a slow drift).
const SPEED: f32 = 0.2;

/// Drawn under the canvas in this frame: the canvas's painters leave their background out.
static ON: AtomicBool = AtomicBool::new(false);

/// Is the backdrop showing? The menu bar, toolbar and categories then paint no background of
/// their own.
pub fn active() -> bool {
    ON.load(Ordering::Relaxed)
}

/// The colours a backdrop is made from: the canvas colour and four near it.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Colors {
    pub bg: Color32,
    pub tones: [Color32; 4],
}

impl Colors {
    /// From the theme in use: toward the ink (aluminium on dark, kombu on light), the surface,
    /// the accent (signal orange) and a deeper shade, each only a little.
    pub fn of(c: &crate::palette::Palette) -> Self {
        let bg = c.window;
        let deep = if c.dark { Color32::BLACK } else { c.text };
        Self {
            bg,
            tones: [
                mix(bg, c.list, 0.9),
                mix(bg, c.text, 0.05),
                mix(bg, c.accent, 0.16),
                mix(bg, deep, if c.dark { 0.25 } else { 0.06 }),
            ],
        }
    }
}

#[derive(Default)]
pub struct Backdrop {
    texture: Option<TextureHandle>,
    /// When the texture was last drawn (egui's clock), its size and colours.
    drawn: Option<(f64, [usize; 2], Colors)>,
}

impl Backdrop {
    /// Paints the backdrop over `rect` (the window's client area), or nothing when `enabled`
    /// is off. Asks for the next frame while it moves.
    pub fn paint(&mut self, ui: &egui::Ui, rect: Rect, enabled: bool) {
        ON.store(enabled, Ordering::Relaxed);
        if !enabled || rect.width() < 1.0 || rect.height() < 1.0 {
            return;
        }
        let ctx = ui.ctx();
        let colors = Colors::of(crate::theme::colors());
        let size = [
            ((rect.width() / CELL).ceil() as usize).clamp(2, MAX_W),
            ((rect.height() / CELL).ceil() as usize).clamp(2, MAX_H),
        ];
        let (now, moving) = ctx.input(|i| {
            let v = i.viewport();
            let shown = v.minimized != Some(true) && v.focused != Some(false);
            (i.time, shown && !reduced_motion())
        });
        let due = match self.drawn {
            None => true,
            Some((at, s, c)) => s != size || c != colors || (moving && now - at >= 1.0 / FPS),
        };
        if due {
            let image = render(size, now as f32 * SPEED, &colors);
            match &mut self.texture {
                Some(t) => t.set(image, TextureOptions::LINEAR),
                None => {
                    self.texture =
                        Some(ctx.load_texture("backdrop", image, TextureOptions::LINEAR));
                }
            }
            self.drawn = Some((now, size, colors));
        }
        if let Some(t) = &self.texture {
            let uv = Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0));
            ui.painter().image(t.id(), rect, uv, Color32::WHITE);
        }
        if moving {
            ctx.request_repaint_after(std::time::Duration::from_secs_f64(1.0 / FPS));
        }
    }
}

/// Has the user turned the system's animations off? Windows: Settings → Accessibility → Visual
/// effects → Animation effects.
#[cfg(windows)]
fn reduced_motion() -> bool {
    crate::win32::animations_off()
}

/// Elsewhere BMD has no reading of the desktop's animation setting yet (GNOME's and KDE's are
/// in their own settings stores): the backdrop moves, and View → Appearance → Animated
/// background turns it off.
#[cfg(not(windows))]
fn reduced_motion() -> bool {
    false
}

/// The backdrop at time `t`, `size` texels.
pub fn render(size: [usize; 2], t: f32, colors: &Colors) -> ColorImage {
    let [w, h] = size;
    let ratio = w as f32 / h as f32;
    let bg = rgb(colors.bg);
    let tones = colors.tones.map(rgb);
    let mut pixels = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let u = (x as f32 + 0.5) / w as f32;
            let v = 1.0 - (y as f32 + 0.5) / h as f32;
            let c = shade_at(u, v, ratio, t, bg, &tones);
            pixels.push(Color32::from_rgb(
                to_byte(c[0]),
                to_byte(c[1]),
                to_byte(c[2]),
            ));
        }
    }
    ColorImage::new(size, pixels)
}

/// The fragment shader for one point: `u`, `v` across the window (0..1, v up).
fn shade_at(u: f32, v: f32, ratio: f32, t: f32, bg: [f32; 3], tones: &[[f32; 3]; 4]) -> [f32; 3] {
    let (px, py) = ((u - 0.5) * ratio, v - 0.5);
    let t = t * 0.1;
    let n1 = snoise(px * 0.4 + t * 0.2, py * 0.4 - t * 0.3);
    let n2 = snoise(
        px * 0.55 - t * 0.15 + n1 * 0.25,
        py * 0.55 + t * 0.25 + n1 * 0.25,
    );
    let n3 = snoise(
        px * 0.75 + t * 0.1 + n2 * 0.2,
        py * 0.75 - t * 0.2 + n2 * 0.2,
    );
    let mut col = bg;
    col = lerp(col, tones[0], smoothstep(-0.2, 0.5, n1) * 0.85);
    col = lerp(col, tones[1], smoothstep(-0.1, 0.6, n2) * 0.7);
    col = lerp(col, tones[2], smoothstep(-0.3, 0.4, n3) * 0.6);
    col = lerp(col, tones[3], smoothstep(0.0, 0.7, n1 * n2) * 0.5);
    // The centre's glow and the darker edges, as blends toward the tones rather than the
    // shader's added light and 80% darkening, which would leave the canvas's text unreadable.
    let dist = (px * px + py * py).sqrt() * 1.5;
    col = lerp(col, tones[1], smoothstep(0.8, 0.0, dist) * 0.3);
    lerp(col, tones[3], smoothstep(0.3, 1.2, dist) * 0.5)
}

/// 2D simplex noise (Ashima Arts / Stefan Gustavson, as in the shader), about −1..1.
pub fn snoise(vx: f32, vy: f32) -> f32 {
    const C: [f32; 4] = [0.211_324_87, 0.366_025_42, -0.577_350_26, 0.024_390_243];
    let skew = (vx + vy) * C[1];
    let (ix, iy) = ((vx + skew).floor(), (vy + skew).floor());
    let unskew = (ix + iy) * C[0];
    let x0 = [vx - ix + unskew, vy - iy + unskew];
    let i1 = if x0[0] > x0[1] {
        [1.0, 0.0]
    } else {
        [0.0, 1.0]
    };
    let x1 = [x0[0] + C[0] - i1[0], x0[1] + C[0] - i1[1]];
    let x2 = [x0[0] + C[2], x0[1] + C[2]];
    let (ix, iy) = (ix.rem_euclid(289.0), iy.rem_euclid(289.0));
    let p = permute(add(
        permute([iy, iy + i1[1], iy + 1.0]),
        [ix, ix + i1[0], ix + 1.0],
    ));
    let dot = |a: [f32; 2]| a[0] * a[0] + a[1] * a[1];
    let mut m = [dot(x0), dot(x1), dot(x2)].map(|d| (0.5 - d).max(0.0));
    m = m.map(|v| v * v * v * v);
    let x = p.map(|v| 2.0 * (v * C[3]).fract() - 1.0);
    let h = x.map(|v| v.abs() - 0.5);
    let a0 = [0, 1, 2].map(|i| x[i] - (x[i] + 0.5).floor());
    for i in 0..3 {
        m[i] *= 1.792_842_9 - 0.853_734_7 * (a0[i] * a0[i] + h[i] * h[i]);
    }
    let g = [
        a0[0] * x0[0] + h[0] * x0[1],
        a0[1] * x1[0] + h[1] * x1[1],
        a0[2] * x2[0] + h[2] * x2[1],
    ];
    130.0 * (m[0] * g[0] + m[1] * g[1] + m[2] * g[2])
}

fn permute(x: [f32; 3]) -> [f32; 3] {
    x.map(|v| ((v * 34.0 + 1.0) * v).rem_euclid(289.0))
}

fn add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

/// GLSL's smoothstep, either way round.
fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn lerp(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
    [0, 1, 2].map(|i| a[i] + (b[i] - a[i]) * t)
}

fn rgb(c: Color32) -> [f32; 3] {
    [c.r(), c.g(), c.b()].map(|v| f32::from(v) / 255.0)
}

fn to_byte(v: f32) -> u8 {
    (v.clamp(0.0, 1.0) * 255.0).round() as u8
}

fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let c = lerp(rgb(a), rgb(b), t);
    Color32::from_rgb(to_byte(c[0]), to_byte(c[1]), to_byte(c[2]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::derive::contrast;

    #[test]
    fn noise_stays_in_range_and_moves() {
        let mut lo = f32::MAX;
        let mut hi = f32::MIN;
        for i in 0..2000 {
            let n = snoise(i as f32 * 0.137 - 40.0, i as f32 * 0.071 + 3.0);
            lo = lo.min(n);
            hi = hi.max(n);
        }
        assert!(lo >= -1.05 && hi <= 1.05, "{lo} {hi}");
        assert!(hi - lo > 1.0, "the field varies: {lo} {hi}");
        let a = render([16, 9], 0.0, &Colors::of(&crate::palette::CLASSIC.dark));
        let b = render([16, 9], 5.0, &Colors::of(&crate::palette::CLASSIC.dark));
        assert_ne!(a.pixels, b.pixels, "time moves it");
    }

    /// Whatever the clouds do, the canvas's texts read on them (WCAG 2.1 AA, 4.5:1), in BMD's
    /// look, light and dark, at many moments.
    #[test]
    fn canvas_text_reads_on_every_frame() {
        let theme = crate::palette::builtins()
            .into_iter()
            .find(|t| t.id == "emerald")
            .unwrap();
        for p in [&theme.light, &theme.dark] {
            let colors = Colors::of(p);
            for step in 0..24 {
                let image = render([48, 27], step as f32 * 7.3, &colors);
                for &px in &image.pixels {
                    for (what, ink) in [("text", p.text), ("muted", p.muted)] {
                        let r = contrast(ink, px);
                        assert!(r >= 4.5, "dark {}: {what} {r:.2} on {px:?}", p.dark);
                    }
                }
            }
        }
    }
}
