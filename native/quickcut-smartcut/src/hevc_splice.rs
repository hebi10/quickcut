//! Preserve delayed pictures when an IDR is reused at a new splice.
use anyhow::{ensure, Context, Result};
use ffmpeg_next as ff;
use ff::codec::packet::Mut;
use std::collections::BTreeMap;

struct Bits<'a> { data: &'a [u8], at: usize }
impl<'a> Bits<'a> {
    fn new(data: &'a [u8]) -> Self { Self { data, at: 0 } }
    fn skip(&mut self, count: usize) -> Result<()> {
        let end = self.at.checked_add(count).context("invalid HEVC syntax length")?;
        let available = self.data.len().checked_mul(8).context("HEVC syntax is too large")?;
        ensure!(end <= available, "truncated HEVC syntax");
        self.at = end;
        Ok(())
    }
    fn u(&mut self, count: usize) -> Result<u32> {
        ensure!(count <= 32, "invalid HEVC integer width");
        let start = self.at;
        self.skip(count)?;
        let mut value = 0;
        for bit in start..self.at {
            value = (value << 1) | u32::from((self.data[bit / 8] >> (7 - bit % 8)) & 1);
        }
        Ok(value)
    }
    fn ue(&mut self, maximum: u32) -> Result<u32> {
        let mut zeros = 0;
        while self.u(1)? == 0 {
            zeros += 1;
            ensure!(zeros < 32 && (1u64 << zeros) - 1 <= u64::from(maximum),
                "HEVC integer exceeds its allowed range");
        }
        let value = ((1u32 << zeros) - 1) + self.u(zeros)?;
        ensure!(value <= maximum, "HEVC integer exceeds its allowed range");
        Ok(value)
    }
}

fn kind(nal: &[u8]) -> Result<u8> {
    ensure!(nal.len() >= 2, "truncated HEVC NAL header");
    ensure!(nal[0] & 0x81 == 0 && nal[1] >> 3 == 0 && nal[1] & 7 != 0,
        "unsupported HEVC NAL/layer");
    Ok((nal[0] >> 1) & 63)
}

fn rbsp(nal: &[u8]) -> Result<Vec<u8>> {
    kind(nal)?;
    let mut data = Vec::with_capacity(nal.len() - 2);
    let mut zeros = 0;
    let mut at = 2;
    while at < nal.len() {
        let byte = nal[at];
        if zeros == 2 {
            if byte == 3 {
                ensure!(nal.get(at + 1).is_some_and(|next| *next <= 3),
                    "invalid HEVC emulation-prevention byte");
                zeros = 0;
                at += 1;
                continue;
            }
            ensure!(byte > 2, "missing HEVC emulation-prevention byte");
        }
        data.push(byte);
        zeros = if byte == 0 { zeros + 1 } else { 0 };
        at += 1;
    }
    Ok(data)
}

// H.265 C.5.2.2 permits a decoder to discard prior pictures when these
// properties change, even with no_output_of_prior_pics_flag cleared.
#[derive(Clone, Debug, PartialEq, Eq)]
struct DecoderConfig {
    width: u32,
    height: u32,
    chroma: u32,
    separate_colour_plane: bool,
    luma_depth_minus8: u32,
    chroma_depth_minus8: u32,
    buffers_minus1: [u32; 7],
}

fn sps_configuration(nal: &[u8]) -> Result<(u32, DecoderConfig)> {
    ensure!(kind(nal)? == 33, "not an HEVC SPS");
    let data = rbsp(nal)?;
    let mut bits = Bits::new(&data);
    bits.skip(4)?; // sps_video_parameter_set_id
    let max_sub = bits.u(3)? as usize;
    ensure!(max_sub <= 6, "invalid HEVC temporal layer count");
    bits.skip(1)?; // sps_temporal_id_nesting_flag
    bits.skip(96)?; // general profile_tier_level
    let mut sub_layers = Vec::with_capacity(max_sub);
    for _ in 0..max_sub { sub_layers.push((bits.u(1)? != 0, bits.u(1)? != 0)); }
    if max_sub > 0 { bits.skip(2 * (8 - max_sub))?; }
    for (profile, level) in sub_layers {
        if profile { bits.skip(88)?; }
        if level { bits.skip(8)?; }
    }
    let id = bits.ue(15)?;
    let chroma = bits.ue(3)?;
    let separate_colour_plane = chroma == 3 && bits.u(1)? != 0;
    let width = bits.ue(u32::MAX - 1)?;
    let height = bits.ue(u32::MAX - 1)?;
    ensure!(width > 0 && height > 0, "invalid HEVC coded dimensions");
    if bits.u(1)? != 0 { for _ in 0..4 { bits.ue(u32::MAX - 1)?; } }
    let luma_depth_minus8 = bits.ue(8)?;
    let chroma_depth_minus8 = bits.ue(8)?;
    bits.ue(12)?; // log2_max_pic_order_cnt_lsb_minus4
    let per_layer = bits.u(1)? != 0;
    let mut buffers_minus1 = [0; 7];
    for layer in (if per_layer { 0 } else { max_sub })..=max_sub {
        buffers_minus1[layer] = bits.ue(15)?;
        let reorder = bits.ue(15)?;
        ensure!(reorder <= buffers_minus1[layer], "invalid HEVC reorder buffer");
        bits.ue(u32::MAX - 1)?; // sps_max_latency_increase_plus1
    }
    let highest = buffers_minus1[max_sub];
    if !per_layer { buffers_minus1[..max_sub].fill(highest); }
    // Compare every temporal-layer selection. Above the highest layer,
    // selecting all available pictures uses that highest layer's capacity.
    buffers_minus1[max_sub + 1..].fill(highest);
    Ok((id, DecoderConfig { width, height, chroma, separate_colour_plane,
        luma_depth_minus8, chroma_depth_minus8, buffers_minus1 }))
}

fn pps_ids(nal: &[u8]) -> Result<(u32, u32)> {
    ensure!(kind(nal)? == 34, "not an HEVC PPS");
    let data = rbsp(nal)?;
    let mut bits = Bits::new(&data);
    Ok((bits.ue(63)?, bits.ue(15)?))
}

struct Picture {
    kind: u8,
    pps: u32,
    no_output: Option<bool>,
    config: DecoderConfig,
}

pub(crate) struct Repair {
    width: usize,
    sps: BTreeMap<u32, DecoderConfig>,
    pps: BTreeMap<u32, u32>,
    previous: Option<DecoderConfig>,
}

impl Repair {
    pub(crate) fn new(extradata: &[u8]) -> Result<Self> {
        ensure!(extradata.len() >= 23 && extradata[0] == 1, "missing HEVC configuration record");
        // parameter_sets tolerates incomplete records. Validate the framing
        // first so a partial array cannot silently choose an older SPS.
        let mut at = 23;
        for _ in 0..extradata[22] {
            ensure!(extradata.len() - at >= 3, "truncated HEVC configuration array");
            let array_kind = extradata[at] & 63;
            let count = u16::from_be_bytes([extradata[at + 1], extradata[at + 2]]);
            at += 3;
            for _ in 0..count {
                ensure!(extradata.len() - at >= 2, "truncated HEVC configuration length");
                let length = u16::from_be_bytes([extradata[at], extradata[at + 1]]) as usize;
                at += 2;
                ensure!(length <= extradata.len() - at, "truncated HEVC parameter set");
                ensure!(kind(&extradata[at..at + length])? == array_kind,
                    "HEVC configuration array has the wrong NAL type");
                at += length;
            }
        }
        ensure!(at == extradata.len(), "unexpected HEVC configuration bytes");
        let mut repair = Self { width: usize::from(extradata[21] & 3) + 1,
            sps: BTreeMap::new(), pps: BTreeMap::new(), previous: None };
        for nal in smartcut_core::bitstream::parameter_sets("hevc", extradata) {
            repair.read_set(&nal)?;
        }
        ensure!(!repair.sps.is_empty() && !repair.pps.is_empty(), "missing HEVC SPS or PPS");
        Ok(repair)
    }

    fn read_set(&mut self, nal: &[u8]) -> Result<()> {
        match kind(nal)? {
            33 => { let (id, config) = sps_configuration(nal)?; self.sps.insert(id, config); },
            34 => { let (id, sps) = pps_ids(nal)?; self.pps.insert(id, sps); },
            _ => (),
        }
        Ok(())
    }

    pub(crate) fn apply(&mut self, packet: &mut ff::Packet, at_splice: bool) -> Result<()> {
        let mut picture: Option<Picture> = None;
        let mut changes = Vec::new();
        let mut at = 0;
        for nal in crate::hevc::nals(packet.data().context("empty HEVC packet")?, self.width)? {
            let nal_kind = kind(nal)?;
            if matches!(nal_kind, 33 | 34) { self.read_set(nal)?; }
            if nal_kind <= 31 {
                // These fields fit within the first two payload bytes for
                // every valid PPS ID, before any emulation-prevention byte.
                let mut bits = Bits::new(&nal[2..]);
                let first = bits.u(1)? != 0;
                let no_output = if (16..=23).contains(&nal_kind) { Some(bits.u(1)? != 0) } else { None };
                let pps = bits.ue(63)?;
                let sps = self.pps.get(&pps).context("unknown HEVC slice PPS")?;
                let config = self.sps.get(sps).context("unknown HEVC slice SPS")?.clone();
                if let Some(current) = &picture {
                    ensure!(!first, "multiple HEVC pictures in one packet");
                    ensure!(current.kind == nal_kind && current.pps == pps
                        && current.no_output == no_output && current.config == config,
                        "inconsistent HEVC picture slice headers");
                } else {
                    ensure!(first, "missing first HEVC slice segment");
                    picture = Some(Picture { kind: nal_kind, pps, no_output, config });
                }
                if at_splice && self.previous.is_some() && matches!(nal_kind, 19 | 20)
                    && no_output == Some(true) { changes.push(at + self.width + 2); }
            }
            at += self.width + nal.len();
        }
        if let Some(picture) = picture {
            if at_splice { ensure!(matches!(picture.kind, 19 | 20), "HEVC splice is not an IDR picture"); }
            if !changes.is_empty() {
                ensure!(self.previous.as_ref() == Some(&picture.config),
                    "HEVC splice changes decoder buffers while prior pictures are pending");
                let result = unsafe { ff::ffi::av_packet_make_writable(packet.as_mut_ptr()) };
                if result < 0 { return Err(ff::Error::from(result)).context("could not make HEVC packet writable"); }
                let data = packet.data_mut().context("empty writable HEVC packet")?;
                for offset in changes {
                    // With PPS <= 63, clearing this bit cannot create a
                    // 00 00 {00..03} sequence: a first slice stays >= 0x80;
                    // a continuation can become 0 only for PPS 63, whose
                    // following byte starts with 1. No RBSP re-escape needed.
                    data[offset] &= !0x40;
                }
            }
            self.previous = Some(picture.config);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ff::codec::packet::{Mut, Ref};

    fn ue(value: u32) -> String {
        let code = format!("{:b}", value + 1);
        format!("{}{}", "0".repeat(code.len() - 1), code)
    }

    fn nal(kind: u8, mut bits: String) -> Vec<u8> {
        bits.push('1'); // rbsp_stop_one_bit
        while bits.len() % 8 != 0 { bits.push('0'); }
        let mut out = vec![kind << 1, 1];
        let mut zeros = 0;
        for chunk in bits.as_bytes().chunks(8) {
            let byte = chunk.iter().fold(0, |value, bit| (value << 1) | (bit - b'0'));
            if zeros == 2 && byte <= 3 { out.push(3); zeros = 0; }
            out.push(byte);
            zeros = if byte == 0 { zeros + 1 } else { 0 };
        }
        out
    }

    fn sps(id: u32, width: u32, depth: u32, buffers: &[u32], per_layer: bool) -> Vec<u8> {
        let max_sub = buffers.len() - 1;
        let mut bits = format!("0000{max_sub:03b}1{}", "0".repeat(96));
        if max_sub > 0 {
            bits.push_str(&"00".repeat(max_sub));
            bits.push_str(&"00".repeat(8 - max_sub));
        }
        for value in [id, 1, width, 240] { bits.push_str(&ue(value)); }
        bits.push('0'); // no conformance window
        for value in [depth, depth, 4] { bits.push_str(&ue(value)); }
        bits.push(if per_layer { '1' } else { '0' });
        for &buffer in &buffers[if per_layer { 0 } else { max_sub }..] {
            bits.push_str(&ue(buffer));
            bits.push_str(&ue(0)); // reorder pictures
            bits.push_str(&ue(0)); // latency
        }
        nal(33, bits)
    }

    fn pps(id: u32, sps: u32) -> Vec<u8> { nal(34, format!("{}{}1", ue(id), ue(sps))) }

    fn slice(kind: u8, first: bool, no_output: bool, pps: u32) -> Vec<u8> {
        let mut bits = format!("{}", u8::from(first));
        if (16..=23).contains(&kind) { bits.push(if no_output { '1' } else { '0' }); }
        bits.push_str(&ue(pps));
        if !first { bits.push('1'); } // dependent_slice_segment_flag
        bits.push_str("0010101010101010000000000000000000000001");
        nal(kind, bits)
    }

    fn configuration(sets: &[Vec<u8>], width: usize) -> Vec<u8> {
        let mut bytes = vec![0; 23];
        bytes[0] = 1;
        bytes[21] = (width - 1) as u8;
        bytes[22] = sets.len() as u8;
        for set in sets {
            bytes.push((set[0] >> 1) & 63);
            bytes.extend_from_slice(&1u16.to_be_bytes());
            bytes.extend_from_slice(&(set.len() as u16).to_be_bytes());
            bytes.extend_from_slice(set);
        }
        bytes
    }

    fn packet(nals: &[Vec<u8>], width: usize) -> ff::Packet {
        let mut bytes = Vec::new();
        for nal in nals {
            bytes.extend_from_slice(&(nal.len() as u32).to_be_bytes()[4 - width..]);
            bytes.extend_from_slice(nal);
        }
        ff::Packet::copy(&bytes)
    }

    fn repair(pps_id: u32, width: usize) -> Repair {
        Repair::new(&configuration(&[sps(0, 320, 0, &[5], false), pps(pps_id, 0)], width)).unwrap()
    }

    #[test]
    fn only_new_splices_change_all_idr_slice_flags() {
        for width in 1..=4 {
            for kind in [19, 20] {
                for pps_id in [0, 31, 32, 62, 63] {
                    let slices = [slice(kind, true, true, pps_id), slice(kind, false, true, pps_id)];
                    let mut state = repair(pps_id, width);
                    let mut first = packet(&slices, width);
                    let original = first.data().unwrap().to_vec();
                    state.apply(&mut first, true).unwrap();
                    assert_eq!(first.data().unwrap(), original, "opening AU must remain whole");
                    let mut internal = packet(&slices, width);
                    state.apply(&mut internal, false).unwrap();
                    assert_eq!(internal.data().unwrap(), original, "intact source IDR must remain whole");
                    let mut joined = packet(&slices, width);
                    state.apply(&mut joined, true).unwrap();
                    let expected = packet(&[slice(kind, true, false, pps_id), slice(kind, false, false, pps_id)], width);
                    assert_eq!(joined.data(), expected.data());
                }
            }
        }
    }

    #[test]
    fn rejects_invalid_pps_or_inconsistent_idr_slices_without_mutation() {
        let mut state = repair(0, 4);
        state.apply(&mut packet(&[slice(19, true, false, 0)], 4), false).unwrap();
        for nals in [
            vec![slice(19, true, true, 64)],
            vec![vec![38, 1]],
            vec![vec![38, 1, 0xc0]],
            vec![slice(19, true, true, 0), slice(19, false, false, 0)],
            vec![slice(19, true, true, 0), slice(19, false, true, 1)],
            vec![slice(19, true, true, 0), slice(20, false, true, 0)],
            vec![slice(19, true, true, 0), slice(19, true, true, 0)],
        ] {
            let mut invalid = packet(&nals, 4);
            let original = invalid.data().unwrap().to_vec();
            assert!(state.apply(&mut invalid, true).is_err());
            assert_eq!(invalid.data().unwrap(), original);
        }
        let mut truncated = packet(&[vec![38, 1, 0xc0, 0x80]], 4);
        // PPS 63 needs six suffix bits after its leading one.
        truncated.shrink(7);
        assert!(state.apply(&mut truncated, true).is_err());
    }

    #[test]
    fn checks_configuration_only_when_a_flag_is_repaired() {
        for changed in [sps(0, 640, 0, &[5], false), sps(0, 320, 2, &[5], false),
            sps(0, 320, 0, &[6], false)] {
            let mut state = repair(0, 4);
            state.apply(&mut packet(&[slice(19, true, false, 0)], 4), false).unwrap();
            let mut joined = packet(&[changed.clone(), slice(19, true, true, 0)], 4);
            let original = joined.data().unwrap().to_vec();
            assert!(state.apply(&mut joined, true).is_err());
            assert_eq!(joined.data().unwrap(), original);
            let mut unflagged = packet(&[changed, slice(19, true, false, 0)], 4);
            state.apply(&mut unflagged, true).unwrap();
        }
        let mut state = repair(0, 4);
        state.apply(&mut packet(&[slice(19, true, false, 0)], 4), false).unwrap();
        let mut compatible = packet(&[sps(1, 320, 0, &[5, 5, 5], true), pps(1, 1), slice(20, true, true, 1)], 4);
        state.apply(&mut compatible, true).unwrap();
        assert_eq!(crate::hevc::nals(compatible.data().unwrap(), 4).unwrap().last().unwrap()[2] & 0x40, 0);
    }

    #[test]
    fn preserves_non_idr_packets_and_tracks_parameter_only_packets() {
        let mut state = repair(0, 4);
        let mut sets = packet(&[sps(1, 320, 0, &[5, 5], false), pps(1, 1)], 4);
        let original_sets = sets.data().unwrap().to_vec();
        state.apply(&mut sets, false).unwrap();
        assert_eq!(sets.data().unwrap(), original_sets);
        let mut first = packet(&[slice(19, true, true, 1)], 4);
        let original_first = first.data().unwrap().to_vec();
        state.apply(&mut first, true).unwrap();
        assert_eq!(first.data().unwrap(), original_first);
        for kind in [1, 16, 21] {
            let mut other = packet(&[slice(kind, true, true, 1)], 4);
            let original = other.data().unwrap().to_vec();
            state.apply(&mut other, false).unwrap();
            assert_eq!(other.data().unwrap(), original);
        }
    }

    #[test]
    fn repair_preserves_packet_properties_and_shared_source_buffer() {
        let mut state = repair(0, 4);
        state.apply(&mut packet(&[slice(19, true, false, 0)], 4), false).unwrap();
        let original = packet(&[slice(19, true, true, 0)], 4);
        let original_bytes = original.data().unwrap().to_vec();
        let mut shared = ff::Packet::empty();
        unsafe { assert_eq!(ff::ffi::av_packet_ref(shared.as_mut_ptr(), original.as_ptr()), 0); }
        shared.set_pts(Some(123)); shared.set_dts(Some(100)); shared.set_duration(7);
        shared.set_stream(2); shared.set_position(99); shared.set_flags(ff::codec::packet::Flags::KEY);
        unsafe {
            let data = ff::ffi::av_packet_new_side_data(shared.as_mut_ptr(),
                ff::ffi::AVPacketSideDataType::AV_PKT_DATA_STRINGS_METADATA, 4);
            assert!(!data.is_null());
            std::ptr::copy_nonoverlapping([b'a', 0, b'b', 0].as_ptr(), data, 4);
        }
        state.apply(&mut shared, true).unwrap();
        assert_eq!(original.data().unwrap(), original_bytes);
        assert_ne!(shared.data(), original.data());
        assert_eq!((shared.pts(), shared.dts(), shared.duration(), shared.stream(), shared.position()),
            (Some(123), Some(100), 7, 2, 99));
        assert_eq!(shared.flags(), ff::codec::packet::Flags::KEY);
        let side: Vec<_> = shared.side_data().map(|side| (side.kind(), side.data().to_vec())).collect();
        assert_eq!(side, vec![(ff::codec::packet::side_data::Type::StringsMetadata, vec![b'a', 0, b'b', 0])]);
    }
}
