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
mod audio_config;
mod avc1;
mod avc_sps;
mod duration;
mod display;
mod hevc;
mod hevc_hdr;
mod hevc_splice;
mod decline;
mod analysis_cache;
mod packet_inspection;
mod candidates;
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

// Preserve the stable source eligibility reasons before codec-specific
// payload inspection, then recheck after the index supplies cadence facts.
fn check_video(video: &smartcut_core::VideoInfo) -> Result<()> {
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
    Ok(())
}

fn check_source(source: &Source, end_us: i64, muted: bool) -> Result<()> {
    ensure_fast!(source.read_whole && source.leading_known, SourceUnreadable, "incomplete source index");
    ensure_fast!(source.joins.is_empty() && source.input.range.is_none() && source.input.parts.is_empty(), Container, "compound input");
    check_video(&source.video)?;
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

/// Facts from a complete successful payload/clock inspection. Retain actual
/// timestamps so fractional frame rates and repeated ranges count precisely.
struct PacketFacts {
    picture_times_us: Box<[i64]>,
    idr_times_us: Box<[i64]>,
}
impl PacketFacts {
    fn retained_bytes(&self) -> usize {
        (self.picture_times_us.len() + self.idr_times_us.len()) * std::mem::size_of::<i64>()
    }

    fn check_plans(&self, plans: &[RangePlan]) -> Result<u64> {
        for boundary in plans.iter().flat_map(|p| &p.segments)
            .filter(|s| s.kind == SegmentKind::Copy)
            .flat_map(|s| std::iter::once(micros(s.start)).chain(s.copy_until.map(micros))) {
            ensure_fast!(self.idr_times_us.binary_search(&boundary).is_ok(), UnsafeSplice,
                "copy boundary is not a validated IDR picture");
        }
        let count = plans.iter().map(|plan| {
            let start = self.picture_times_us.partition_point(|&t| t < micros(plan.t_in));
            let end = self.picture_times_us.partition_point(|&t| t < micros(plan.t_out));
            (end - start) as u64
        }).sum();
        ensure_fast!(count > 0, NoCopySegment, "no retained video pictures");
        Ok(count)
    }
}


pub fn cut(input: &str, output: &str, start_us: i64, end_us: i64, muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Report> {
    cut_ranges(input, output, &[(start_us, end_us)], muted, callbacks)
}

/// Assemble exact ranges from one source in the caller's order. The source is
/// indexed once; every encoded boundary gets its own AVC parameter-set ID.
pub fn cut_ranges(input: &str, output: &str, ranges: &[(i64, i64)], muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Report> {
    cut_ranges_impl(input, output, ranges, muted, false, callbacks)
}

/// The user's fast-only choice permits audio conversion, never video encoding.
pub fn cut_ranges_copy_video(input: &str, output: &str, ranges: &[(i64, i64)], muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Report> {
    cut_ranges_impl(input, output, ranges, muted, true, callbacks)
}

/// Return inward-only ranges at the actual microsecond boundaries. These are
/// proposals: saving still verifies the current input and cannot encode video.
pub fn suggest_copy_ranges(input: &str, ranges: &[(i64, i64)], source_end_us: i64, muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Vec<(i64, i64)>> {
    ensure!(Path::new(input).is_absolute(), "local absolute path required");
    ensure!(!ranges.is_empty() && ranges.len() <= 256, "unsupported range count");
    ensure!(ranges.iter().all(|&(start, end)| start >= 0 && end > start && end <= source_end_us), "invalid range");
    ensure!(!callbacks.is_cancelled(), "cancelled");
    smartcut_core::init()?;
    let stop = Arc::clone(&callbacks);
    let _reads = smartcut_core::input::stop_reads_when(move || stop.is_cancelled());
    let progress = |percent| callbacks.progress(percent * 90 / 100);
    let lease = analysis_cache::load(input, callbacks.as_ref(), &progress)?;
    let source = &lease.source;
    for &(_, end) in ranges { check_source(source, end, muted)?; }
    explain(audio_config::validate_export(source, muted), Reason::AudioFormat)?;
    let mut points = Vec::new();
    for (index, point) in source.points.iter().enumerate() {
        if index % 256 == 0 { ensure!(!callbacks.is_cancelled(), "cancelled"); }
        let time = micros(point.time);
        if !point.open_gop() && point.measured && time >= 0
            && lease.analysis.packets.idr_times_us.binary_search(&time).is_ok() {
            points.push(time);
        }
    }
    points.sort_unstable();
    points.dedup();
    let options = PlanOptions { allow_open_gop: false, ..Default::default() };
    let mut budget = candidates::MAX_TOTAL_CANDIDATE_PLANS;
    let mut proposed = Vec::with_capacity(ranges.len());
    for (index, &(start, end)) in ranges.iter().enumerate() {
        let range = candidates::choose_range(&points, start, end, source_end_us,
            callbacks.as_ref(), &mut budget, |from, to| {
                let plan = plan_range(&source.video, source.duration, &source.points,
                    from as f64 / 1_000_000.0, to as f64 / 1_000_000.0, &options);
                check_plan(&plan, from, to).is_ok()
                    && plan.segments.iter().all(|s| s.kind == SegmentKind::Copy)
                    && lease.analysis.packets.check_plans(&[plan]).is_ok()
            })?;
        proposed.push(range);
        callbacks.progress(90 + ((index + 1) * 9 / ranges.len()) as i32);
    }
    lease.verify(input, callbacks.as_ref())?;
    callbacks.progress(100);
    lease.verify(input, callbacks.as_ref())?;
    Ok(proposed)
}

fn cut_ranges_impl(input: &str, output: &str, ranges: &[(i64, i64)], muted: bool,
    copy_video_only: bool, callbacks: Arc<dyn Callbacks>) -> Result<Report> {
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
        let progress = |percent| callbacks.progress(percent * 15 / 100);
        let lease = analysis_cache::load(input, callbacks.as_ref(), &progress)?;
        let source = &lease.source;
        for &(_, end) in ranges { check_source(source, end, muted)?; }
        explain(audio_config::validate_export(source, muted), Reason::AudioFormat)?;
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
        ensure_fast!(!copy_video_only || encoded_segments == 0, ExactBoundary,
            "fast-only consent does not allow video encoding");
        let expected = explain(lease.analysis.packets.check_plans(&plans), Reason::PacketStructure)?;
        let display = &lease.analysis.display;
        let (parameter_set_ids, reference_frames) = if source.video.codec == "h264" && encoded_segments > 0 {
            let (ids, refs) = explain(avc1::unused_ids(input, encoded_segments), Reason::CodecConfiguration)?;
            (ids, Some(refs))
        } else { (Vec::new(), None) };
        lease.verify(input, callbacks.as_ref())?;
        let intermediate_path = format!("{output}.smartcut.mp4");
        ensure!(!Path::new(&intermediate_path).exists(), "intermediate staging file already exists");
        let _intermediate = StagingFile { path: &intermediate_path, complete: false };
        let report_callback = Arc::clone(&callbacks);
        let stats = explain(smartcut_core::cut::cut_with_stats(source, &plans, &intermediate_path,
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
        ensure_fast!(!copy_video_only || stats.encoded_video_packets == 0, OutputValidation,
            "fast-only output unexpectedly encoded video");
        ensure_fast!(stats.copied_video_packets + stats.encoded_video_packets == expected, OutputValidation, "retained picture count changed");
        let final_video_packets = if source.video.codec == "hevc" {
            explain(hevc::remux(&intermediate_path, output, display, callbacks.as_ref(),
                &stats.copied_segment_starts_us, source.video.frame_rate), Reason::OutputValidation)?
        } else {
            explain(avc1::remux(&intermediate_path, output, display, callbacks.as_ref(),
                source.video.frame_rate), Reason::OutputValidation)?
        };
        ensure_fast!(final_video_packets == expected, OutputValidation, "final remux changed the retained picture count");
        lease.verify(input, callbacks.as_ref())?;
        callbacks.progress(100);
        lease.verify(input, callbacks.as_ref())?;
        Ok(Report { copied_video_packets: stats.copied_video_packets, encoded_video_packets: stats.encoded_video_packets })
    })();
    staging.complete = result.is_ok();
    result
}

#[cfg(test)]
mod tests;
