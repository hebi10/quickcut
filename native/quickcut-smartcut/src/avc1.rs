//! A fixed AVC configuration for Android's avc1-only MP4 extractor.
//!
//! x264 assigns each encoded boundary fresh parameter-set IDs before encoding.
//! This pass gathers every set into avcC and removes in-band sets. It never
//! rewrites a coded picture, including CABAC data or a copied slice header.
use anyhow::{bail, ensure, Context, Result};
use ffmpeg_next as ff;
use std::collections::{BTreeMap, BTreeSet};
use crate::{avc_nals, Callbacks};

struct Bits { data: Vec<u8>, at: usize }
impl Bits {
    fn new(nal: &[u8], at: usize) -> Self {
        let mut data = Vec::new();
        let mut zeros = 0;
        for &byte in nal {
            if zeros == 2 && byte == 3 { zeros = 0; continue; }
            data.push(byte);
            zeros = if byte == 0 { zeros + 1 } else { 0 };
        }
        Self { data, at }
    }
    fn bit(&mut self) -> Result<u32> {
        ensure!(self.at < self.data.len() * 8, "truncated AVC syntax");
        let value = (self.data[self.at / 8] >> (7 - self.at % 8)) & 1;
        self.at += 1;
        Ok(value as u32)
    }
    fn ue(&mut self) -> Result<u32> {
        let mut zeros = 0;
        while self.bit()? == 0 { zeros += 1; ensure!(zeros < 31, "invalid AVC Exp-Golomb value"); }
        let mut value = 1u32;
        for _ in 0..zeros { value = value * 2 + self.bit()?; }
        Ok(value - 1)
    }
}

fn ids(nal: &[u8]) -> Result<(u32, Option<u32>)> {
    match nal.first().map(|n| n & 31) {
        Some(7) => { let id = Bits::new(nal, 32).ue()?; ensure!(id < 32, "invalid SPS ID"); Ok((id, None)) },
        Some(8) => {
            let mut bits = Bits::new(nal, 8);
            let pps = bits.ue()?; let sps = bits.ue()?;
            ensure!(pps < 256 && sps < 32, "invalid PPS reference"); Ok((pps, Some(sps)))
        },
        _ => bail!("not an AVC parameter set"),
    }
}

fn configuration(input: &ff::format::context::Input, video: usize) -> Result<(usize, Vec<Vec<u8>>)> {
    let stream = input.stream(video).context("missing video stream")?;
    let parameters = stream.parameters();
    let bytes = unsafe {
        let p = parameters.as_ptr();
        ensure!(!(*p).extradata.is_null() && (*p).extradata_size > 0, "missing AVC configuration");
        std::slice::from_raw_parts((*p).extradata, (*p).extradata_size as usize)
    };
    let width = match smartcut_core::bitstream::framing_from_extradata("h264", bytes) {
        smartcut_core::bitstream::NalFraming::Length(n) => n,
        _ => bail!("AVC configuration must declare length-prefixed NALs"),
    };
    // This route handles ordinary 8-bit 4:2:0 AVC only. Explicitly reject
    // auxiliary sequence parameter sets instead of silently dropping them.
    ensure!(bytes.len() >= 7, "truncated avcC");
    let mut at = 6;
    let mut consume = |count: usize| -> Result<()> {
        for _ in 0..count {
            ensure!(at + 2 <= bytes.len(), "truncated avcC parameter set");
            let size = u16::from_be_bytes([bytes[at], bytes[at + 1]]) as usize;
            at += 2;
            ensure!(size > 0 && size <= bytes.len() - at, "truncated avcC parameter payload");
            at += size;
        }
        Ok(())
    };
    consume((bytes[5] & 31) as usize)?;
    drop(consume);
    ensure!(at < bytes.len(), "missing avcC PPS count");
    let count = bytes[at] as usize; at += 1;
    for _ in 0..count {
        ensure!(at + 2 <= bytes.len(), "truncated avcC PPS");
        let size = u16::from_be_bytes([bytes[at], bytes[at + 1]]) as usize;
        at += 2;
        ensure!(size > 0 && size <= bytes.len() - at, "truncated avcC PPS payload");
        at += size;
    }
    if at != bytes.len() {
        ensure!(bytes.len() - at == 4 && bytes[at] & 3 == 1
            && bytes[at + 1] & 7 == 0 && bytes[at + 2] & 7 == 0 && bytes[at + 3] == 0,
            "unsupported AVC configuration extension");
    }
    Ok((width, smartcut_core::bitstream::parameter_sets("h264", bytes)))
}

fn supported_sps_profile(nal: &[u8]) -> bool {
    nal.len() >= 4 && matches!(nal[1], 66 | 77 | 100)
}

pub fn unused_ids(path: &str, count: usize) -> Result<(Vec<u8>, u32)> {
    let input = smartcut_core::input::open_local(path)?;
    let video = input.streams().best(ff::media::Type::Video).context("missing video")?.index();
    let (_, sets) = configuration(&input, video)?;
    ensure!(sets.iter().filter(|s| s[0] & 31 == 7).count() == 1
        && sets.iter().filter(|s| s[0] & 31 == 8).count() == 1,
        "multiple source AVC configurations require the exact fallback");
    ensure!(sets.iter().filter(|s| s[0] & 31 == 7).all(|s| supported_sps_profile(s)),
        "unsupported source AVC profile requires the exact fallback");
    let source_config = crate::avc_sps::DecoderConfig::read(
        sets.iter().find(|s| s[0] & 31 == 7).unwrap())?;
    let mut used = BTreeSet::new();
    let mut sps_count = 0;
    for set in sets {
        let (id, reference) = ids(&set)?;
        used.insert(id);
        if let Some(reference) = reference { used.insert(reference); } else { sps_count += 1; }
    }
    ensure!(sps_count + count <= 31, "too many parameter sets for avcC");
    let available: Vec<u8> = (0..32).filter(|id| !used.contains(id)).take(count).map(|id| id as u8).collect();
    ensure!(available.len() == count, "no unused AVC parameter-set IDs");
    Ok((available, source_config.references))
}

#[derive(Default)]
struct Sets { sps: BTreeMap<u32, Vec<u8>>, pps: BTreeMap<u32, Vec<u8>> }
impl Sets {
    fn add(&mut self, nal: &[u8]) -> Result<()> {
        let (id, reference) = ids(nal)?;
        let map = if reference.is_some() { &mut self.pps } else { &mut self.sps };
        if let Some(existing) = map.get(&id) {
            ensure!(existing == nal, "parameter-set ID reused with a different configuration");
        } else { map.insert(id, nal.to_vec()); }
        Ok(())
    }
    fn avcc(&self) -> Result<Vec<u8>> {
        ensure!(!self.sps.is_empty() && self.sps.len() <= 31 && !self.pps.is_empty() && self.pps.len() <= 255,
            "invalid AVC parameter-set counts");
        for pps in self.pps.values() {
            ensure!(self.sps.contains_key(&ids(pps)?.1.unwrap()), "PPS references an absent SPS");
        }
        // All pictures are 8-bit 4:2:0. Declare the highest actual profile and
        // level, with only constraint flags common to every supplied SPS.
        let mut ordered: Vec<&Vec<u8>> = self.sps.values().collect();
        ensure!(ordered.iter().all(|s| supported_sps_profile(s)), "unsupported AVC profile");
        let expected = crate::avc_sps::DecoderConfig::read(ordered[0])?;
        for sps in &ordered[1..] {
            ensure!(crate::avc_sps::DecoderConfig::read(sps)? == expected,
                "AVC decoder configuration changes across the splice; exact fallback required");
        }
        ordered.sort_by_key(|s| std::cmp::Reverse(s[1]));
        let profile = ordered[0][1];
        let compatibility = ordered.iter().fold(255, |flags, s| flags & s[2]);
        let level = ordered.iter().map(|s| s[3]).max().unwrap();
        let mut out = vec![1, profile, compatibility, level, 0xff, 0xe0 | ordered.len() as u8];
        for sps in ordered { append_set(&mut out, sps)?; }
        out.push(self.pps.len() as u8);
        for pps in self.pps.values() { append_set(&mut out, pps)?; }
        if profile == 100 { out.extend_from_slice(&[0xfd, 0xf8, 0xf8, 0]); }
        Ok(out)
    }
    fn validate_picture(&self, nal: &[u8]) -> Result<()> {
        match nal[0] & 31 {
            1 | 5 => {
                let mut bits = Bits::new(nal, 8);
                bits.ue()?; bits.ue()?;
                ensure!(self.pps.contains_key(&bits.ue()?), "slice references an absent PPS");
            },
            2..=4 => bail!("AVC data partitioning is unsupported"),
            _ => (),
        }
        Ok(())
    }
}

fn append_set(out: &mut Vec<u8>, set: &[u8]) -> Result<()> {
    ensure!(set.len() <= u16::MAX as usize, "oversized AVC parameter set");
    out.extend_from_slice(&(set.len() as u16).to_be_bytes());
    out.extend_from_slice(set);
    Ok(())
}

pub fn remux(input_path: &str, output_path: &str, display: &crate::display::Display, callbacks: &dyn Callbacks,
    frame_rate: f64) -> Result<u64> {
    let mut input = smartcut_core::input::open_local(input_path)?;
    crate::decline::explain(crate::audio_config::validate(&input), crate::decline::Reason::AudioFormat)?;
    let video = input.streams().best(ff::media::Type::Video).context("missing video")?.index();
    let (width, original_sets) = configuration(&input, video)?;
    let mut sets = Sets::default();
    for set in original_sets { sets.add(&set)?; }
    let mut read_count = 0;
    loop {
        if read_count % 64 == 0 { ensure!(!callbacks.is_cancelled(), "cancelled"); }
        let mut packet = ff::Packet::empty();
        match packet.read(&mut input) { Ok(()) => (), Err(ff::Error::Eof) => break, Err(e) => return Err(e.into()) }
        read_count += 1;
        if packet.stream() != video { continue; }
        for nal in avc_nals(packet.data().context("empty AVC packet")?, width)? {
            if matches!(nal[0] & 31, 7 | 8) { sets.add(nal)?; }
        }
    }
    let avcc = sets.avcc()?;
    drop(input);
    let mut input = smartcut_core::input::open_local(input_path)?;
    let mut output = ff::format::output_as(output_path, "mp4")?;
    output.set_metadata(input.metadata().to_owned());
    let mut input_time_bases = Vec::new();
    for stream in input.streams() {
        input_time_bases.push(stream.time_base());
        let mut out = output.add_stream(ff::encoder::find(ff::codec::Id::None))?;
        out.set_parameters(stream.parameters());
        out.set_time_base(stream.time_base());
        out.set_metadata(stream.metadata().to_owned());
        if stream.index() == video { display.apply(&mut out)?; }
        unsafe {
            let p = out.parameters().as_mut_ptr();
            (*p).codec_tag = 0;
            if stream.index() == video {
                ff::ffi::av_freep(std::ptr::addr_of_mut!((*p).extradata).cast());
                let data = ff::ffi::av_mallocz(avcc.len() + ff::ffi::AV_INPUT_BUFFER_PADDING_SIZE as usize) as *mut u8;
                ensure!(!data.is_null(), "could not allocate AVC configuration");
                std::ptr::copy_nonoverlapping(avcc.as_ptr(), data, avcc.len());
                (*p).extradata = data; (*p).extradata_size = avcc.len() as i32;
                (*p).codec_tag = u32::from_le_bytes(*b"avc1");
                (*p).profile = avcc[1] as i32; (*p).level = avcc[3] as i32;
            }
        }
    }
    output.write_header()?;
    let picture_ticks = crate::duration::picture_ticks(frame_rate, output.stream(video).unwrap().time_base())?;
    let mut written = 0;
    let mut video_written = 0u64;
    loop {
        if written % 64 == 0 { ensure!(!callbacks.is_cancelled(), "cancelled"); }
        let mut packet = ff::Packet::empty();
        match packet.read(&mut input) { Ok(()) => (), Err(ff::Error::Eof) => break, Err(e) => return Err(e.into()) }
        let stream = packet.stream();
        if stream == video {
            let mut bytes = Vec::new();
            for nal in avc_nals(packet.data().context("empty AVC packet")?, width)? {
                if matches!(nal[0] & 31, 7 | 8) { continue; }
                sets.validate_picture(nal)?;
                bytes.extend_from_slice(&(nal.len() as u32).to_be_bytes());
                bytes.extend_from_slice(nal);
            }
            ensure!(!bytes.is_empty(), "AVC packet contained only parameter sets");
            let mut stripped = ff::Packet::copy(&bytes);
            stripped.set_stream(stream); stripped.set_flags(packet.flags());
            stripped.set_pts(packet.pts()); stripped.set_dts(packet.dts()); stripped.set_duration(packet.duration());
            packet = stripped;
        }
        packet.rescale_ts(input_time_bases[stream], output.stream(stream).unwrap().time_base());
        if stream == video { packet.set_duration(picture_ticks); }
        packet.set_position(-1);
        packet.write_interleaved(&mut output)?;
        if stream == video { video_written += 1; }
        written += 1;
        callbacks.progress(85 + ((written as f64 / read_count.max(1) as f64) * 14.0).min(14.0) as i32);
    }
    output.write_trailer()?;
    ensure!(!callbacks.is_cancelled(), "cancelled");
    Ok(video_written)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn different_reference_buffers_in_static_avcc_require_exact_fallback() {
        // Actual green5 output: Android libavc retained only the tail when
        // source SPS0 used one reference and encoded SPS1 used three.
        let source = [0x67, 0x42, 0xc0, 0xd, 0xda, 0x5, 0x7, 0xec, 0x4, 0x40,
            0, 0, 3, 0, 0x40, 0, 0, 0xf, 3, 0xc5, 0xa, 0xa8];
        let boundary = [0x67, 0x42, 0xc0, 0xd, 0x56, 0x40, 0x50, 0x7e, 0xc0, 0x44,
            0, 0, 3, 0, 4, 0, 0, 3, 0, 0xf0, 0x3c, 0x50, 0xa9, 0x20];
        assert_eq!(crate::avc_sps::DecoderConfig::read(&source).unwrap().references, 1);
        assert_eq!(crate::avc_sps::DecoderConfig::read(&boundary).unwrap().references, 3);
        let mut sets = Sets::default();
        for nal in [source.as_slice(), boundary.as_slice(), &[0x68, 0xce, 0xf, 0xc8],
            &[0x68, 0x48, 0xb8, 0xcb, 0x20]] { sets.add(nal).unwrap(); }
        assert!(sets.avcc().unwrap_err().to_string().contains("decoder configuration changes"));
        assert!(crate::avc_sps::DecoderConfig::read(&source[..7]).is_err());
    }

    #[test]
    fn extended_profile_is_declined_instead_of_changing_to_high() {
        // Ordinary baseline-compatible syntax is valid under Extended as
        // well; change only profile_idc in the fixture's real SPS.
        let sps = [0x67, 88, 0xc0, 0x0d, 0xda, 0x05, 0x07, 0xec, 0x04, 0x40,
            0, 0, 3, 0, 0x40, 0, 0, 0x0f, 3, 0xc5, 0x0a, 0xa8];
        let pps = [0x68, 0xce, 0x0f, 0xc8];
        let mut sets = Sets::default();
        sets.add(&sps).unwrap(); sets.add(&pps).unwrap();
        assert!(!supported_sps_profile(&sps), "the same profile must be rejected before encoding");
        assert!(sets.avcc().is_err(), "libx264 cannot preserve Extended profile");
    }
    #[test]
    fn conflicting_parameter_sets_and_unknown_references_are_rejected() {
        let mut sets = Sets::default();
        sets.add(&[0x67, 66, 0, 30, 0x80]).unwrap();
        assert!(sets.add(&[0x67, 66, 0, 31, 0x80]).is_err());
        // PPS0 references SPS1, which the configuration does not contain.
        sets.add(&[0x68, 0xa0]).unwrap();
        assert!(sets.avcc().is_err());
    }
}
