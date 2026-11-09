//! Blend modes, tint and colour matrix of the layers (P26): the port of `fr.toolmap.core.layers.Blend`.
//!
//! Every function here does the same floating-point operations, in the same order, as the Kotlin code, so the two
//! give the same pixels bit for bit (`EngineParityTest` compares them). Pixels are ARGB ints with straight (not
//! premultiplied) colours, as `Bitmap.getPixels` gives them. What changes is how the work is laid out: the mode is
//! chosen once per call instead of once per channel, the scratch values live in registers instead of arrays on the
//! heap, a channel value becomes a number between 0 and 1 by a table instead of a division, and an opaque pixel
//! over an opaque pixel (most of a map) is read from a table made once per mode and opacity.

use std::sync::{Arc, Mutex};

const CHANNELS: usize = 3;
const MAX: f32 = 255.0;
const HALF: f32 = 0.5;
const QUARTER: f32 = 0.25;
const LUM_R: f32 = 0.3;
const LUM_G: f32 = 0.59;
const LUM_B: f32 = 0.11;
const ALPHA_SHIFT: u32 = 24;
const SHIFTS: [u32; CHANNELS] = [16, 8, 0];
const ALPHA_MASK: u32 = 0xFF00_0000;
const OPAQUE: u32 = 0xFF;

/// The blend modes, in the order of the Kotlin enum `BlendMode` (its ordinal crosses the JNI door).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(u8)]
pub enum Mode {
    Normal = 0,
    Multiply,
    Screen,
    Overlay,
    Darken,
    Lighten,
    ColorDodge,
    ColorBurn,
    HardLight,
    SoftLight,
    Difference,
    Exclusion,
    Hue,
    Saturation,
    Color,
    Luminosity,
}

pub const MODE_COUNT: usize = 16;
const SEPARABLE_COUNT: usize = Mode::Hue as usize;

impl Mode {
    pub fn from_ordinal(ordinal: i32) -> Option<Mode> {
        const ALL: [Mode; MODE_COUNT] = [
            Mode::Normal,
            Mode::Multiply,
            Mode::Screen,
            Mode::Overlay,
            Mode::Darken,
            Mode::Lighten,
            Mode::ColorDodge,
            Mode::ColorBurn,
            Mode::HardLight,
            Mode::SoftLight,
            Mode::Difference,
            Mode::Exclusion,
            Mode::Hue,
            Mode::Saturation,
            Mode::Color,
            Mode::Luminosity,
        ];
        usize::try_from(ordinal).ok().and_then(|i| ALL.get(i).copied())
    }

    /// True for the modes computed channel by channel; the last four work on the whole colour.
    pub fn is_separable(self) -> bool {
        (self as usize) < SEPARABLE_COUNT
    }
}

// --- Java's arithmetic, where Rust's own differs -------------------------------------------------------------------

/// `Math.min(float, float)`: NaN if either is NaN, and -0.0 is below +0.0.
#[inline(always)]
pub(crate) fn jmin(a: f32, b: f32) -> f32 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.is_sign_negative() {
        return b;
    }
    if a <= b {
        a
    } else {
        b
    }
}

/// `Math.max(float, float)`: NaN if either is NaN, and +0.0 is above -0.0.
#[inline(always)]
pub(crate) fn jmax(a: f32, b: f32) -> f32 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.is_sign_negative() {
        return b;
    }
    if a >= b {
        a
    } else {
        b
    }
}

/// Kotlin's `Float.coerceIn(0f, 1f)`: a NaN stays a NaN. Written out, as the Kotlin code compares.
#[inline(always)]
#[allow(clippy::manual_clamp)]
pub(crate) fn coerce_unit(v: f32) -> f32 {
    if v < 0.0 {
        0.0
    } else if v > 1.0 {
        1.0
    } else {
        v
    }
}

/// Kotlin's `sqrt(Float)`: the square root is taken in double precision, then rounded.
#[inline(always)]
fn jsqrt(v: f32) -> f32 {
    (v as f64).sqrt() as f32
}

/// `(v * 255 + 0.5).toInt().coerceIn(0, 255)`: Rust's `as i32` saturates and maps NaN to 0, as Kotlin's `toInt`.
#[inline(always)]
fn pack(v: f32) -> u32 {
    ((v * MAX + HALF) as i32).clamp(0, 255) as u32
}

/// `value / 255f` for each channel value: the division the Kotlin code does, done once.
static UNIT: [f32; 256] = {
    let mut table = [0.0f32; 256];
    let mut i = 0;
    while i < 256 {
        table[i] = i as f32 / MAX;
        i += 1;
    }
    table
};

#[inline(always)]
fn unit(value: u32) -> f32 {
    UNIT[(value & OPAQUE) as usize]
}

#[inline(always)]
fn unpack(argb: u32) -> [f32; CHANNELS] {
    [unit(argb >> 16), unit(argb >> 8), unit(argb)]
}

// --- The blend functions B(cb, cs) of W3C Compositing and Blending Level 1, § 10 -----------------------------------

#[inline(always)]
fn screen(cb: f32, cs: f32) -> f32 {
    cb + cs - cb * cs
}

#[inline(always)]
fn hard_light(cb: f32, cs: f32) -> f32 {
    if cs <= HALF {
        cb * 2.0 * cs
    } else {
        screen(cb, 2.0 * cs - 1.0)
    }
}

#[inline(always)]
fn soft_light(cb: f32, cs: f32) -> f32 {
    if cs <= HALF {
        return cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb);
    }
    let d = if cb <= QUARTER { ((16.0 * cb - 12.0) * cb + 4.0) * cb } else { jsqrt(cb) };
    cb + (2.0 * cs - 1.0) * (d - cb)
}

/// The blend function of a separable `mode`, channels in 0..1.
#[inline(always)]
pub fn channel(mode: Mode, cb: f32, cs: f32) -> f32 {
    match mode {
        Mode::Normal => cs,
        Mode::Multiply => cb * cs,
        Mode::Screen => screen(cb, cs),
        Mode::Overlay => hard_light(cs, cb),
        Mode::Darken => jmin(cb, cs),
        Mode::Lighten => jmax(cb, cs),
        Mode::ColorDodge => {
            if cb == 0.0 {
                0.0
            } else if cs >= 1.0 {
                1.0
            } else {
                jmin(1.0, cb / (1.0 - cs))
            }
        }
        Mode::ColorBurn => {
            if cb >= 1.0 {
                1.0
            } else if cs == 0.0 {
                0.0
            } else {
                1.0 - jmin(1.0, (1.0 - cb) / cs)
            }
        }
        Mode::HardLight => hard_light(cb, cs),
        Mode::SoftLight => soft_light(cb, cs),
        Mode::Difference => (cb - cs).abs(),
        Mode::Exclusion => cb + cs - 2.0 * cb * cs,
        // The whole-colour modes never come here (`color` handles them).
        Mode::Hue | Mode::Saturation | Mode::Color | Mode::Luminosity => cs,
    }
}

#[inline(always)]
pub(crate) fn lum(c: [f32; CHANNELS]) -> f32 {
    LUM_R * c[0] + LUM_G * c[1] + LUM_B * c[2]
}

#[inline(always)]
fn sat(c: [f32; CHANNELS]) -> f32 {
    jmax(c[0], jmax(c[1], c[2])) - jmin(c[0], jmin(c[1], c[2]))
}

/// `SetLum(c, l)`: `c` moved to luminosity `l`, then brought back into 0..1 (`ClipColor`).
#[inline(always)]
fn set_lum(c: [f32; CHANNELS], l: f32) -> [f32; CHANNELS] {
    let d = l - lum(c);
    let mut out = [c[0] + d, c[1] + d, c[2] + d];
    let lo = lum(out);
    let n = jmin(out[0], jmin(out[1], out[2]));
    let x = jmax(out[0], jmax(out[1], out[2]));
    for v in out.iter_mut() {
        if n < 0.0 {
            *v = lo + (*v - lo) * lo / (lo - n);
        }
        if x > 1.0 {
            *v = lo + (*v - lo) * (1.0 - lo) / (x - lo);
        }
    }
    out
}

/// `SetSat(c, s)`: `c` with saturation `s`, its hue kept.
#[inline(always)]
fn set_sat(c: [f32; CHANNELS], s: f32) -> [f32; CHANNELS] {
    let hi = jmax(c[0], jmax(c[1], c[2]));
    let lo = jmin(c[0], jmin(c[1], c[2]));
    let mut out = [0.0f32; CHANNELS];
    for i in 0..CHANNELS {
        out[i] = if hi <= lo {
            0.0
        } else if c[i] == hi {
            s
        } else if c[i] == lo {
            0.0
        } else {
            (c[i] - lo) * s / (hi - lo)
        };
    }
    out
}

/// The blended colour `B(Cb, Cs)` of any `mode`, colours in 0..1.
#[inline(always)]
pub fn color(mode: Mode, cb: [f32; CHANNELS], cs: [f32; CHANNELS]) -> [f32; CHANNELS] {
    match mode {
        Mode::Hue => set_lum(set_sat(cs, sat(cb)), lum(cb)),
        Mode::Saturation => set_lum(set_sat(cb, sat(cs)), lum(cb)),
        Mode::Color => set_lum(cs, lum(cb)),
        Mode::Luminosity => set_lum(cb, lum(cs)),
        _ => [channel(mode, cb[0], cs[0]), channel(mode, cb[1], cs[1]), channel(mode, cb[2], cs[2])],
    }
}

/// One pixel of `composite`: `s` drawn over `b`, or `None` when the source is transparent there (`b` stays).
#[inline(always)]
fn composite_pixel(b: u32, s: u32, mode: Mode, gain: f32) -> Option<u32> {
    let alpha_s = unit(s >> ALPHA_SHIFT) * gain;
    if alpha_s <= 0.0 {
        return None;
    }
    let alpha_b = unit(b >> ALPHA_SHIFT);
    let cs = unpack(s);
    let cb = unpack(b);
    let mixed = if alpha_b <= 0.0 || mode == Mode::Normal {
        cs
    } else {
        let blended = color(mode, cb, cs);
        // Where the backdrop is partly transparent the source keeps part of its own colour.
        [
            (1.0 - alpha_b) * cs[0] + alpha_b * blended[0],
            (1.0 - alpha_b) * cs[1] + alpha_b * blended[1],
            (1.0 - alpha_b) * cs[2] + alpha_b * blended[2],
        ]
    };
    let alpha_o = alpha_s + alpha_b * (1.0 - alpha_s);
    let mut argb = pack(alpha_o) << ALPHA_SHIFT;
    for c in 0..CHANNELS {
        let premultiplied = alpha_s * mixed[c] + alpha_b * (1.0 - alpha_s) * cb[c];
        argb |= pack(premultiplied / alpha_o) << SHIFTS[c];
    }
    Some(argb)
}

/// What an opaque pixel over an opaque pixel gives for one separable mode and one opacity: its alpha, the same for
/// all, and each channel from its two values (`channels[backdrop * 256 + source]`). Filled by `composite_pixel`
/// itself, so it cannot disagree with it.
struct OpaqueTable {
    mode: Mode,
    gain_bits: u32,
    alpha: u32,
    channels: Box<[u8; 65536]>,
}

/// The tables made lately, the latest last: the tiles of a layer all ask for the same one.
static OPAQUE_TABLES: Mutex<Vec<Arc<OpaqueTable>>> = Mutex::new(Vec::new());

/// 64 KB each: the layers of a map rarely use more modes and opacities at once.
const KEPT_TABLES: usize = 12;

fn opaque_table(mode: Mode, gain: f32) -> Arc<OpaqueTable> {
    let gain_bits = gain.to_bits();
    {
        let mut kept = OPAQUE_TABLES.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(i) = kept.iter().position(|t| t.mode == mode && t.gain_bits == gain_bits) {
            let table = kept.remove(i);
            kept.push(Arc::clone(&table));
            return table;
        }
    }
    // Made outside the lock: two threads may make the same table at once, both right.
    let mut channels = Box::new([0u8; 65536]);
    let mut alpha = OPAQUE;
    for b in 0..256u32 {
        for s in 0..256u32 {
            let out = composite_pixel(ALPHA_MASK | b, ALPHA_MASK | s, mode, gain).unwrap_or(ALPHA_MASK | b);
            channels[(b * 256 + s) as usize] = (out & OPAQUE) as u8;
            alpha = out >> ALPHA_SHIFT;
        }
    }
    let table = Arc::new(OpaqueTable { mode, gain_bits, alpha, channels });
    let mut kept = OPAQUE_TABLES.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if kept.len() >= KEPT_TABLES {
        kept.remove(0);
    }
    kept.push(Arc::clone(&table));
    table
}

/// Fewer pixels than this are not worth a table of 65 536 entries.
const TABLE_MIN_PIXELS: usize = 16 * 1024;

/// Draws `above` over `below` (same length), in place in `below`: `mode` blends the colours where both are present,
/// `opacity` (0..1) scales the alpha of `above`, and the result is "source over" (W3C Compositing § 5.8).
pub fn composite(below: &mut [i32], above: &[i32], mode: Mode, opacity: f32) {
    composite_with(below, above, mode, opacity, None)
}

/// `composite` for one piece of a larger image of `total` pixels: the table is made when the whole image is worth it.
pub fn composite_part(below: &mut [i32], above: &[i32], mode: Mode, opacity: f32, total: usize) {
    composite_with(below, above, mode, opacity, Some(total))
}

fn composite_with(below: &mut [i32], above: &[i32], mode: Mode, opacity: f32, total: Option<usize>) {
    debug_assert_eq!(below.len(), above.len());
    let gain = coerce_unit(opacity);
    let worth_a_table = total.unwrap_or(below.len()) >= TABLE_MIN_PIXELS;
    // A source at no opacity writes nothing, and a NaN opacity is left to the formula.
    let table = if gain > 0.0 && mode.is_separable() && worth_a_table { Some(opaque_table(mode, gain)) } else { None };
    let table = table.as_deref();
    match mode {
        Mode::Normal => run(below, above, Mode::Normal, gain, table),
        Mode::Multiply => run(below, above, Mode::Multiply, gain, table),
        Mode::Screen => run(below, above, Mode::Screen, gain, table),
        Mode::Overlay => run(below, above, Mode::Overlay, gain, table),
        Mode::Darken => run(below, above, Mode::Darken, gain, table),
        Mode::Lighten => run(below, above, Mode::Lighten, gain, table),
        Mode::ColorDodge => run(below, above, Mode::ColorDodge, gain, table),
        Mode::ColorBurn => run(below, above, Mode::ColorBurn, gain, table),
        Mode::HardLight => run(below, above, Mode::HardLight, gain, table),
        Mode::SoftLight => run(below, above, Mode::SoftLight, gain, table),
        Mode::Difference => run(below, above, Mode::Difference, gain, table),
        Mode::Exclusion => run(below, above, Mode::Exclusion, gain, table),
        Mode::Hue => run(below, above, Mode::Hue, gain, table),
        Mode::Saturation => run(below, above, Mode::Saturation, gain, table),
        Mode::Color => run(below, above, Mode::Color, gain, table),
        Mode::Luminosity => run(below, above, Mode::Luminosity, gain, table),
    }
}

/// The pixel loop, compiled once per mode (each call above passes a constant): no choice of mode per pixel.
#[inline(always)]
fn run(below: &mut [i32], above: &[i32], mode: Mode, gain: f32, table: Option<&OpaqueTable>) {
    for (b, &s) in below.iter_mut().zip(above.iter()) {
        let (bu, su) = (*b as u32, s as u32);
        if let Some(table) = table {
            // Both opaque: each channel depends on its two values only.
            if (bu & su) >> ALPHA_SHIFT == OPAQUE {
                let channels = &table.channels;
                let r = channels[(((bu >> 8) & 0xFF00) | ((su >> 16) & OPAQUE)) as usize] as u32;
                let g = channels[((bu & 0xFF00) | ((su >> 8) & OPAQUE)) as usize] as u32;
                let bl = channels[(((bu & OPAQUE) << 8) | (su & OPAQUE)) as usize] as u32;
                *b = ((table.alpha << ALPHA_SHIFT) | (r << 16) | (g << 8) | bl) as i32;
                continue;
            }
        }
        if let Some(out) = composite_pixel(bu, su, mode, gain) {
            *b = out as i32;
        }
    }
}

/// Gives `pixels` a `tint` colour (`0xRRGGBB`), in place: each pixel takes the hue and saturation of the tint and
/// keeps its own luminosity, mixed with its own colour by `intensity` (0..1). Alpha stays.
pub fn tint(pixels: &mut [i32], tint: i32, intensity: f32) {
    let k = coerce_unit(intensity);
    if k <= 0.0 {
        return;
    }
    let ct = unpack(tint as u32);
    for p in pixels.iter_mut() {
        let pu = *p as u32;
        let c = unpack(pu);
        let tinted = set_lum(ct, lum(c));
        let mut argb = pu & ALPHA_MASK;
        for ch in 0..CHANNELS {
            argb |= pack(c[ch] + (tinted[ch] - c[ch]) * k) << SHIFTS[ch];
        }
        *p = argb as i32;
    }
}

pub const MATRIX_SIZE: usize = 20;

/// Applies a colour `matrix` (4 × 5, Android's layout, offsets in 0..255) to `pixels`, in place. Alpha stays.
pub fn adjust(pixels: &mut [i32], matrix: &[f32; MATRIX_SIZE]) {
    for p in pixels.iter_mut() {
        let pu = *p as u32;
        let r = ((pu >> 16) & OPAQUE) as f32;
        let g = ((pu >> 8) & OPAQUE) as f32;
        let b = (pu & OPAQUE) as f32;
        let mut argb = pu & ALPHA_MASK;
        for row in 0..CHANNELS {
            let v = matrix[row * 5] * r + matrix[row * 5 + 1] * g + matrix[row * 5 + 2] * b + matrix[row * 5 + 4];
            argb |= (((v + HALF) as i32).clamp(0, 255) as u32) << SHIFTS[row];
        }
        *p = argb as i32;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn near(expected: f32, actual: f32) {
        assert!((expected - actual).abs() < 1e-4, "expected {expected}, got {actual}");
    }

    /// The values of `BlendTest.separableModesGiveTheReferenceValues` (hand-computed from the W3C text).
    #[test]
    fn separable_modes_give_the_reference_values() {
        let cases = [
            (Mode::Normal, 0.2, 0.7, 0.7),
            (Mode::Multiply, 0.5, 0.5, 0.25),
            (Mode::Screen, 0.5, 0.5, 0.75),
            (Mode::Overlay, 0.25, 0.5, 0.25),
            (Mode::Overlay, 0.75, 0.5, 0.75),
            (Mode::Darken, 0.2, 0.7, 0.2),
            (Mode::Lighten, 0.2, 0.7, 0.7),
            (Mode::ColorDodge, 0.5, 0.5, 1.0),
            (Mode::ColorDodge, 0.2, 0.5, 0.4),
            (Mode::ColorDodge, 0.0, 1.0, 0.0),
            (Mode::ColorBurn, 0.5, 0.5, 0.0),
            (Mode::ColorBurn, 0.8, 0.5, 0.6),
            (Mode::ColorBurn, 1.0, 0.0, 1.0),
            (Mode::HardLight, 0.5, 0.25, 0.25),
            (Mode::HardLight, 0.5, 0.75, 0.75),
            (Mode::SoftLight, 0.25, 0.75, 0.375),
            (Mode::SoftLight, 0.5, 0.25, 0.375),
            (Mode::SoftLight, 0.64, 1.0, 0.8),
            (Mode::Difference, 0.2, 0.7, 0.5),
            (Mode::Exclusion, 0.5, 0.5, 0.5),
            (Mode::Exclusion, 0.2, 1.0, 0.8),
        ];
        for (mode, cb, cs, expected) in cases {
            near(expected, channel(mode, cb, cs));
        }
    }

    #[test]
    fn composite_blends_opaque_pixels_and_keeps_transparent_ones() {
        let mut below = [0xFF808080u32 as i32, 0xFF808080u32 as i32, 0x00000000, 0xFF102030u32 as i32];
        let above = [0xFFFF0000u32 as i32, 0xFFFF0000u32 as i32, 0x80FF0000u32 as i32, 0x00FFFFFF];
        let mut half = below;
        composite(&mut below, &above, Mode::Multiply, 1.0);
        assert_eq!(0xFF800000u32 as i32, below[0]);
        assert_eq!(0x80FF0000u32 as i32, below[2]);
        assert_eq!(0xFF102030u32 as i32, below[3]);
        composite(&mut half, &above, Mode::Multiply, 0.5);
        assert_eq!(0xFF804040u32 as i32, half[0]);
    }

    #[test]
    fn normal_mode_is_plain_source_over() {
        let mut below = [0xFF0000FFu32 as i32];
        composite(&mut below, &[0x80FF0000u32 as i32], Mode::Normal, 1.0);
        assert_eq!(0xFF80007Fu32 as i32, below[0]);
    }

    /// The table of opaque pixels gives what the pixel formula gives, for every pair of values, mode and opacity
    /// (more opacities than tables are kept: the oldest ones are made again).
    #[test]
    fn the_opaque_table_agrees_with_the_formula() {
        let pixels: Vec<i32> =
            (0..65536u32).map(|i| (ALPHA_MASK | (i >> 8) << 16 | (i >> 8) << 8 | (i >> 8)) as i32).collect();
        let above: Vec<i32> =
            (0..65536u32).map(|i| (ALPHA_MASK | (i & 255) << 16 | (i & 255) << 8 | (i & 255)) as i32).collect();
        for ordinal in 0..SEPARABLE_COUNT as i32 {
            let mode = Mode::from_ordinal(ordinal).unwrap();
            for gain in [1.0f32, 0.6, 0.35, 0.004] {
                let mut with_table = pixels.clone();
                composite(&mut with_table, &above, mode, gain);
                for (i, (&b, &s)) in pixels.iter().zip(above.iter()).enumerate() {
                    let expected = composite_pixel(b as u32, s as u32, mode, gain).map_or(b, |p| p as i32);
                    assert_eq!(expected, with_table[i], "{mode:?} at {gain}, pixel {i}");
                }
            }
        }
    }

    #[test]
    fn a_channel_value_is_the_division_the_kotlin_code_does() {
        for i in 0..256u32 {
            assert_eq!((i as f32 / 255.0).to_bits(), unit(i).to_bits());
        }
    }

    #[test]
    fn tint_gives_the_colour_and_keeps_luminosity_and_alpha() {
        let mut pixels = [0xFF808080u32 as i32, 0x40808080];
        tint(&mut pixels, 0xFF0000, 1.0);
        assert_eq!(0xFF, (pixels[0] >> 16) & 0xFF);
        assert_eq!(74, (pixels[0] >> 8) & 0xFF);
        assert_eq!(74, pixels[0] & 0xFF);
        assert_eq!(0x40, (pixels[1] as u32) >> 24);
        let mut untouched = [0xFF123456u32 as i32];
        tint(&mut untouched, 0xFF0000, 0.0);
        assert_eq!(0xFF123456u32 as i32, untouched[0]);
    }

    #[test]
    fn adjust_applies_the_colour_matrix() {
        let mut identity = [0.0f32; MATRIX_SIZE];
        for i in 0..4 {
            identity[i * 6] = 1.0;
        }
        let mut pixels = [0xFF336699u32 as i32, 0x80000000u32 as i32];
        let copy = pixels;
        adjust(&mut pixels, &identity);
        assert_eq!(copy, pixels);
    }
}
