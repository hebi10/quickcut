//! Recognize Android's AAC priming-inclusive whole-file duration. This is not
//! an overshoot tolerance: only the actual mdhd value, explained by a normal
//! single-entry AAC edit, is accepted. The requested trim plan stays unchanged.
use anyhow::{ensure, Context, Result};
use ffmpeg_next as ff;
use smartcut_core::{input::Demux, Source};

#[derive(Clone, Copy)]
struct Atom { kind: [u8; 4], data: i64, end: i64 }

struct Reader { input: Demux, remaining_atoms: usize }
impl Reader {
    fn bytes(&mut self, at: i64, length: usize) -> Result<Vec<u8>> {
        let mut bytes = vec![0; length];
        unsafe {
            let io = (*self.input.as_mut_ptr()).pb;
            ensure!(!io.is_null(), "missing MP4 reader");
            ensure!(ff::ffi::avio_seek(io, at, 0) == at, "cannot seek MP4 metadata");
            let read = ff::ffi::avio_read(io, bytes.as_mut_ptr(), length as i32);
            ensure!(read == length as i32, "truncated MP4 metadata");
        }
        Ok(bytes)
    }
    fn prefix(&mut self, atom: Atom, length: usize) -> Result<Vec<u8>> {
        ensure!(atom.end - atom.data >= length as i64, "truncated MP4 atom");
        self.bytes(atom.data, length)
    }
    fn children(&mut self, start: i64, end: i64) -> Result<Vec<Atom>> {
        let mut at = start;
        let mut atoms = Vec::new();
        while at < end {
            ensure!(self.remaining_atoms > 0 && end - at >= 8, "invalid or excessive MP4 atoms");
            self.remaining_atoms -= 1;
            let header = self.bytes(at, 8)?;
            let size32 = be32(&header, 0);
            let (size, header_size) = match size32 {
                0 => ((end - at) as u64, 8),
                1 => {
                    ensure!(end - at >= 16, "truncated large MP4 atom");
                    (be64(&self.bytes(at + 8, 8)?, 0), 16)
                }
                value => (u64::from(value), 8),
            };
            ensure!(size >= header_size && size <= (end - at) as u64, "invalid MP4 atom size");
            atoms.push(Atom { kind: header[4..8].try_into().unwrap(), data: at + header_size as i64,
                end: at + size as i64 });
            at += size as i64;
        }
        Ok(atoms)
    }
    fn inside(&mut self, atom: Atom) -> Result<Vec<Atom>> { self.children(atom.data, atom.end) }
    fn timing(&mut self, atom: Atom) -> Result<(u32, u64)> {
        let version = self.prefix(atom, 4)?[0];
        let (scale, ticks) = match version {
            0 => { let data = self.prefix(atom, 20)?; (be32(&data, 12), u64::from(be32(&data, 16))) }
            1 => { let data = self.prefix(atom, 32)?; (be32(&data, 20), be64(&data, 24)) }
            _ => anyhow::bail!("unknown MP4 duration version"),
        };
        ensure!(scale > 0 && ticks > 0 && ticks != u64::MAX
            && !(version == 0 && ticks == u64::from(u32::MAX)), "unknown MP4 duration");
        Ok((scale, ticks))
    }
    fn edit(&mut self, atom: Atom) -> Result<(u64, u64)> {
        let first = self.prefix(atom, 8)?;
        ensure!(be32(&first, 4) == 1, "complex AAC edit list");
        let (length, duration, media_time, rate_at) = match first[0] {
            0 => { let data = self.prefix(atom, 20)?;
                (20, u64::from(be32(&data, 8)), i64::from(be32(&data, 12) as i32), 16) }
            1 => { let data = self.prefix(atom, 28)?;
                (28, be64(&data, 8), be64(&data, 16) as i64, 24) }
            _ => anyhow::bail!("unknown AAC edit version"),
        };
        let data = self.prefix(atom, length)?;
        ensure!(atom.end - atom.data == length as i64 && media_time >= 0
            && data[rate_at..rate_at + 4] == [0, 1, 0, 0], "unsupported AAC edit");
        Ok((duration, media_time as u64))
    }
}

fn be32(data: &[u8], at: usize) -> u32 { u32::from_be_bytes(data[at..at + 4].try_into().unwrap()) }
fn be64(data: &[u8], at: usize) -> u64 { u64::from_be_bytes(data[at..at + 8].try_into().unwrap()) }
fn one(atoms: &[Atom], kind: &[u8; 4]) -> Result<Atom> {
    let mut matches = atoms.iter().filter(|a| &a.kind == kind);
    let result = *matches.next().context("missing MP4 duration atom")?;
    ensure!(matches.next().is_none(), "duplicate MP4 duration atom");
    Ok(result)
}
fn duration_us((scale, ticks): (u32, u64)) -> Result<i64> {
    i64::try_from(u128::from(ticks) * 1_000_000 / u128::from(scale)).context("MP4 duration overflow")
}

pub(crate) fn is_android_aac_eof(source: &Source, end_us: i64) -> Result<bool> {
    if source.audios.len() != 1 || source.start_time != 0.0 { return Ok(false); }
    // Use the same AVIO owner as all native reads: a granted content descriptor
    // must never be reopened through /proc/self/fd, nor share a mutable cursor.
    let input = smartcut_core::input::open_local(&source.path)?;
    ensure!(input.format().name().split(',').any(|name| name == "mp4"), "not MP4");
    let mut reader = Reader { input, remaining_atoms: 1024 };
    let size = unsafe {
        let io = (*reader.input.as_mut_ptr()).pb;
        ensure!(!io.is_null(), "missing MP4 reader");
        ff::ffi::avio_size(io)
    };
    ensure!(size >= 8, "unknown MP4 size");
    let top = reader.children(0, size)?;
    let movie = reader.inside(one(&top, b"moov")?)?;
    let movie_time = reader.timing(one(&movie, b"mvhd")?)?;
    if (duration_us(movie_time)? - super::micros(source.duration)).abs() > 1 { return Ok(false); }
    let tracks: Vec<_> = movie.iter().filter(|a| &a.kind == b"trak").copied().collect();
    if tracks.len() != 2 { return Ok(false); }
    let mut audio = None;
    let mut video_us = None;
    for track in tracks {
        let track_atoms = reader.inside(track)?;
        let media = reader.inside(one(&track_atoms, b"mdia")?)?;
        let handler = reader.prefix(one(&media, b"hdlr")?, 12)?;
        let time = reader.timing(one(&media, b"mdhd")?)?;
        match &handler[8..12] {
            b"vide" => { ensure!(video_us.is_none(), "duplicate video track"); video_us = Some(duration_us(time)?); }
            b"soun" => {
                ensure!(audio.is_none(), "duplicate audio track");
                let edits = reader.inside(one(&track_atoms, b"edts")?)?;
                audio = Some((time, reader.edit(one(&edits, b"elst")?)?));
            }
            _ => return Ok(false),
        }
    }
    let ((audio_scale, audio_ticks), (edit_duration, priming)) = audio.context("missing AAC duration")?;
    // One 1024-sample AAC priming edit must explain the entire disagreement.
    // This deliberately excludes arbitrary, stretched, empty or chained edits.
    if audio_scale != source.audios[0].sample_rate || priming != 1024 || audio_ticks <= priming
        || edit_duration != movie_time.1
        || u128::from(audio_ticks - priming) * u128::from(movie_time.0)
            != u128::from(movie_time.1) * u128::from(audio_scale) { return Ok(false); }
    let audio_us = duration_us((audio_scale, audio_ticks))?;
    if video_us.context("missing video duration")? > duration_us(movie_time)? { return Ok(false); }
    let android_end = audio_us.checked_add(500).context("MP4 duration overflow")? / 1000 * 1000;
    Ok(end_us == android_end)
}
