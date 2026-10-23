//! The final Android MP4 must describe a supported AAC channel arrangement.
//! A nonstandard PCE can decode in FFmpeg yet fail Android's ConfigRaw setup.
use anyhow::{ensure, Result};
use ffmpeg_next as ff;

fn configuration(bytes: &[u8], channels: i32, sample_rate: i32) -> Result<()> {
    ensure!(bytes.len() >= 2 && bytes[0] >> 3 == 2, "missing AAC-LC configuration");
    let frequency = ((bytes[0] & 7) << 1 | bytes[1] >> 7) as usize;
    let rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350];
    ensure!(rates.get(frequency) == Some(&sample_rate), "AAC sample-rate configuration mismatch");
    let arrangement = ((bytes[1] >> 3) & 15) as usize;
    let counts = [0, 1, 2, 3, 4, 5, 6, 8];
    ensure!(arrangement > 0 && counts.get(arrangement) == Some(&channels),
        "nonstandard or mismatched AAC channel configuration");
    ensure!(bytes[1] & 7 == 0, "unsupported AAC frame configuration");
    Ok(())
}

fn validate_parameters(parameters: &ff::codec::Parameters) -> Result<()> {
    ensure!(parameters.id() == ff::codec::Id::AAC, "expected AAC output");
    unsafe {
        let raw = parameters.as_ptr();
        ensure!(!(*raw).extradata.is_null() && (*raw).extradata_size >= 2,
            "missing AAC output configuration");
        configuration(std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize),
            (*raw).ch_layout.nb_channels, (*raw).sample_rate)?;
    }
    Ok(())
}

pub(crate) fn validate(input: &ff::format::context::Input) -> Result<()> {
    for stream in input.streams() {
        let parameters = stream.parameters();
        if parameters.medium() == ff::media::Type::Audio { validate_parameters(&parameters)?; }
    }
    Ok(())
}

/// Ask the same encoder used by export for its output configuration. Source
/// channel counts alone cannot distinguish standard AAC from a PCE layout
/// that Android refuses. Muted exports have no output audio to validate.
pub(crate) fn validate_export(source: &smartcut_core::Source, muted: bool) -> Result<()> {
    if muted || source.audios.is_empty() { return Ok(()); }
    let input = smartcut_core::input::open_local(&source.path)?;
    for audio in &source.audios {
        let parameters = input.stream(audio.stream_index)
            .ok_or_else(|| anyhow::anyhow!("missing audio stream"))?.parameters();
        let derived_rate = match audio.channels { 0 | 1 => 96_000, 2 => 192_000, n => 64_000 * n as usize };
        let bit_rate = if parameters.id() == ff::codec::Id::AAC {
            audio.bit_rate.unwrap_or(derived_rate.max(192_000))
        } else { derived_rate };
        let encoder = smartcut_core::audio::Reencoder::new(parameters, ff::codec::Id::AAC,
            None, audio, audio.channels, audio.sample_rate, bit_rate, None)?;
        validate_parameters(&encoder.parameters())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn standard_surround_configs_preserve_six_and_eight_channels() {
        // Actual FFmpeg AAC-LC AudioSpecificConfig prefixes at 48 kHz.
        configuration(&[0x11, 0xb0, 0x56, 0xe5, 0x00], 6, 48000).unwrap();
        configuration(&[0x11, 0xb8, 0x56, 0xe5, 0x00], 8, 48000).unwrap();
        configuration(&[0x12, 0x10, 0x56, 0xe5, 0x00], 2, 44100).unwrap();
    }
    #[test]
    fn pce_that_failed_android_setup_retains_the_audio_reason() {
        let result = crate::decline::explain(configuration(&[0x11, 0x80, 0x04], 6, 48000),
            crate::decline::Reason::AudioFormat);
        assert_eq!(crate::decline::code(&result.unwrap_err()), 8);
    }
    #[test]
    fn incomplete_and_conflicting_headers_are_declined() {
        for bytes in [&[0x11][..], &[0x11, 0xb0], &[0x11, 0xf8], &[0x11, 0x94], &[0x29, 0x90]] {
            assert!(configuration(bytes, 2, 48000).is_err());
        }
        assert!(configuration(&[0x11, 0x90], 2, 44100).is_err());
    }
}
