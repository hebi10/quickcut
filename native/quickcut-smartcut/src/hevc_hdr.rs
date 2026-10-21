//! Decline HDR signalling that the boundary encoder cannot preserve.
//! The caller supplies complete video access units, in decode order.
use anyhow::{bail, ensure, Context, Result};
use ffmpeg_next as ff;

// ffmpeg-sys-next does not bind mastering_display_metadata.h. These layouts
// match that header in the pinned FFmpeg 7.1.5 source. Its struct sizes are not
// a public ABI promise: recheck them when upgrading the native dependency.
#[repr(C)]
#[derive(Clone, Copy)]
struct MasteringDisplayRaw {
    display_primaries: [[ff::ffi::AVRational; 2]; 3],
    white_point: [ff::ffi::AVRational; 2],
    min_luminance: ff::ffi::AVRational,
    max_luminance: ff::ffi::AVRational,
    has_primaries: std::ffi::c_int,
    has_luminance: std::ffi::c_int,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct ContentLightRaw {
    max_cll: u32,
    max_fall: u32,
}

const _: [(); 88] = [(); std::mem::size_of::<MasteringDisplayRaw>()];
const _: [(); 8] = [(); std::mem::size_of::<ContentLightRaw>()];

pub(crate) struct Guard {
    static_hdr: bool,
    transfer: i32,
    seen_access_unit: bool,
    mastering: Option<[u8; 24]>,
    content_light: Option<[u8; 4]>,
    alternative_transfer: Option<u8>,
    // Side data uses native FFmpeg structs rather than HEVC serialization.
    // Compare active, normalized fields, never padding or inactive fields.
    side_mastering: Option<Vec<(i32, i32)>>,
    side_content_light: Option<[u32; 2]>,
}

impl Guard {
    pub(crate) fn new(extradata: &[u8], static_hdr: bool, transfer: i32) -> Result<Self> {
        ensure!(extradata.len() >= 23 && extradata[0] == 1, "invalid HEVC configuration record");
        let mut guard = Self { static_hdr, transfer, seen_access_unit: false,
            mastering: None, content_light: None, alternative_transfer: None,
            side_mastering: None, side_content_light: None };
        let mut at = 23;
        for _ in 0..extradata[22] {
            let array = take(extradata, &mut at, 3)?;
            let kind = array[0] & 63;
            ensure!(array[0] & 0x40 == 0 && matches!(kind, 32..=34 | 39 | 40),
                "unsupported HEVC configuration array");
            let count = u16::from_be_bytes([array[1], array[2]]);
            for _ in 0..count {
                let length = take(extradata, &mut at, 2)?;
                let length = u16::from_be_bytes([length[0], length[1]]) as usize;
                let nal = take(extradata, &mut at, length)?;
                ensure!(nal_kind(nal)? == kind, "HEVC configuration array/NAL type mismatch");
                guard.inspect_nal(nal)?;
            }
        }
        ensure!(at == extradata.len(), "trailing HEVC configuration bytes");
        Ok(guard)
    }

    /// Call for stream side data before reading AUs, and packet side data
    /// before `inspect`. A type first seen after AU 1 cannot be inherited by
    /// the encoder's one-time first-frame signalling probe.
    pub(crate) fn observe_side_data(&mut self, kind: ff::codec::packet::side_data::Type, bytes: &[u8]) -> Result<()> {
        use ff::codec::packet::side_data::Type::{MasteringDisplayMetadata, ContentLightLevel};
        match kind {
            MasteringDisplayMetadata => {
                ensure!(self.static_hdr, "static HDR metadata without supported HDR color");
                ensure!(bytes.len() == std::mem::size_of::<MasteringDisplayRaw>(),
                    "invalid mastering-display side-data size");
                // AVPacket side-data byte alignment is not part of this API.
                let value = unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast::<MasteringDisplayRaw>()) };
                ensure!(matches!(value.has_primaries, 0 | 1) && matches!(value.has_luminance, 0 | 1),
                    "invalid mastering-display flags");
                let mut fields = vec![(value.has_primaries, value.has_luminance)];
                if value.has_primaries != 0 {
                    for coordinate in value.display_primaries.iter().flatten().chain(value.white_point.iter()) {
                        let value = rational(*coordinate)?;
                        ensure!(value.0 <= value.1, "invalid mastering-display chromaticity");
                        fields.push(value);
                    }
                }
                if value.has_luminance != 0 {
                    fields.push(rational(value.min_luminance)?);
                    fields.push(rational(value.max_luminance)?);
                }
                remember(&mut self.side_mastering, fields, self.seen_access_unit, "mastering-display side data")?;
            }
            ContentLightLevel => {
                ensure!(self.static_hdr, "static HDR metadata without supported HDR color");
                ensure!(bytes.len() == std::mem::size_of::<ContentLightRaw>(),
                    "invalid content-light side-data size");
                let value = unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast::<ContentLightRaw>()) };
                ensure!(value.max_cll <= u16::MAX as u32 && value.max_fall <= u16::MAX as u32,
                    "content-light side data exceeds HEVC field widths");
                remember(&mut self.side_content_light, [value.max_cll, value.max_fall],
                    self.seen_access_unit, "content-light side data")?;
            }
            _ => {},
        }
        Ok(())
    }

    pub(crate) fn inspect(&mut self, nals: &[&[u8]]) -> Result<()> {
        ensure!(!nals.is_empty(), "empty HEVC access unit");
        for nal in nals { self.inspect_nal(nal)?; }
        // Do this after the whole AU: valid first-picture metadata can occur
        // in separate SEI NALs on either side of its coded picture.
        self.seen_access_unit = true;
        Ok(())
    }

    fn inspect_nal(&mut self, nal: &[u8]) -> Result<()> {
        if !matches!(nal_kind(nal)?, 39 | 40) { return Ok(()); }
        let rbsp = unescape(&nal[2..])?;
        ensure!(rbsp.len() >= 3 && rbsp.last() == Some(&0x80), "invalid HEVC SEI trailing bits");
        let body = &rbsp[..rbsp.len() - 1];
        let mut at = 0;
        while at < body.len() {
            let kind = sei_number(body, &mut at)?;
            let length = sei_number(body, &mut at)?;
            let payload = take(body, &mut at, length)?;
            match kind {
                4 => bail!("unsupported registered HEVC user data (dynamic/vendor HDR metadata)"),
                5 => ensure!(payload.len() >= 16, "truncated unregistered HEVC user-data UUID"),
                // Tone/knee/color remapping, ambient
                // viewing and content color volume are not copied to x265.
                23 | 141 | 142 | 148 | 149 => bail!("unsupported HEVC color-signalling SEI {kind}"),
                // These wrap further SEI messages. Treating their payload as
                // opaque would let unsupported dynamic metadata pass unseen.
                133 | 157 | 159 | 162 => bail!("unsupported nested HEVC SEI {kind}"),
                137 => {
                    ensure!(self.static_hdr, "static HDR metadata without supported HDR color");
                    let value: [u8; 24] = payload.try_into().context("invalid mastering-display SEI size")?;
                    remember(&mut self.mastering, value, self.seen_access_unit, "mastering-display SEI")?;
                }
                144 => {
                    ensure!(self.static_hdr, "static HDR metadata without supported HDR color");
                    let value: [u8; 4] = payload.try_into().context("invalid content-light SEI size")?;
                    remember(&mut self.content_light, value, self.seen_access_unit, "content-light SEI")?;
                }
                147 => {
                    // x265 emits this even when its HLG SPS already says 18.
                    // Permit only the source's already-resolved HDR transfer;
                    // a new or conflicting transform cannot pass unnoticed.
                    ensure!(payload.len() == 1 && self.static_hdr && [16, 18].contains(&self.transfer)
                        && i32::from(payload[0]) == self.transfer, "unsupported alternative HEVC transfer SEI");
                    remember(&mut self.alternative_transfer, payload[0], self.seen_access_unit, "alternative-transfer SEI")?;
                }
                _ => {}, // Preserve existing handling of other framed SEI.
            }
        }
        Ok(())
    }
}

fn remember<T: PartialEq>(slot: &mut Option<T>, value: T, seen_access_unit: bool, name: &str) -> Result<()> {
    if let Some(original) = slot {
        ensure!(*original == value, "changing {name}");
    } else {
        ensure!(!seen_access_unit, "{name} first appears after the first HEVC access unit");
        *slot = Some(value);
    }
    Ok(())
}

fn rational(value: ff::ffi::AVRational) -> Result<(i32, i32)> {
    ensure!(value.num >= 0 && value.den > 0, "invalid mastering-display rational");
    let (mut a, mut b) = (value.num, value.den);
    while b != 0 { let remainder = a % b; a = b; b = remainder; }
    Ok((value.num / a, value.den / a))
}

fn nal_kind(nal: &[u8]) -> Result<u8> {
    ensure!(nal.len() >= 2 && nal[0] & 0x81 == 0 && nal[1] >> 3 == 0 && nal[1] & 7 != 0,
        "invalid HEVC metadata NAL header/layer");
    Ok((nal[0] >> 1) & 63)
}

fn take<'a>(bytes: &'a [u8], at: &mut usize, length: usize) -> Result<&'a [u8]> {
    ensure!(*at <= bytes.len() && length <= bytes.len() - *at, "truncated HEVC metadata payload");
    let value = &bytes[*at..*at + length];
    *at += length;
    Ok(value)
}

fn sei_number(bytes: &[u8], at: &mut usize) -> Result<usize> {
    let mut value = 0usize;
    loop {
        let byte = take(bytes, at, 1)?[0];
        value = value.checked_add(byte as usize).context("HEVC SEI length/type overflow")?;
        if byte != 255 { return Ok(value); }
    }
}

fn unescape(bytes: &[u8]) -> Result<Vec<u8>> {
    let mut rbsp = Vec::with_capacity(bytes.len());
    let mut zeros = 0;
    let mut at = 0;
    while at < bytes.len() {
        let byte = bytes[at];
        if zeros == 2 {
            ensure!(byte >= 3, "missing HEVC emulation-prevention byte");
            if byte == 3 {
                ensure!(at + 1 < bytes.len() && bytes[at + 1] <= 3, "invalid HEVC emulation-prevention byte");
                zeros = 0;
                at += 1;
                continue;
            }
        }
        rbsp.push(byte);
        zeros = if byte == 0 { zeros + 1 } else { 0 };
        at += 1;
    }
    Ok(rbsp)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn new_guard(extradata: &[u8], static_hdr: bool) -> Result<Guard> {
        Guard::new(extradata, static_hdr, if static_hdr { 16 } else { 1 })
    }

    #[test]
    fn redundant_hlg_transfer_is_allowed_but_conflicting_or_late_transfer_is_not() {
        let message = sei(39, &[(147, &[18])]);
        let mut guard = Guard::new(&configuration(&[message.clone()]), true, 18).unwrap();
        guard.inspect(&[&message, &[38, 1]]).unwrap();
        guard.inspect(&[&message, &[38, 1]]).unwrap();
        assert!(guard.inspect(&[&sei(39, &[(147, &[16])]), &[38, 1]]).is_err());
        assert!(Guard::new(&configuration(&[message.clone()]), true, 16).is_err());
        let mut guard = Guard::new(&configuration(&[]), true, 18).unwrap();
        guard.inspect(&[&[38, 1]]).unwrap();
        assert!(guard.inspect(&[&message, &[38, 1]]).is_err());
        for payload in [&[][..], &[18, 18]] {
            assert!(Guard::new(&configuration(&[]), true, 18).unwrap()
                .inspect(&[&sei(39, &[(147, payload)]), &[38, 1]]).is_err());
        }
    }

    fn side_bytes<T>(value: &T) -> &[u8] {
        // Test values are zero-initialized C structs, including their padding.
        unsafe { std::slice::from_raw_parts((value as *const T).cast(), std::mem::size_of::<T>()) }
    }

    fn mastering_side() -> MasteringDisplayRaw {
        let mut value: MasteringDisplayRaw = unsafe { std::mem::zeroed() };
        value.has_primaries = 1;
        value.has_luminance = 1;
        for (point, values) in value.display_primaries.iter_mut()
            .zip([[34000, 16000], [13250, 34500], [7500, 3000]]) {
            for (coordinate, numerator) in point.iter_mut().zip(values) {
                *coordinate = ff::ffi::AVRational { num: numerator, den: 50000 };
            }
        }
        value.white_point = [ff::ffi::AVRational { num: 15635, den: 50000 },
            ff::ffi::AVRational { num: 16450, den: 50000 }];
        value.min_luminance = ff::ffi::AVRational { num: 50, den: 10000 };
        value.max_luminance = ff::ffi::AVRational { num: 10000000, den: 10000 };
        value
    }

    #[test]
    fn static_side_data_repeats_may_use_equivalent_rationals() {
        use ff::codec::packet::side_data::Type::MasteringDisplayMetadata;
        let mut value = mastering_side();
        let mut guard = new_guard(&configuration(&[]), true).unwrap();
        guard.observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).unwrap();
        guard.inspect(&[&[38, 1]]).unwrap();
        value.max_luminance = ff::ffi::AVRational { num: 1000, den: 1 };
        guard.observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).unwrap();
        value.max_luminance.num = 2000;
        assert!(guard.observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).is_err());
    }

    #[test]
    fn rejects_late_or_changed_packet_content_light_side_data() {
        use ff::codec::packet::side_data::Type::ContentLightLevel;
        let mut value: ContentLightRaw = unsafe { std::mem::zeroed() };
        value.max_cll = 1000; value.max_fall = 400;
        let mut guard = new_guard(&configuration(&[]), true).unwrap();
        guard.inspect(&[&[38, 1]]).unwrap();
        assert!(guard.observe_side_data(ContentLightLevel, side_bytes(&value)).is_err());
        let mut guard = new_guard(&configuration(&[]), true).unwrap();
        guard.observe_side_data(ContentLightLevel, side_bytes(&value)).unwrap();
        guard.inspect(&[&[38, 1]]).unwrap();
        guard.observe_side_data(ContentLightLevel, side_bytes(&value)).unwrap();
        value.max_fall += 1;
        assert!(guard.observe_side_data(ContentLightLevel, side_bytes(&value)).is_err());
    }

    #[test]
    fn rejects_late_mastering_side_data_and_malformed_side_data_structures() {
        use ff::codec::packet::side_data::Type::{MasteringDisplayMetadata, ContentLightLevel};
        let mut value = mastering_side();
        let mut guard = new_guard(&configuration(&[]), true).unwrap();
        guard.inspect(&[&[38, 1]]).unwrap();
        assert!(guard.observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).is_err());
        assert!(new_guard(&configuration(&[]), false).unwrap()
            .observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).is_err());
        for kind in [MasteringDisplayMetadata, ContentLightLevel] {
            for length in [0, 1, 7, 9, 87, 89] {
                assert!(new_guard(&configuration(&[]), true).unwrap()
                    .observe_side_data(kind, &vec![0; length]).is_err());
            }
        }
        value.max_luminance.den = 0;
        assert!(new_guard(&configuration(&[]), true).unwrap()
            .observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).is_err());
        value = mastering_side(); value.has_primaries = 2;
        assert!(new_guard(&configuration(&[]), true).unwrap()
            .observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).is_err());
        value = mastering_side(); value.white_point[0].num = -1;
        assert!(new_guard(&configuration(&[]), true).unwrap()
            .observe_side_data(MasteringDisplayMetadata, side_bytes(&value)).is_err());
    }

    fn escape(rbsp: &[u8]) -> Vec<u8> {
        let mut bytes = Vec::new();
        let mut zeros = 0;
        for &byte in rbsp {
            if zeros == 2 && byte <= 3 {
                bytes.push(3);
                zeros = 0;
            }
            bytes.push(byte);
            zeros = if byte == 0 { zeros + 1 } else { 0 };
        }
        bytes
    }

    fn number(bytes: &mut Vec<u8>, mut value: usize) {
        while value >= 255 { bytes.push(255); value -= 255; }
        bytes.push(value as u8);
    }

    fn sei(kind: u8, messages: &[(usize, &[u8])]) -> Vec<u8> {
        let mut rbsp = Vec::new();
        for &(payload_type, payload) in messages {
            number(&mut rbsp, payload_type);
            number(&mut rbsp, payload.len());
            rbsp.extend_from_slice(payload);
        }
        rbsp.push(0x80);
        let mut bytes = vec![kind << 1, 1];
        bytes.extend(escape(&rbsp));
        bytes
    }

    fn configuration(nals: &[Vec<u8>]) -> Vec<u8> {
        // Codec/profile validation belongs to hevc::validate_configuration.
        // These records isolate this guard's array and metadata handling.
        let mut bytes = vec![0; 23];
        bytes[0] = 1;
        bytes[22] = nals.len() as u8;
        for nal in nals {
            bytes.extend_from_slice(&[(nal[0] >> 1) & 63, 0, 1]);
            bytes.extend_from_slice(&(nal.len() as u16).to_be_bytes());
            bytes.extend_from_slice(nal);
        }
        bytes
    }

    fn mastering() -> Vec<u8> {
        let mut bytes = Vec::new();
        for value in [13250u16, 34500, 7500, 3000, 34000, 16000, 15635, 16450] {
            bytes.extend_from_slice(&value.to_be_bytes());
        }
        bytes.extend_from_slice(&10_000_000u32.to_be_bytes());
        bytes.extend_from_slice(&50u32.to_be_bytes());
        bytes
    }

    fn light() -> Vec<u8> { vec![0x03, 0xe8, 0x01, 0x90] }

    #[test]
    fn static_hdr_metadata_is_optional() {
        for static_hdr in [false, true] {
            let mut guard = new_guard(&configuration(&[]), static_hdr).unwrap();
            guard.inspect(&[&[64, 1], &[38, 1], &[68, 1]]).unwrap();
            guard.inspect(&[&[2, 1]]).unwrap();
        }
    }

    #[test]
    fn first_access_unit_accepts_static_metadata_in_any_nal_order() {
        let master = sei(39, &[(137, &mastering())]);
        let content = sei(40, &[(144, &light())]);
        let mut guard = new_guard(&configuration(&[]), true).unwrap();
        guard.inspect(&[&[64, 1], &content, &[38, 1], &master, &[68, 1]]).unwrap();
        guard.inspect(&[&[2, 1]]).unwrap();
        // The same static SEI may be repeated at every later closed GOP.
        guard.inspect(&[&master, &content, &[38, 1]]).unwrap();
    }

    #[test]
    fn configuration_static_metadata_and_identical_packet_repeats_are_accepted() {
        let master = sei(39, &[(137, &mastering())]);
        let content = sei(39, &[(144, &light())]);
        let mut guard = new_guard(&configuration(&[master.clone()]), true).unwrap();
        // A type absent from hvcC may still first appear in the first AU.
        guard.inspect(&[&content, &[38, 1], &master]).unwrap();
        guard.inspect(&[&master, &content, &[38, 1]]).unwrap();
    }

    #[test]
    fn rejects_static_metadata_changes_even_inside_the_first_access_unit() {
        for (kind, original) in [(137, mastering()), (144, light())] {
            let mut changed = original.clone();
            changed[0] ^= 1;
            let first = sei(39, &[(kind, &original)]);
            let second = sei(39, &[(kind, &changed)]);
            let mut guard = new_guard(&configuration(&[]), true).unwrap();
            assert!(guard.inspect(&[&first, &second, &[38, 1]]).is_err());
            let mut guard = new_guard(&configuration(&[]), true).unwrap();
            guard.inspect(&[&first, &[38, 1]]).unwrap();
            assert!(guard.inspect(&[&second, &[38, 1]]).is_err());
            let mut guard = new_guard(&configuration(&[first]), true).unwrap();
            assert!(guard.inspect(&[&second, &[38, 1]]).is_err());
        }
    }

    #[test]
    fn rejects_each_static_metadata_type_when_it_first_appears_after_the_first_au() {
        for (kind, payload) in [(137, mastering()), (144, light())] {
            let later = sei(39, &[(kind, &payload)]);
            let mut guard = new_guard(&configuration(&[]), true).unwrap();
            guard.inspect(&[&[38, 1]]).unwrap();
            assert!(guard.inspect(&[&later, &[38, 1]]).is_err());
        }
        let mut guard = new_guard(&configuration(&[]), true).unwrap();
        guard.inspect(&[&sei(39, &[(137, &mastering())]), &[38, 1]]).unwrap();
        assert!(guard.inspect(&[&sei(39, &[(144, &light())]), &[38, 1]]).is_err());
    }

    #[test]
    fn rejects_static_metadata_when_the_encoder_will_not_inherit_it() {
        for (kind, payload) in [(137, mastering()), (144, light())] {
            let message = sei(39, &[(kind, &payload)]);
            assert!(new_guard(&configuration(&[message.clone()]), false).is_err());
            assert!(new_guard(&configuration(&[]), false).unwrap()
                .inspect(&[&message, &[38, 1]]).is_err());
        }
    }

    #[test]
    fn rejects_registered_user_data_in_configuration_prefix_and_suffix_sei() {
        // T.35 registered user data includes HDR10+ and other vendor metadata;
        // no unverified registered application is safe to drop at a boundary.
        for kind in [39, 40] {
            let message = sei(kind, &[(5, &[0x11; 16]), (4, &[0xb5, 0, 0x3c, 0, 1, 4])]);
            assert!(new_guard(&configuration(&[message.clone()]), true).is_err());
            assert!(new_guard(&configuration(&[]), true).unwrap()
                .inspect(&[&message, &[38, 1]]).is_err());
        }
    }

    #[test]
    fn accepts_encoder_user_data_and_other_framed_sei_with_extended_lengths() {
        let payload: Vec<_> = (0..600).map(|index| (index % 5) as u8).collect();
        let message = sei(39, &[(5, &payload), (510, &[0, 0, 0, 0, 1, 2, 3])]);
        let mut guard = new_guard(&configuration(&[message.clone()]), false).unwrap();
        guard.inspect(&[&message, &[38, 1]]).unwrap();
    }

    #[test]
    fn rejects_unsupported_color_transforms_and_nested_sei() {
        for kind in [23, 141, 142, 147, 148, 149, 133, 157, 159, 162] {
            let message = sei(39, &[(kind, &[18])]);
            assert!(new_guard(&configuration(&[message.clone()]), true).is_err(), "SEI {kind}");
            assert!(new_guard(&configuration(&[]), true).unwrap()
                .inspect(&[&message, &[38, 1]]).is_err(), "SEI {kind}");
        }
    }

    #[test]
    fn rejects_malformed_fixed_size_static_metadata_and_short_user_uuid() {
        for (kind, size) in [(137, 23), (137, 25), (144, 3), (144, 5), (5, 15)] {
            let message = sei(39, &[(kind, &vec![0x11; size])]);
            assert!(new_guard(&configuration(&[]), true).unwrap()
                .inspect(&[&message, &[38, 1]]).is_err());
        }
    }

    #[test]
    fn rejects_truncated_sei_headers_payloads_and_missing_trailing_bits() {
        let complete = sei(39, &[(137, &mastering())]);
        for length in 0..complete.len() {
            assert!(new_guard(&configuration(&[]), true).unwrap()
                .inspect(&[&complete[..length]]).is_err(), "length {length}");
        }
        for rbsp in [
            &[255][..], &[5, 255], &[5, 255, 255, 255, 1, 0x80],
            &[5, 16, 0x80], &[0x80, 0], &[5, 0, 0x80],
        ] {
            let mut message = vec![78, 1];
            message.extend(escape(rbsp));
            assert!(new_guard(&configuration(&[]), true).unwrap().inspect(&[&message]).is_err());
        }
    }

    #[test]
    fn rejects_missing_or_invalid_emulation_prevention_bytes() {
        for payload in [
            &[0, 0, 0][..], &[0, 0, 1], &[0, 0, 2], &[0, 0, 3], &[0, 0, 3, 4],
        ] {
            let mut message = vec![78, 1, 5, payload.len() as u8];
            message.extend_from_slice(payload);
            message.push(0x80);
            assert!(new_guard(&configuration(&[]), true).unwrap().inspect(&[&message]).is_err());
        }
    }

    #[test]
    fn rejects_malformed_configuration_arrays_without_reading_past_them() {
        let valid = configuration(&[sei(39, &[(137, &mastering())])]);
        for length in 0..valid.len() {
            assert!(new_guard(&valid[..length], true).is_err(), "length {length}");
        }
        let mut extra = valid.clone(); extra.push(0);
        assert!(new_guard(&extra, true).is_err());
        for (offset, value) in [(0, 0), (23, 40), (23, 39 | 0x40), (26, 255), (27, 1), (28, 0xfe), (29, 0)] {
            let mut malformed = valid.clone();
            malformed[offset] = value;
            assert!(new_guard(&malformed, true).is_err(), "offset {offset}");
        }
    }
}
