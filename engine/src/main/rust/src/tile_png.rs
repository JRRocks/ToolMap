//! The PNG of a tile drawn here (blended, cut out along a contour, or made of other tiles).
//!
//! Such a tile is given to the map and never stored, so the time to write it counts and its size hardly does: rows
//! are filtered « Up » and compressed by `fdeflate`, a DEFLATE made for filtered image rows. Android's own PNG
//! writer (`Bitmap.compress`: every filter tried on every row, zlib at level 6) was the heaviest step of a drawn
//! tile (docs/specs/elevation-maps.md § 5, blend-modes.md § 5).

use png::{BitDepth, ColorType, Compression, Encoder, EncodingError};

/// More pixels than any tile has (2048 × 2048; tiles are 256 to 1024 px): the caller's array is refused beyond it
/// (`NativeEngine.MAX_PNG_PIXELS` on the Kotlin side, which does not even ask).
pub const MAX_PIXELS: usize = 2048 * 2048;

/// How much time is spent to make the file smaller.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Effort {
    /// Rows filtered « Up », then `fdeflate`: the fastest that still compresses (the choice for tiles).
    Fastest,
    /// The filter chosen row by row, then `fdeflate`: smaller files, more time.
    Fast,
}

/// The PNG of `argb` (ARGB ints with straight colours, `width` × `height`, row after row). An image without any
/// transparency is written without an alpha channel; a fully transparent pixel is written black, as Android does
/// (its hidden colour would only weigh on the compression).
pub fn encode(argb: &[i32], width: u32, height: u32, effort: Effort) -> Result<Vec<u8>, EncodingError> {
    let opaque = argb.iter().all(|&p| (p as u32) >> 24 == 0xFF);
    let channels = if opaque { 3 } else { 4 };
    let mut data = Vec::with_capacity(argb.len() * channels);
    if opaque {
        for &p in argb {
            let p = p as u32;
            data.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, p as u8]);
        }
    } else {
        for &p in argb {
            let p = p as u32;
            let a = (p >> 24) as u8;
            if a == 0 {
                data.extend_from_slice(&[0, 0, 0, 0]);
            } else {
                data.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, p as u8, a]);
            }
        }
    }
    // About a third of the raw size for map tiles; the vector grows if a tile compresses badly.
    let mut out = Vec::with_capacity(data.len() / 3 + 1024);
    {
        let mut encoder = Encoder::new(&mut out, width, height);
        encoder.set_color(if opaque { ColorType::Rgb } else { ColorType::Rgba });
        encoder.set_depth(BitDepth::Eight);
        encoder.set_compression(match effort {
            Effort::Fastest => Compression::Fastest,
            Effort::Fast => Compression::Fast,
        });
        let mut writer = encoder.write_header()?;
        writer.write_image_data(&data)?;
        writer.finish()?;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode(png_bytes: &[u8]) -> (png::OutputInfo, Vec<u8>) {
        let mut reader = png::Decoder::new(std::io::Cursor::new(png_bytes)).read_info().unwrap();
        let mut buffer = vec![0; reader.output_buffer_size().unwrap()];
        let info = reader.next_frame(&mut buffer).unwrap();
        buffer.truncate(info.buffer_size());
        (info, buffer)
    }

    #[test]
    fn an_opaque_image_is_written_without_alpha_and_read_back_as_it_was() {
        let pixels: Vec<i32> =
            (0..64u32 * 64).map(|i| (0xFF000000u32 | i.wrapping_mul(2654435761) >> 8) as i32).collect();
        for effort in [Effort::Fastest, Effort::Fast] {
            let (info, data) = decode(&encode(&pixels, 64, 64, effort).unwrap());
            assert_eq!(ColorType::Rgb, info.color_type);
            assert_eq!((64, 64), (info.width, info.height));
            for (i, &p) in pixels.iter().enumerate() {
                let p = p as u32;
                assert_eq!([(p >> 16) as u8, (p >> 8) as u8, p as u8], data[i * 3..i * 3 + 3]);
            }
        }
    }

    #[test]
    fn transparency_is_kept_and_hidden_colours_are_dropped() {
        let pixels = [0xFF102030u32 as i32, 0x80405060u32 as i32, 0x00FFFFFF, 0x01000000];
        let (info, data) = decode(&encode(&pixels, 2, 2, Effort::Fastest).unwrap());
        assert_eq!(ColorType::Rgba, info.color_type);
        assert_eq!(vec![0x10, 0x20, 0x30, 0xFF, 0x40, 0x50, 0x60, 0x80, 0, 0, 0, 0, 0, 0, 0, 1], data);
    }
}
