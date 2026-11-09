//! Douglas-Peucker on flat coordinates: the port of `fr.toolmap.core.track.PathSimplifier.keptIndices`.
//!
//! Same comparisons and the same double-precision operations as the Kotlin code, so the same points are kept.

/// Kotlin's `Double.coerceIn(0.0, 1.0)`: a NaN stays a NaN. Written out, as the Kotlin code compares.
#[inline(always)]
#[allow(clippy::manual_clamp)]
fn coerce_unit(v: f64) -> f64 {
    if v < 0.0 {
        0.0
    } else if v > 1.0 {
        1.0
    } else {
        v
    }
}

#[inline(always)]
fn distance_to_segment(px: f64, py: f64, ax: f64, ay: f64, bx: f64, by: f64) -> f64 {
    let dx = bx - ax;
    let dy = by - ay;
    let length_squared = dx * dx + dy * dy;
    if length_squared == 0.0 {
        return ((px - ax) * (px - ax) + (py - ay) * (py - ay)).sqrt();
    }
    let t = coerce_unit(((px - ax) * dx + (py - ay) * dy) / length_squared);
    let cx = ax + t * dx;
    let cy = ay + t * dy;
    ((px - cx) * (px - cx) + (py - cy) * (py - cy)).sqrt()
}

/// True for the points to keep (always the first and the last): every point farther than `tolerance` from the
/// line through the points kept around it. Iterative, so a long path cannot overflow the stack.
pub fn kept_indices(xs: &[f64], ys: &[f64], tolerance: f64) -> Vec<bool> {
    debug_assert_eq!(xs.len(), ys.len());
    let n = xs.len();
    let mut keep = vec![false; n];
    if n == 0 {
        return keep;
    }
    keep[0] = true;
    keep[n - 1] = true;
    let mut stack: Vec<(usize, usize)> = vec![(0, n - 1)];
    while let Some((first, last)) = stack.pop() {
        let mut farthest = usize::MAX;
        let mut farthest_distance = tolerance;
        let (ax, ay, bx, by) = (xs[first], ys[first], xs[last], ys[last]);
        for i in first + 1..last {
            let d = distance_to_segment(xs[i], ys[i], ax, ay, bx, by);
            if d > farthest_distance {
                farthest_distance = d;
                farthest = i;
            }
        }
        if farthest != usize::MAX {
            keep[farthest] = true;
            stack.push((first, farthest));
            stack.push((farthest, last));
        }
    }
    keep
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_straight_line_keeps_its_ends_only() {
        let xs: Vec<f64> = (0..100).map(|i| i as f64).collect();
        let ys = vec![0.0; 100];
        let keep = kept_indices(&xs, &ys, 0.5);
        assert_eq!(2, keep.iter().filter(|&&k| k).count());
        assert!(keep[0] && keep[99]);
    }

    #[test]
    fn a_point_that_sticks_out_is_kept() {
        let keep = kept_indices(&[0.0, 5.0, 10.0], &[0.0, 3.0, 0.0], 1.0);
        assert_eq!(vec![true, true, true], keep);
        let flat = kept_indices(&[0.0, 5.0, 10.0], &[0.0, 0.5, 0.0], 1.0);
        assert_eq!(vec![true, false, true], flat);
    }

    #[test]
    fn nothing_and_one_point() {
        assert!(kept_indices(&[], &[], 1.0).is_empty());
        assert_eq!(vec![true], kept_indices(&[1.0], &[2.0], 1.0));
    }
}
