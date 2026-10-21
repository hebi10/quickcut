//! Narrow Android adapter. An unsupported or uncertain source is declined so
//! QuickCut can use its existing exact transcoder. No requested bound is moved.
use anyhow::{ensure, Context, Result};
use ffmpeg_next as ff;
use smartcut_core::{plan::{plan_range, PlanOptions, RangePlan, SegmentKind}, Source};
use std::path::Path;
use std::sync::Arc;
use std::{cmp::Reverse, collections::BinaryHeap};

macro_rules! ensure_fast {
    ($condition:expr, $reason:ident, $message:literal) => {
        if !$condition {
            return Err(anyhow::anyhow!($message).context(crate::decline::Reason::$reason));
        }
    };
}

mod android;
mod avc1;
mod avc_sps;
mod duration;
mod display;
mod hevc;
mod hevc_hdr;
mod hevc_splice;
mod decline;
use decline::{explain, Reason};

pub trait Callbacks: Send + Sync {
    fn is_cancelled(&self) -> bool;
    fn progress(&self, percent: i32);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Report {
    pub copied_video_packets: u64,
    pub encoded_video_packets: u64,
}

struct StagingFile<'a> { path: &'a str, complete: bool }
impl Drop for StagingFile<'_> {
    fn drop(&mut self) {
        if !self.complete { let _ = std::fs::remove_file(self.path); }
    }
}

fn micros(seconds: f64) -> i64 { (seconds * 1_000_000.0).round() as i64 }

fn unsupported_side_data(kind: ff::codec::packet::side_data::Type, static_hdr: bool) -> bool {
    use ff::codec::packet::side_data::Type::*;
    (!static_hdr && matches!(kind, MasteringDisplayMetadata | ContentLightLevel))
        || matches!(kind, NewExtraData | ParamChange | DisplayMatrix | Stereo3d
        | DataSpherical | DOVI_CONF
        | DYNAMIC_HDR10_PLUS | ICC_PROFILE | FRAME_CROPPING | LCEVC
        | EncryptionInfo | EncryptionInitInfo)
}

fn avc_nals(data: &[u8], width: usize) -> Result<Vec<&[u8]>> {
    ensure!((1..=4).contains(&width), "unsupported AVC length prefix");
    let mut result = Vec::new();
    let mut at = 0;
    while at < data.len() {
        ensure!(data.len() - at >= width, "truncated AVC length prefix");
        let length = data[at..at + width].iter().fold(0usize, |n, b| (n << 8) | *b as usize);
        at += width;
        ensure!(length > 0 && length <= data.len() - at, "truncated or empty AVC NAL");
        let nal = &data[at..at + length];
        ensure!(nal[0] & 0x80 == 0 && matches!(nal[0] & 31, 1 | 5..=12), "unsupported AVC NAL type");
        result.push(nal);
        at += length;
    }
    ensure!(!result.is_empty(), "empty video packet");
    Ok(result)
}

struct PictureClock {
    origin: Option<f64>,
    rate: f64,
    tolerance: f64,
    queued: BinaryHeap<Reverse<i64>>,
    next: i64,
}
impl PictureClock {
    fn new(rate: f64, tick: f64) -> Self {
        Self { origin: None, rate, tolerance: tick.max(0.000_001) * 1.1,
            queued: BinaryHeap::new(), next: 0 }
    }
    fn add(&mut self, time: f64) -> Result<()> {
        let origin = *self.origin.get_or_insert(time);
        let index = ((time - origin) * self.rate).round() as i64;
        ensure_fast!(index >= 0 && (time - origin - index as f64 / self.rate).abs() <= self.tolerance, Timestamps, "video timestamps do not follow a constant frame clock");
        self.queued.push(Reverse(index));
        // AVC allows at most sixteen reference pictures. Keep more than that
        // to validate presentation order while reading B frames in decode order.
        if self.queued.len() > 32 { self.pop()?; }
        Ok(())
    }
    fn pop(&mut self) -> Result<()> {
        ensure_fast!(self.queued.pop() == Some(Reverse(self.next)), Timestamps, "duplicate or missing video timestamp");
        self.next += 1;
        Ok(())
    }
    fn finish(&mut self) -> Result<()> {
        while !self.queued.is_empty() { self.pop()?; }
        Ok(())
    }
}

/// The upstream planner may omit a sub-frame head or round a coarse clock.
/// Accept only plans that retain the caller's exact microsecond coordinates.
fn check_plan(plan: &RangePlan, start_us: i64, end_us: i64) -> Result<()> {
    ensure!(start_us >= 0 && end_us > start_us, "invalid trim range");
    ensure!(micros(plan.t_in) == start_us && micros(plan.t_out) == end_us,
        "planner would move the requested boundary");
    let first = plan.segments.first().context("empty trim plan")?;
    let last = plan.segments.last().unwrap();
    ensure!(micros(first.start) == start_us && micros(last.end) == end_us,
        "segments would move the requested boundary");
    for segment in &plan.segments {
        ensure!(segment.start.is_finite() && segment.end.is_finite() && segment.end > segment.start,
            "invalid segment bounds");
        ensure!(segment.retouch.is_none(), "effects are not supported");
    }
    for pair in plan.segments.windows(2) {
        ensure!(micros(pair[0].end) == micros(pair[1].start), "gap or overlap in trim plan");
    }
    Ok(())
}

fn check_source(source: &Source, end_us: i64, muted: bool) -> Result<()> {
    let video = &source.video;
    ensure_fast!(source.read_whole && source.leading_known, SourceUnreadable, "incomplete source index");
    ensure_fast!(source.joins.is_empty() && source.input.range.is_none() && source.input.parts.is_empty(), Container, "compound input");
    ensure_fast!(matches!(video.codec.as_str(), "h264" | "hevc"), VideoCodec, "only H.264/HEVC is supported");
    let main10 = video.codec == "hevc"
        && video.shape.pix_fmt == ff::ffi::AVPixelFormat::AV_PIX_FMT_YUV420P10LE as i32;
    ensure_fast!(video.shape.pix_fmt == ff::ffi::AVPixelFormat::AV_PIX_FMT_YUV420P as i32
        || main10, PixelFormat, "only 8-bit AVC/HEVC or 10-bit HEVC 4:2:0 video is supported");
    ensure_fast!(video.field_order == ff::ffi::AVFieldOrder::AV_FIELD_PROGRESSIVE as i32
        && video.field_shape.is_none() && !video.pulldown, PictureStructure, "interlaced or unknown picture structure");
    ensure_fast!(!video.variable_rate && video.frame_rate.is_finite()
        && video.frame_rate > 0.0 && video.frame_rate <= 120.0, FrameRate, "non-constant or unsupported frame rate");
    ensure_fast!(video.width > 0 && video.height > 0 && video.width <= 8192 && video.height <= 8192, Dimensions, "unsupported dimensions");
    let extended_color = [16, 18].contains(&video.shape.transfer)
        || video.shape.primaries == 9 || [9, 10].contains(&video.shape.matrix);
    ensure_fast!(!extended_color || (main10 && video.shape.primaries == 9
        && [9, 10].contains(&video.shape.matrix) && [14, 16, 18].contains(&video.shape.transfer)),
        ColorFormat, "unsupported HDR or wide-gamut configuration");
    ensure_fast!(source.audios.len() <= 1, TrackLayout, "multiple audio tracks");
    ensure_fast!(muted || source.audios.iter().all(|a| matches!(a.codec.as_str(),
        "aac" | "mp3" | "ac3" | "eac3" | "opus" | "flac" | "alac"
        | "pcm_s16le" | "pcm_s24le" | "pcm_s32le" | "pcm_f32le")
        && (1..=8).contains(&a.channels)), AudioFormat, "unsupported audio tracks");
    ensure_fast!(source.captions.is_empty() && source.graphics.is_empty() && source.subpictures.is_empty() && source.dropped.is_empty(), TrackLayout, "additional tracks");
    ensure_fast!(source.duration.is_finite(), SourceDuration, "invalid source duration");
    // Android's MP4 extractor reports raw media-header lengths, including AAC
    // priming that FFmpeg removes using the edit list. Recognize that exact
    // whole-file selection without clamping the caller's requested boundary.
    ensure_fast!(end_us <= micros(source.duration) + 1
        || duration::is_android_aac_eof(source, end_us)?, SourceDuration, "range exceeds source duration");
    Ok(())
}

/// Inspect actual access-unit payloads, not the container keyframe flag. This
/// also counts the pictures that must survive, catching silent packet loss.
fn inspect_packets(source: &Source, plans: &[RangePlan], callbacks: &dyn Callbacks) -> Result<u64> {
    let mut input = smartcut_core::input::open_local(&source.path)?;
    ensure_fast!(input.format().name().split(',').any(|n| n == "mp4"), Container, "input is not MP4");
    ensure_fast!(input.nb_streams() as usize == 1 + source.audios.len(), TrackLayout, "additional streams");
    let stream = input.stream(source.video.stream_index).context("missing video stream")?;
    let static_hdr = source.video.codec == "hevc" && [16, 18].contains(&source.video.shape.transfer);
    ensure_fast!(stream.metadata().get("rotate").is_none(), Rotation, "rotation metadata");
    ensure_fast!(!stream.side_data().any(|d| d.kind() != ff::codec::packet::side_data::Type::DisplayMatrix
        && unsupported_side_data(d.kind(), static_hdr)), Metadata, "unsupported video metadata");
    let parameters = stream.parameters();
    let extradata = unsafe {
        let raw = parameters.as_ptr();
        ensure_fast!(!(*raw).extradata.is_null() && (*raw).extradata_size > 0, CodecConfiguration, "missing AVC configuration");
        std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize)
    };
    let is_hevc = source.video.codec == "hevc";
    if is_hevc { explain(hevc::validate_configuration(extradata), Reason::CodecConfiguration)?; }
    let mut hdr = if is_hevc { Some(explain(hevc_hdr::Guard::new(extradata, static_hdr,
        source.video.shape.transfer), Reason::Metadata)?) } else { None };
    if let Some(guard) = &mut hdr {
        for side in stream.side_data() {
            explain(guard.observe_side_data(side.kind(), side.data()), Reason::Metadata)?;
        }
    }
    let original_sets = smartcut_core::bitstream::parameter_sets(&source.video.codec, extradata);
    ensure_fast!(!original_sets.is_empty(), CodecConfiguration, "missing AVC parameter sets");
    let length_width = match source.video.framing {
        smartcut_core::bitstream::NalFraming::Length(width) => width,
        _ => anyhow::bail!("MP4 AVC length prefixes required"),
    };
    let mut needed: Vec<i64> = plans.iter().flat_map(|p| &p.segments).filter(|s| s.kind == SegmentKind::Copy)
        .flat_map(|s| std::iter::once(micros(s.start)).chain(s.copy_until.map(micros))).collect();
    needed.sort_unstable(); needed.dedup();
    let mut count = 0u64;
    let mut packets = 0u64;
    let mut clock = PictureClock::new(source.video.frame_rate, source.video.time_base);
    loop {
        if packets % 64 == 0 { ensure!(!callbacks.is_cancelled(), "cancelled"); }
        let mut packet = ff::Packet::empty();
        match packet.read(&mut input) {
            Ok(()) => (),
            Err(ff::Error::Eof) => break,
            Err(error) => return Err(error.into()),
        }
        packets += 1;
        ensure_fast!(!packet.flags().contains(ff::codec::packet::Flags::CORRUPT), PacketStructure, "corrupt source packet");
        ensure_fast!(!packet.side_data().any(|d| unsupported_side_data(d.kind(), static_hdr)), Metadata, "changing codec configuration or unsupported packet metadata");
        if packet.stream() != source.video.stream_index { continue; }
        if let Some(guard) = &mut hdr {
            for side in packet.side_data() {
                explain(guard.observe_side_data(side.kind(), side.data()), Reason::Metadata)?;
            }
        }
        let bytes = packet.data().context("empty video packet")?;
        let nals = if is_hevc { hevc::nals(bytes, length_width)? } else { avc_nals(bytes, length_width)? };
        if let Some(guard) = &mut hdr { explain(guard.inspect(&nals), Reason::Metadata)?; }
        let kind = |nal: &[u8]| if is_hevc { (nal[0] >> 1) & 63 } else { nal[0] & 31 };
        let idr = nals.iter().any(|nal| if is_hevc { matches!(kind(nal), 19 | 20) } else { kind(nal) == 5 });
        ensure_fast!(nals.iter().any(|nal| if is_hevc { kind(nal) <= 31 } else { (1..=5).contains(&kind(nal)) }), PacketStructure, "packet without a coded picture");
        if clock.origin.is_none() { ensure_fast!(idr, UnsafeSplice, "source does not begin at an IDR picture"); }
        ensure_fast!(nals.iter().filter(|nal| if is_hevc { matches!(kind(nal), 32..=34) } else { matches!(kind(nal), 7 | 8) }).all(|nal|
            original_sets.iter().any(|original| original.as_slice() == *nal)), Metadata, "parameters change inside the source");
        let pts = explain(packet.pts().context("video packet has no presentation timestamp"), Reason::Timestamps)?;
        let at = pts as f64 * source.video.time_base - source.start_time;
        clock.add(at)?;
        let at_us = micros(at);
        // Count every selected occurrence, including overlapping/repeated ranges.
        // Validate the source clock once, independently of the output ordering.
        count += plans.iter().filter(|p| at_us >= micros(p.t_in) && at_us < micros(p.t_out)).count() as u64;
        if needed.contains(&at_us) {
            ensure_fast!(idr, UnsafeSplice, "copy boundary is not an IDR picture");
            needed.retain(|&t| t != at_us);
        }
    }
    clock.finish()?;
    ensure_fast!(needed.is_empty(), UnsafeSplice, "copy boundary packet was not found");
    ensure_fast!(count > 0, NoCopySegment, "no retained video pictures");
    Ok(count)
}

pub fn cut(input: &str, output: &str, start_us: i64, end_us: i64, muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Report> {
    cut_ranges(input, output, &[(start_us, end_us)], muted, callbacks)
}

/// Assemble exact ranges from one source in the caller's order. The source is
/// indexed once; every encoded boundary gets its own AVC parameter-set ID.
pub fn cut_ranges(input: &str, output: &str, ranges: &[(i64, i64)], muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Report> {
    ensure!(Path::new(input).is_absolute() && Path::new(output).is_absolute(), "local absolute paths required");
    ensure!(!ranges.is_empty() && ranges.len() <= 256, "unsupported range count");
    ensure!(ranges.iter().all(|&(start, end)| start >= 0 && end > start), "invalid trim range");
    ensure!(!Path::new(output).exists() || std::fs::metadata(output)?.len() == 0, "output must be a fresh staging file");
    ensure!(!callbacks.is_cancelled(), "cancelled");
    let mut staging = StagingFile { path: output, complete: false };
    smartcut_core::init()?;
    let stop = Arc::clone(&callbacks);
    let _reads = smartcut_core::input::stop_reads_when(move || stop.is_cancelled());
    let result = (|| {
        let stopped = || callbacks.is_cancelled();
        let progress = |value: f64| callbacks.progress((value * 15.0).clamp(0.0, 15.0) as i32);
        let source = explain(smartcut_core::scan_stoppable(input, &smartcut_core::index::PacketScan, Some(&progress), Some(&stopped)), Reason::SourceUnreadable)?;
        for &(_, end) in ranges { check_source(&source, end, muted)?; }
        let options = PlanOptions { allow_open_gop: false, ..Default::default() };
        // plan_on adjusts coarse-frame clocks. A single plain MP4 needs no join
        // handling, so use plan_range and verify its coordinates unchanged.
        let plans = ranges.iter().map(|&(start, end)| {
            let plan = plan_range(&source.video, source.duration, &source.points,
                start as f64 / 1_000_000.0, end as f64 / 1_000_000.0, &options);
            explain(check_plan(&plan, start, end), Reason::ExactBoundary)?;
            Ok(plan)
        }).collect::<Result<Vec<_>>>()?;
        ensure_fast!(plans.iter().flat_map(|p| &p.segments).any(|s| s.kind == SegmentKind::Copy), NoCopySegment, "no complete GOP to copy");
        let encoded_segments = plans.iter().flat_map(|p| &p.segments)
            .filter(|s| s.kind == SegmentKind::Reencode).count();
        let expected = explain(inspect_packets(&source, &plans, callbacks.as_ref()), Reason::PacketStructure)?;
        let display = explain(display::Display::read(input), Reason::Rotation)?;
        let (parameter_set_ids, reference_frames) = if source.video.codec == "h264" {
            let (ids, refs) = explain(avc1::unused_ids(input, encoded_segments), Reason::CodecConfiguration)?;
            (ids, Some(refs))
        } else { (Vec::new(), None) };
        ensure!(!callbacks.is_cancelled(), "cancelled");
        let intermediate_path = format!("{output}.smartcut.mp4");
        ensure!(!Path::new(&intermediate_path).exists(), "intermediate staging file already exists");
        let _intermediate = StagingFile { path: &intermediate_path, complete: false };
        let report_callback = Arc::clone(&callbacks);
        let stats = explain(smartcut_core::cut::cut_with_stats(&source, &plans, &intermediate_path,
            &smartcut_core::cut::CutOptions {
                strict: true,
                h264_parameter_set_ids: parameter_set_ids,
                h264_reference_frames: reference_frames,
                audio_mode: smartcut_core::cut::AudioMode::Reencode,
                audio_codec: smartcut_core::cut::AudioCodec::Aac,
                drop_streams: if muted { source.audios.iter().map(|a| a.stream_index).collect() } else { Vec::new() },
                ..Default::default()
            }, Some(Box::new(move |_, total, _| report_callback.progress(15 + (total * 65.0).clamp(0.0, 65.0) as i32)))), Reason::ProcessingFailed)?;
        ensure_fast!(stats.copied_video_packets > 0, NoCopySegment, "no actual copied video output");
        ensure_fast!(stats.copied_video_packets + stats.encoded_video_packets == expected, OutputValidation, "retained picture count changed");
        let final_video_packets = if source.video.codec == "hevc" {
            explain(hevc::remux(&intermediate_path, output, &display, callbacks.as_ref(),
                &stats.copied_segment_starts_us), Reason::OutputValidation)?
        } else {
            explain(avc1::remux(&intermediate_path, output, &display, callbacks.as_ref()), Reason::OutputValidation)?
        };
        ensure_fast!(final_video_packets == expected, OutputValidation, "final remux changed the retained picture count");
        ensure_fast!(!smartcut_core::input::reads_failed(), SourceUnreadable, "input read failed");
        ensure!(!smartcut_core::input::reads_stopped() && !callbacks.is_cancelled(), "cancelled");
        callbacks.progress(100);
        ensure!(!callbacks.is_cancelled(), "cancelled");
        Ok(Report { copied_video_packets: stats.copied_video_packets, encoded_video_packets: stats.encoded_video_packets })
    })();
    staging.complete = result.is_ok();
    result
}

#[cfg(test)]
mod tests;
