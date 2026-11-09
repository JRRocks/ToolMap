//! Elevation masks of the layers (P20): the port of `fr.toolmap.core.elevation.MaskRaster`.
//!
//! Same operations in the same order as the Kotlin code (double precision for the place of each pixel in the
//! elevation tile, single precision for the elevations), so the opacities are the same bit for bit.

use crate::blend::{coerce_unit, jmax, jmin};

const MIN_STEP: f32 = 0.01;
const OPAQUE: f32 = 255.0;

/// More than any tile measures (256 or 512 px; the Kotlin side refuses larger ones before it comes here).
pub const MAX_SIDE: usize = 4096;

/// More zoom levels below the elevation tile than any map has (`DemWindow.depth` is at most 30 - 13).
pub const MAX_DEPTH: i32 = 30;

#[inline(always)]
fn coerce(v: i32, last: i32) -> usize {
    v.clamp(0, last) as usize
}

/// The elevation of each pixel of a `size` × `size` tile under the cell (`col`, `row`) of a 2^`depth` grid of the
/// elevation tile `dem` (`dem_size` × `dem_size` metres, row after row), interpolated between the four nearest
/// pixel centres; beyond the edge of the elevation tile its border pixels are repeated.
pub fn elevations(dem: &[f32], dem_size: usize, depth: i32, col: i32, row: i32, size: usize) -> Vec<f32> {
    let side = (1i32 << depth) as f64;
    let dem_side = dem_size as i32;
    let place = |cell: i32, i: usize| (cell as f64 + (i as f64 + 0.5) / size as f64) / side * dem_side as f64 - 0.5;
    let xs: Vec<f64> = (0..size).map(|i| place(col, i)).collect();
    let last = dem_side - 1;
    let mut out = vec![0f32; size * size];
    for (j, line) in out.chunks_exact_mut(size).enumerate() {
        let py = place(row, j);
        let y0 = py.floor() as i32;
        let ty = (py - y0 as f64) as f32;
        let r0 = coerce(y0, last) * dem_size;
        let r1 = coerce(y0.saturating_add(1), last) * dem_size;
        for (value, &px) in line.iter_mut().zip(xs.iter()) {
            let x0 = px.floor() as i32;
            let tx = (px - x0 as f64) as f32;
            let c0 = coerce(x0, last);
            let c1 = coerce(x0.saturating_add(1), last);
            let top = dem[r0 + c0] + (dem[r0 + c1] - dem[r0 + c0]) * tx;
            let bottom = dem[r1 + c0] + (dem[r1 + c1] - dem[r1 + c0]) * tx;
            *value = top + (bottom - top) * ty;
        }
    }
    out
}

/// The opacity (0..255) of each pixel of a `size` × `size` grid of `elevations` for the band `low`..`high` metres
/// (`None`: unbounded): 255 inside, 0 outside, and a one-pixel soft edge along the contour (the local slope says how
/// many metres a pixel spans).
pub fn alphas(elevations: &[f32], size: usize, low: Option<f32>, high: Option<f32>) -> Vec<u8> {
    let mut out = vec![0u8; size * size];
    let last = size - 1;
    for j in 0..size {
        let up = j.saturating_sub(1) * size;
        let down = (j + 1).min(last) * size;
        let line = j * size;
        for i in 0..size {
            let e = elevations[line + i];
            // Metres per pixel along the steeper axis (central differences), never zero.
            let gx = (elevations[line + (i + 1).min(last)] - elevations[line + i.saturating_sub(1)]).abs() * 0.5;
            let gy = (elevations[down + i] - elevations[up + i]).abs() * 0.5;
            let step = jmax(jmax(gx, gy), MIN_STEP);
            let mut a = 1.0f32;
            if let Some(low) = low {
                a = jmin(a, coerce_unit((e - low) / step + 0.5));
            }
            if let Some(high) = high {
                a = jmin(a, coerce_unit((high - e) / step + 0.5));
            }
            // Kotlin: `(a * 255f + 0.5f).toInt().toByte()`.
            out[line + i] = ((a * OPAQUE + 0.5) as i32) as u8;
        }
    }
    out
}

/// Scales the opacity of each pixel (ARGB) by its mask value, in place: colours stay, as `getPixels` gives them.
pub fn apply(pixels: &mut [i32], alphas: &[u8]) {
    for (p, &mask) in pixels.iter_mut().zip(alphas.iter()) {
        let pu = *p as u32;
        let a = (pu >> 24) * mask as u32 / 255;
        *p = ((pu & 0x00FF_FFFF) | (a << 24)) as i32;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 256 px elevation tile rising from 1000 m (west edge) to 2020 m (east edge), 4 m per pixel.
    fn ramp() -> Vec<f32> {
        (0..256 * 256).map(|i| 1000.0 + (i % 256) as f32 * 4.0).collect()
    }

    #[test]
    fn a_tile_as_fine_as_the_elevations_reads_them_as_they_are() {
        let dem = ramp();
        let out = elevations(&dem, 256, 0, 0, 0, 256);
        assert_eq!(dem, out);
    }

    #[test]
    fn the_band_is_opaque_inside_and_transparent_outside() {
        let el = elevations(&ramp(), 256, 0, 0, 0, 256);
        let above = alphas(&el, 256, Some(1500.0), None);
        assert_eq!(0, above[128 * 256 + 10]); // 1040 m
        assert_eq!(0, above[128 * 256 + 120]); // 1480 m
        assert_eq!(255, above[128 * 256 + 130]); // 1520 m
        let below = alphas(&el, 256, None, Some(1500.0));
        assert_eq!(255, below[128 * 256 + 10]);
        assert_eq!(0, below[128 * 256 + 250]);
    }

    #[test]
    fn the_mask_scales_the_opacity_and_keeps_the_colour() {
        let mut pixels = [0xFF112233u32 as i32, 0x80112233u32 as i32, 0xFF112233u32 as i32];
        apply(&mut pixels, &[255, 128, 0]);
        assert_eq!(0xFF112233u32 as i32, pixels[0]);
        assert_eq!(0x40112233, pixels[1]);
        assert_eq!(0x00112233, pixels[2]);
    }
}
