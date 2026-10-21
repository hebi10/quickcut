//! HEVC Main/Main10 uses hev1: parameter sets travel with each closed GOP.
//! Only a verified splice's prior-picture discard flag may be cleared.
use anyhow::{ensure, Context, Result};
use ffmpeg_next as ff;
use crate::{decline::{explain, Reason}, display::Display, hevc_splice::Repair, Callbacks};

pub fn validate_configuration(bytes: &[u8]) -> Result<()> {
    ensure!(bytes.len() >= 23 && bytes[0] == 1 && bytes[16] & 3 == 1
        && matches!((bytes[1] & 31, bytes[17] & 7, bytes[18] & 7), (1, 0, 0) | (2, 2, 2)),
        "only HEVC Main 8-bit or Main10 10-bit 4:2:0 configuration is supported");
    let sets = smartcut_core::bitstream::parameter_sets("hevc", bytes);
    for kind in [32, 33, 34] {
        ensure!(sets.iter().filter(|s| s.len() >= 2 && (s[0] >> 1) & 63 == kind).count() == 1,
            "one HEVC VPS, SPS and PPS is required");
    }
    Ok(())
}

pub fn nals(data: &[u8], width: usize) -> Result<Vec<&[u8]>> {
    ensure!((1..=4).contains(&width), "unsupported HEVC length prefix");
    let mut result = Vec::new();
    let mut at = 0;
    while at < data.len() {
        ensure!(data.len() - at >= width, "truncated HEVC length prefix");
        let length = data[at..at + width].iter().fold(0usize, |n, b| (n << 8) | *b as usize);
        at += width;
        ensure!(length >= 2 && length <= data.len() - at, "truncated HEVC NAL");
        let nal = &data[at..at + length];
        let kind = (nal[0] >> 1) & 63;
        ensure!(nal[0] & 0x81 == 0 && nal[1] >> 3 == 0 && nal[1] & 7 > 0
            && matches!(kind, 0..=9 | 16..=21 | 32..=40), "unsupported HEVC NAL/layer");
        result.push(nal);
        at += length;
    }
    ensure!(!result.is_empty(), "empty HEVC packet");
    Ok(result)
}

pub fn remux(input_path: &str, output_path: &str, display: &Display, callbacks: &dyn Callbacks,
    splices: &[i64]) -> Result<u64> {
    let mut input = smartcut_core::input::open_local(input_path)?;
    let video = input.streams().best(ff::media::Type::Video).context("missing video")?.index();
    let parameters = input.stream(video).unwrap().parameters();
    let extradata = unsafe {
        let raw = parameters.as_ptr();
        ensure!(!(*raw).extradata.is_null() && (*raw).extradata_size > 0, "missing HEVC configuration");
        std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize)
    };
    let mut repair = explain(Repair::new(extradata), Reason::UnsafeSplice)?;
    let mut pending = splices.to_vec();
    let mut output = ff::format::output_as(output_path, "mp4")?;
    output.set_metadata(input.metadata().to_owned());
    let mut time_bases = Vec::new();
    for stream in input.streams() {
        time_bases.push(stream.time_base());
        let mut out = output.add_stream(ff::encoder::find(ff::codec::Id::None))?;
        out.set_parameters(stream.parameters());
        out.set_time_base(stream.time_base());
        out.set_metadata(stream.metadata().to_owned());
        unsafe { (*out.parameters().as_mut_ptr()).codec_tag = if stream.index() == video {
            u32::from_le_bytes(*b"hev1")
        } else { 0 }; }
        if stream.index() == video { display.apply(&mut out)?; }
    }
    output.write_header()?;
    let mut count = 0;
    loop {
        ensure!(!callbacks.is_cancelled(), "cancelled");
        let mut packet = ff::Packet::empty();
        match packet.read(&mut input) { Ok(()) => (), Err(ff::Error::Eof) => break, Err(e) => return Err(e.into()) }
        let stream = packet.stream();
        if stream == video {
            let pts = explain(packet.pts().context("HEVC packet has no presentation timestamp"), Reason::Timestamps)?;
            let at = unsafe { ff::ffi::av_rescale_q(pts, time_bases[video].into(), ff::Rational(1, 1_000_000).into()) };
            let matched = pending.iter().position(|&t| t.abs_diff(at) <= 2);
            explain(repair.apply(&mut packet, matched.is_some()), Reason::UnsafeSplice)?;
            if let Some(index) = matched { pending.remove(index); }
        }
        packet.rescale_ts(time_bases[stream], output.stream(stream).unwrap().time_base());
        packet.set_position(-1);
        packet.write_interleaved(&mut output)?;
        if stream == video { count += 1; }
    }
    explain((|| { ensure!(pending.is_empty(), "planned HEVC splice packet was not found"); Ok(()) })(), Reason::UnsafeSplice)?;
    output.write_trailer()?;
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_truncated_and_multilayer_packets() {
        assert!(nals(&[0, 0, 0, 2, 40, 1], 4).is_ok());
        for data in [&[0, 0, 0, 3, 40, 1][..], &[0, 0, 0, 2, 41, 1],
            &[0, 0, 0, 2, 40, 8], &[0, 0, 0, 2, 40, 0]] {
            assert!(nals(data, 4).is_err());
        }
        assert!(validate_configuration(&[0; 23]).is_err());
    }
}
