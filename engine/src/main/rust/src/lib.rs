//! The native compute engine of ToolMap (ADR-119, docs/specs/native-engine.md).
//!
//! The modules hold plain Rust, tested by `cargo test`; this file is the JNI door, called by
//! `fr.toolmap.engine.NativeBridge`. Rules of the door:
//! - arrays are copied in and out by pieces (`Get…ArrayRegion` / `Set…ArrayRegion`), never pinned: the garbage
//!   collector is never held back, and no piece of memory of the JVM is touched outside those calls;
//! - sizes are checked before anything is read or written: a refusal leaves the caller's arrays as they were, and
//!   the Kotlin code then does the work itself;
//! - a panic never crosses the door: it becomes [PANICKED].

pub mod blend;
pub mod mask;
pub mod simplify;
pub mod tile_png;

use jni_sys::{
    jarray, jboolean, jbooleanArray, jbyte, jbyteArray, jclass, jdouble, jdoubleArray, jfloat, jfloatArray, jint,
    jintArray, jsize, JNIEnv, JNI_TRUE,
};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

/// Raised whenever a function of the door changes its arguments or its results: the Kotlin side refuses a library
/// that does not announce the version it was written for (a stale file left by an earlier build).
const ABI_VERSION: jint = 1;

const OK: jint = 0;
/// The arguments are not ones the engine works on; nothing was written.
const REFUSED: jint = -1;
/// A JNI call failed (its exception is pending) before anything was written.
const JNI_FAILED: jint = -2;
/// The engine panicked before anything was written.
const PANICKED: jint = -3;
/// A failure after a part of the result was written: the caller's array is no longer usable.
const BROKEN: jint = -4;

/// Pixels handled per piece: 256 KB per array, a few hundred microseconds of work.
const PIECE: usize = 64 * 1024;

/// The JNI environment of the calling thread, behind the few calls the engine needs.
#[derive(Clone, Copy)]
struct Env(*mut JNIEnv);

macro_rules! jni {
    ($env:expr, $name:ident $(, $arg:expr)*) => {{
        let env: *mut JNIEnv = $env.0;
        // The table of a live JNI environment has every function of its version: a missing one is a broken JVM.
        ((**env).$name.expect(concat!("JNI function ", stringify!($name))))(env $(, $arg)*)
    }};
}

impl Env {
    /// The length of `array`, or `None` for a null reference.
    unsafe fn len(self, array: jarray) -> Option<usize> {
        if array.is_null() {
            return None;
        }
        usize::try_from(jni!(self, GetArrayLength, array)).ok()
    }

    unsafe fn pending(self) -> bool {
        jni!(self, ExceptionCheck) == JNI_TRUE
    }

    unsafe fn get_ints(self, array: jintArray, start: usize, out: &mut [i32]) -> bool {
        jni!(self, GetIntArrayRegion, array, start as jsize, out.len() as jsize, out.as_mut_ptr());
        !self.pending()
    }

    unsafe fn set_ints(self, array: jintArray, start: usize, values: &[i32]) -> bool {
        jni!(self, SetIntArrayRegion, array, start as jsize, values.len() as jsize, values.as_ptr());
        !self.pending()
    }

    unsafe fn get_floats(self, array: jfloatArray, out: &mut [f32]) -> bool {
        jni!(self, GetFloatArrayRegion, array, 0, out.len() as jsize, out.as_mut_ptr());
        !self.pending()
    }

    unsafe fn get_doubles(self, array: jdoubleArray, out: &mut [f64]) -> bool {
        jni!(self, GetDoubleArrayRegion, array, 0, out.len() as jsize, out.as_mut_ptr());
        !self.pending()
    }

    unsafe fn get_bytes(self, array: jbyteArray, start: usize, out: &mut [u8]) -> bool {
        jni!(self, GetByteArrayRegion, array, start as jsize, out.len() as jsize, out.as_mut_ptr() as *mut jbyte);
        !self.pending()
    }

    /// A new `byte[]` holding `values`, or null (an exception is then pending: out of memory).
    unsafe fn new_bytes(self, values: &[u8]) -> jbyteArray {
        let Ok(len) = jsize::try_from(values.len()) else { return ptr::null_mut() };
        let array = jni!(self, NewByteArray, len);
        if array.is_null() {
            return ptr::null_mut();
        }
        jni!(self, SetByteArrayRegion, array, 0, len, values.as_ptr() as *const jbyte);
        if self.pending() {
            ptr::null_mut()
        } else {
            array
        }
    }

    /// A new `boolean[]` holding `values`, or null (an exception is then pending).
    unsafe fn new_booleans(self, values: &[bool]) -> jbooleanArray {
        let Ok(len) = jsize::try_from(values.len()) else { return ptr::null_mut() };
        let array = jni!(self, NewBooleanArray, len);
        if array.is_null() {
            return ptr::null_mut();
        }
        // A Rust `bool` is one byte, 0 or 1: what a `jboolean` is.
        jni!(self, SetBooleanArrayRegion, array, 0, len, values.as_ptr() as *const jboolean);
        if self.pending() {
            ptr::null_mut()
        } else {
            array
        }
    }
}

/// Runs `work` and gives [PANICKED] if it panics.
fn guarded(work: impl FnOnce() -> jint) -> jint {
    catch_unwind(AssertUnwindSafe(work)).unwrap_or(PANICKED)
}

/// Runs `work` and gives null if it panics.
fn guarded_array(work: impl FnOnce() -> jarray) -> jarray {
    catch_unwind(AssertUnwindSafe(work)).unwrap_or(ptr::null_mut())
}

/// Reads `pixels` piece by piece, lets `work` change each piece, and writes it back. `work` gives false when it could
/// not do its piece (a JNI call of its own failed): nothing more is asked of the JVM then, as its rules demand
/// while an exception is pending.
unsafe fn in_place(env: Env, pixels: jintArray, mut work: impl FnMut(usize, &mut [i32]) -> bool) -> jint {
    let Some(total) = env.len(pixels) else { return REFUSED };
    let mut piece = vec![0i32; total.min(PIECE)];
    let mut start = 0;
    while start < total {
        let n = (total - start).min(PIECE);
        // Before the first piece is written back the caller's array is whole; after it, a failure leaves it broken.
        let failure = if start == 0 { JNI_FAILED } else { BROKEN };
        if !env.get_ints(pixels, start, &mut piece[..n]) || !work(start, &mut piece[..n]) {
            return failure;
        }
        if !env.set_ints(pixels, start, &piece[..n]) {
            return BROKEN;
        }
        start += n;
    }
    OK
}

/// The version of this door (`NativeBridge.ABI_VERSION` on the Kotlin side).
#[no_mangle]
pub extern "system" fn Java_fr_toolmap_engine_NativeBridge_abiVersion(_env: *mut JNIEnv, _class: jclass) -> jint {
    ABI_VERSION
}

/// `Blend.composite`: `above` drawn over `below`, in place in `below`.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_composite(
    env: *mut JNIEnv,
    _class: jclass,
    below: jintArray,
    above: jintArray,
    mode: jint,
    opacity: jfloat,
) -> jint {
    guarded(|| {
        let env = Env(env);
        let Some(mode) = blend::Mode::from_ordinal(mode) else { return REFUSED };
        let (Some(total), Some(above_len)) = (env.len(below), env.len(above)) else { return REFUSED };
        if total != above_len {
            return REFUSED;
        }
        let mut source = vec![0i32; total.min(PIECE)];
        in_place(env, below, |start, piece| {
            let n = piece.len();
            if !env.get_ints(above, start, &mut source[..n]) {
                return false;
            }
            blend::composite_part(piece, &source[..n], mode, opacity, total);
            true
        })
    })
}

/// `Blend.tint`: the hue and saturation of `tint` given to `pixels`, in place.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_tint(
    env: *mut JNIEnv,
    _class: jclass,
    pixels: jintArray,
    tint: jint,
    intensity: jfloat,
) -> jint {
    guarded(|| {
        in_place(Env(env), pixels, |_, piece| {
            blend::tint(piece, tint, intensity);
            true
        })
    })
}

/// `Blend.adjust`: a 4 × 5 colour matrix applied to `pixels`, in place.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_adjust(
    env: *mut JNIEnv,
    _class: jclass,
    pixels: jintArray,
    matrix: jfloatArray,
) -> jint {
    guarded(|| {
        let env = Env(env);
        if env.len(matrix) != Some(blend::MATRIX_SIZE) {
            return REFUSED;
        }
        let mut values = [0f32; blend::MATRIX_SIZE];
        if !env.get_floats(matrix, &mut values) {
            return JNI_FAILED;
        }
        in_place(env, pixels, |_, piece| {
            blend::adjust(piece, &values);
            true
        })
    })
}

/// `MaskRaster.alphas(MaskRaster.elevations(…))`: the opacity of each pixel of a tile for an elevation band.
/// `low` and `high` are the bounds in metres, NaN for none. Null when the arguments are refused.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_maskAlphas(
    env: *mut JNIEnv,
    _class: jclass,
    dem: jfloatArray,
    dem_size: jint,
    depth: jint,
    col: jint,
    row: jint,
    size: jint,
    low: jfloat,
    high: jfloat,
) -> jbyteArray {
    guarded_array(|| {
        let env = Env(env);
        let (Ok(dem_size), Ok(size)) = (usize::try_from(dem_size), usize::try_from(size)) else {
            return ptr::null_mut();
        };
        let sides_ok = (1..=mask::MAX_SIDE).contains(&dem_size) && (1..=mask::MAX_SIDE).contains(&size);
        if !sides_ok || !(0..=mask::MAX_DEPTH).contains(&depth) || env.len(dem) != Some(dem_size * dem_size) {
            return ptr::null_mut();
        }
        let mut elevations = vec![0f32; dem_size * dem_size];
        if !env.get_floats(dem, &mut elevations) {
            return ptr::null_mut();
        }
        let bound = |v: jfloat| if v.is_nan() { None } else { Some(v) };
        let heights = mask::elevations(&elevations, dem_size, depth, col, row, size);
        env.new_bytes(&mask::alphas(&heights, size, bound(low), bound(high)))
    })
}

/// The opacity of each pixel of `pixels` scaled by its mask value in `alphas`, in place.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_applyAlphas(
    env: *mut JNIEnv,
    _class: jclass,
    pixels: jintArray,
    alphas: jbyteArray,
) -> jint {
    guarded(|| {
        let env = Env(env);
        let (Some(total), Some(mask_len)) = (env.len(pixels), env.len(alphas)) else { return REFUSED };
        if total != mask_len {
            return REFUSED;
        }
        let mut values = vec![0u8; total.min(PIECE)];
        in_place(env, pixels, |start, piece| {
            let n = piece.len();
            if !env.get_bytes(alphas, start, &mut values[..n]) {
                return false;
            }
            mask::apply(piece, &values[..n]);
            true
        })
    })
}

/// The PNG of `pixels` (`width` × `height` ARGB ints with straight colours). `effort`: 0 fastest, 1 smaller.
/// Null when the arguments are refused or the image cannot be written.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_encodePng(
    env: *mut JNIEnv,
    _class: jclass,
    pixels: jintArray,
    width: jint,
    height: jint,
    effort: jint,
) -> jbyteArray {
    guarded_array(|| {
        let env = Env(env);
        let (Ok(w), Ok(h)) = (u32::try_from(width), u32::try_from(height)) else { return ptr::null_mut() };
        let Some(count) = (w as usize).checked_mul(h as usize) else { return ptr::null_mut() };
        if w == 0 || h == 0 || count > tile_png::MAX_PIXELS || env.len(pixels) != Some(count) {
            return ptr::null_mut();
        }
        let mut argb = vec![0i32; count];
        if !env.get_ints(pixels, 0, &mut argb) {
            return ptr::null_mut();
        }
        let effort = if effort == 1 { tile_png::Effort::Fast } else { tile_png::Effort::Fastest };
        match tile_png::encode(&argb, w, h, effort) {
            Ok(bytes) => env.new_bytes(&bytes),
            Err(_) => ptr::null_mut(),
        }
    })
}

/// `PathSimplifier.keptIndices`: Douglas-Peucker on flat coordinates. Null when the arguments are refused.
///
/// # Safety
/// Called by the JVM with its own environment and array references.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_toolmap_engine_NativeBridge_keptIndices(
    env: *mut JNIEnv,
    _class: jclass,
    xs: jdoubleArray,
    ys: jdoubleArray,
    tolerance: jdouble,
) -> jbooleanArray {
    guarded_array(|| {
        let env = Env(env);
        let (Some(n), Some(m)) = (env.len(xs), env.len(ys)) else { return ptr::null_mut() };
        if n != m {
            return ptr::null_mut();
        }
        let mut px = vec![0f64; n];
        let mut py = vec![0f64; n];
        if !env.get_doubles(xs, &mut px) || !env.get_doubles(ys, &mut py) {
            return ptr::null_mut();
        }
        env.new_booleans(&simplify::kept_indices(&px, &py, tolerance))
    })
}
