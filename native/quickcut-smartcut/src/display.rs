//! Preserve ordinary phone orientation without rotating/reencoding pixels.
use anyhow::{ensure, Context, Result};
use ffmpeg_next as ff;

#[derive(Default)]
pub struct Display(pub Option<Vec<u8>>);

fn validate(bytes: &[u8]) -> Result<()> {
    ensure!(bytes.len() == 36, "invalid display matrix size");
    let m: Vec<i32> = bytes.chunks_exact(4)
        .map(|b| i32::from_ne_bytes(b.try_into().unwrap())).collect();
    let basis = [m[0], m[1], m[3], m[4]];
    ensure!([[65536, 0, 0, 65536], [0, -65536, 65536, 0],
        [-65536, 0, 0, -65536], [0, 65536, -65536, 0]].contains(&basis)
        && [m[2], m[5], m[6], m[7]] == [0; 4] && m[8] == 1 << 30,
        "only orthogonal rotation without reflection, scaling or translation is supported");
    Ok(())
}

impl Display {
    pub fn read(path: &str) -> Result<Self> {
        let input = smartcut_core::input::open_local(path)?;
        let stream = input.streams().best(ff::media::Type::Video).context("missing video")?;
        ensure!(stream.metadata().get("rotate").is_none(), "unsupported legacy rotation metadata");
        let matrices: Vec<_> = stream.side_data()
            .filter(|d| d.kind() == ff::codec::packet::side_data::Type::DisplayMatrix)
            .map(|d| d.data().to_vec()).collect();
        ensure!(matrices.len() <= 1, "ambiguous display transform");
        if let Some(matrix) = matrices.first() { validate(matrix)?; }
        Ok(Self(matrices.into_iter().next()))
    }

    pub fn apply(&self, stream: &mut ff::format::stream::StreamMut<'_>) -> Result<()> {
        if let Some(bytes) = &self.0 {
            unsafe {
                let data = ff::ffi::av_stream_new_side_data(stream.as_mut_ptr(),
                    ff::ffi::AVPacketSideDataType::AV_PKT_DATA_DISPLAYMATRIX, bytes.len());
                ensure!(!data.is_null(), "could not allocate display matrix");
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), data, bytes.len());
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn bytes(m: [i32; 9]) -> Vec<u8> { m.into_iter().flat_map(i32::to_ne_bytes).collect() }
    #[test]
    fn accepts_quarter_turns_but_rejects_other_transforms() {
        for basis in [[65536, 0, 0, 65536], [0, -65536, 65536, 0],
            [-65536, 0, 0, -65536], [0, 65536, -65536, 0]] {
            assert!(validate(&bytes([basis[0], basis[1], 0, basis[2], basis[3], 0, 0, 0, 1 << 30])).is_ok());
        }
        for m in [[-65536, 0, 0, 0, 65536, 0, 0, 0, 1 << 30],
            [65536, 0, 0, 0, 65536, 0, 100, 0, 1 << 30],
            [65536, 0, 0, 0, 32768, 0, 0, 0, 1 << 30]] {
            assert!(validate(&bytes(m)).is_err());
        }
        assert!(validate(&[0; 35]).is_err());
    }
}
