//! Decoder configuration that Android libavc requires to remain constant
//! across the SPS entries in one avcC record. IDs and picture-order syntax
//! may differ, but a buffer/profile/size change can invalidate the other sets.
use anyhow::{ensure, Result};

fn maximum_frame_macroblocks(profile: u32, constraints: u32, level: u32) -> Result<u32> {
    // Android 15 ih264d_correct_level_idc upgrades underspecified levels
    // before comparing the next SPS. Reject that case instead of allowing
    // an otherwise identical avcC to reset and lose earlier parameter sets.
    // Level 1b uses level_idc 11 + constraint_set3 in Baseline/Main, or
    // level_idc 9 for High. Both permit only 99 coded macroblocks.
    Ok(match level {
        9 if profile == 100 => 99,
        10 => 99,
        11 if matches!(profile, 66 | 77) && constraints & 0x10 != 0 => 99,
        11 | 12 | 13 | 20 => 396,
        21 => 792,
        22 | 30 => 1620,
        31 => 3600,
        32 => 5120,
        40 | 41 => 8192,
        42 => 8704,
        50 => 22080,
        51 | 52 => 36864,
        _ => anyhow::bail!("unsupported SPS level requires exact fallback"),
    })
}

struct Bits { bytes: Vec<u8>, at: usize }
impl Bits {
    fn new(nal: &[u8]) -> Self {
        let mut bytes = Vec::new(); let mut zeros = 0;
        for &byte in nal {
            if zeros == 2 && byte == 3 { zeros = 0; continue; }
            bytes.push(byte); zeros = if byte == 0 { zeros + 1 } else { 0 };
        }
        Self { bytes, at: 8 }
    }
    fn u(&mut self, count: usize) -> Result<u32> {
        ensure!(count <= 32 && self.at + count <= self.bytes.len() * 8, "truncated SPS");
        let mut value = 0;
        for _ in 0..count {
            value = (value << 1) | u32::from((self.bytes[self.at / 8] >> (7 - self.at % 8)) & 1);
            self.at += 1;
        }
        Ok(value)
    }
    fn flag(&mut self) -> Result<bool> { Ok(self.u(1)? != 0) }
    fn ue(&mut self) -> Result<u32> {
        let mut zeros = 0;
        while self.u(1)? == 0 { zeros += 1; ensure!(zeros < 31, "invalid SPS integer"); }
        Ok((1u32 << zeros) - 1 + self.u(zeros)?)
    }
    fn se(&mut self) -> Result<i32> {
        let value = self.ue()?;
        Ok(if value & 1 != 0 { ((value + 1) / 2) as i32 } else { -((value / 2) as i32) })
    }
    fn hrd(&mut self) -> Result<()> {
        let count = self.ue()?; ensure!(count <= 31, "unsupported SPS HRD count");
        self.u(8)?;
        for _ in 0..=count { self.ue()?; self.ue()?; self.u(1)?; }
        self.u(20)?; Ok(())
    }
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct DecoderConfig {
    profile: u32,
    level: u32,
    pub references: u32,
    width_mbs: u32,
    height_map_units: u32,
    frame_mbs_only: bool,
    crop: [u32; 4],
    // Full range, primaries, transfer, matrix; absent VUI means unspecified.
    colour: [u32; 4],
    reorder: Option<(u32, u32)>,
}

impl DecoderConfig {
    pub fn read(nal: &[u8]) -> Result<Self> {
        ensure!(nal.first().map(|v| v & 31) == Some(7), "not an SPS");
        let mut bits = Bits::new(nal);
        let profile = bits.u(8)?;
        ensure!(matches!(profile, 66 | 77 | 100), "unsupported SPS profile");
        let constraints = bits.u(8)?; let level = bits.u(8)?;
        let maximum_macroblocks = maximum_frame_macroblocks(profile, constraints, level)?;
        ensure!(bits.ue()? < 32, "invalid SPS ID");
        if profile == 100 {
            ensure!(bits.ue()? == 1 && bits.ue()? == 0 && bits.ue()? == 0,
                "only 8-bit 4:2:0 SPS is supported");
            bits.u(1)?;
            if bits.flag()? {
                for index in 0..8 {
                    if !bits.flag()? { continue; }
                    let (mut last, mut next) = (8, 8);
                    for _ in 0..if index < 6 { 16 } else { 64 } {
                        if next != 0 { next = (last + bits.se()?).rem_euclid(256); }
                        if next != 0 { last = next; }
                    }
                }
            }
        }
        ensure!(bits.ue()? <= 12, "invalid SPS frame-number width");
        match bits.ue()? {
            0 => { ensure!(bits.ue()? <= 12, "invalid SPS order width"); },
            1 => {
                bits.u(1)?; bits.se()?; bits.se()?;
                let count = bits.ue()?; ensure!(count <= 255, "invalid SPS order cycle");
                for _ in 0..count { bits.se()?; }
            },
            2 => (),
            _ => anyhow::bail!("invalid SPS picture order"),
        }
        let references = bits.ue()?;
        ensure!((1..=16).contains(&references), "unsupported SPS reference-buffer size");
        bits.u(1)?;
        let width_mbs = bits.ue()?; let height_map_units = bits.ue()?;
        ensure!(width_mbs < 1024 && height_map_units < 1024, "unsupported SPS dimensions");
        let frame_mbs_only = bits.flag()?;
        ensure!(frame_mbs_only, "interlaced SPS requires exact fallback");
        ensure!((width_mbs + 1) * (height_map_units + 1) <= maximum_macroblocks,
            "SPS level is too low for the coded frame size; exact fallback required");
        bits.u(1)?;
        let mut crop = [0; 4];
        if bits.flag()? { for offset in &mut crop { *offset = bits.ue()?; } }
        let mut colour = [0, 2, 2, 2];
        let mut reorder = None;
        if bits.flag()? {
            if bits.flag()? { if bits.u(8)? == 255 { bits.u(16)?; bits.u(16)?; } }
            if bits.flag()? { bits.u(1)?; }
            if bits.flag()? {
                bits.u(3)?; colour[0] = bits.u(1)?;
                if bits.flag()? { for value in &mut colour[1..] { *value = bits.u(8)?; } }
            }
            if bits.flag()? { bits.ue()?; bits.ue()?; }
            if bits.flag()? { bits.u(32)?; bits.u(32)?; bits.u(1)?; }
            let nal_hrd = bits.flag()?; if nal_hrd { bits.hrd()?; }
            let vcl_hrd = bits.flag()?; if vcl_hrd { bits.hrd()?; }
            if nal_hrd || vcl_hrd { bits.u(1)?; }
            bits.u(1)?;
            if bits.flag()? {
                bits.u(1)?; for _ in 0..4 { bits.ue()?; }
                let frames = bits.ue()?; let buffer = bits.ue()?;
                ensure!(frames <= buffer && buffer >= references && buffer <= 16,
                    "invalid SPS reorder buffer");
                reorder = Some((frames, buffer));
            }
        }
        Ok(Self { profile, level, references, width_mbs, height_map_units,
            frame_mbs_only, crop, colour, reorder })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn level_1b_and_unknown_levels_have_explicit_safe_limits() {
        assert_eq!(maximum_frame_macroblocks(66, 0xc0, 11).unwrap(), 396);
        assert_eq!(maximum_frame_macroblocks(66, 0xd0, 11).unwrap(), 99);
        assert_eq!(maximum_frame_macroblocks(77, 0x10, 11).unwrap(), 99);
        assert_eq!(maximum_frame_macroblocks(100, 0, 9).unwrap(), 99);
        assert!(maximum_frame_macroblocks(66, 0, 9).is_err());
        assert!(maximum_frame_macroblocks(100, 0, 255).is_err());
    }
}
