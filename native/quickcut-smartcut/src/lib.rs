//! Narrow Android adapter. An unsupported or uncertain source is declined so
//! QuickCut can use its existing exact transcoder. No requested bound is moved.
use anyhow::{ensure, Context, Result};
use ffmpeg_next as ff;
use smartcut_core::{plan::{plan_range, PlanOptions, RangePlan, SegmentKind}, Source};
use std::path::Path;
use std::sync::Arc;
use std::{cmp::Reverse, collections::BinaryHeap};

mod android;
mod avc1;

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

fn unsupported_side_data(kind: ff::codec::packet::side_data::Type) -> bool {
    use ff::codec::packet::side_data::Type::*;
    matches!(kind, NewExtraData | ParamChange | DisplayMatrix | Stereo3d
        | MasteringDisplayMetadata | ContentLightLevel | DataSpherical | DOVI_CONF
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
        ensure!(index >= 0 && (time - origin - index as f64 / self.rate).abs() <= self.tolerance,
            "video timestamps do not follow a constant frame clock");
        self.queued.push(Reverse(index));
        // AVC allows at most sixteen reference pictures. Keep more than that
        // to validate presentation order while reading B frames in decode order.
        if self.queued.len() > 32 { self.pop()?; }
        Ok(())
    }
    fn pop(&mut self) -> Result<()> {
        ensure!(self.queued.pop() == Some(Reverse(self.next)), "duplicate or missing video timestamp");
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
    ensure!(plan.segments.iter().any(|s| s.kind == SegmentKind::Copy), "no complete GOP to copy");
    ensure!(plan.segments.iter().any(|s| s.kind == SegmentKind::Reencode), "native remux already handles complete copies");
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

fn check_source(source: &Source, end_us: i64) -> Result<()> {
    let video = &source.video;
    ensure!(source.read_whole && source.leading_known, "incomplete source index");
    ensure!(source.joins.is_empty() && source.input.range.is_none() && source.input.parts.is_empty(), "compound input");
    ensure!(video.codec == "h264", "only H.264 is supported");
    ensure!(video.shape.pix_fmt == ff::ffi::AVPixelFormat::AV_PIX_FMT_YUV420P as i32, "only 8-bit 4:2:0 video is supported");
    ensure!(video.field_order == ff::ffi::AVFieldOrder::AV_FIELD_PROGRESSIVE as i32
        && video.field_shape.is_none() && !video.pulldown, "interlaced or unknown picture structure");
    ensure!(!video.variable_rate && video.frame_rate.is_finite()
        && video.frame_rate > 0.0 && video.frame_rate <= 120.0, "non-constant or unsupported frame rate");
    ensure!(video.width > 0 && video.height > 0 && video.width <= 8192 && video.height <= 8192, "unsupported dimensions");
    ensure!(![16, 18].contains(&video.shape.transfer)
        && video.shape.primaries != 9 && ![9, 10].contains(&video.shape.matrix), "HDR or wide-gamut video");
    ensure!(source.audios.len() <= 1 && source.audios.iter().all(|a| a.codec == "aac" && (1..=2).contains(&a.channels)), "unsupported audio tracks");
    ensure!(source.captions.is_empty() && source.graphics.is_empty() && source.subpictures.is_empty() && source.dropped.is_empty(), "additional tracks");
    ensure!(source.duration.is_finite() && end_us <= micros(source.duration) + 1, "range exceeds source duration");
    Ok(())
}

/// Inspect actual access-unit payloads, not the container keyframe flag. This
/// also counts the pictures that must survive, catching silent packet loss.
fn inspect_packets(source: &Source, plan: &RangePlan, callbacks: &dyn Callbacks) -> Result<u64> {
    let mut input = smartcut_core::input::open_local(&source.path)?;
    ensure!(input.format().name().split(',').any(|n| n == "mp4"), "input is not MP4");
    ensure!(input.nb_streams() as usize == 1 + source.audios.len(), "additional streams");
    let stream = input.stream(source.video.stream_index).context("missing video stream")?;
    ensure!(stream.metadata().get("rotate").is_none(), "rotation metadata");
    ensure!(!stream.side_data().any(|d| unsupported_side_data(d.kind())), "unsupported video metadata or display transform");
    let parameters = stream.parameters();
    let extradata = unsafe {
        let raw = parameters.as_ptr();
        ensure!(!(*raw).extradata.is_null() && (*raw).extradata_size > 0, "missing AVC configuration");
        std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize)
    };
    let original_sets = smartcut_core::bitstream::parameter_sets("h264", extradata);
    ensure!(!original_sets.is_empty(), "missing AVC parameter sets");
    let length_width = match source.video.framing {
        smartcut_core::bitstream::NalFraming::Length(width) => width,
        _ => anyhow::bail!("MP4 AVC length prefixes required"),
    };
    let mut needed: Vec<i64> = plan.segments.iter().filter(|s| s.kind == SegmentKind::Copy)
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
        ensure!(!packet.flags().contains(ff::codec::packet::Flags::CORRUPT), "corrupt source packet");
        ensure!(!packet.side_data().any(|d| unsupported_side_data(d.kind())), "changing codec configuration or unsupported packet metadata");
        if packet.stream() != source.video.stream_index { continue; }
        let nals = avc_nals(packet.data().context("empty video packet")?, length_width)?;
        ensure!(nals.iter().any(|nal| (1..=5).contains(&(nal[0] & 31))), "packet without an AVC picture");
        if clock.origin.is_none() { ensure!(nals.iter().any(|nal| nal[0] & 31 == 5), "source does not begin at an IDR picture"); }
        ensure!(nals.iter().filter(|nal| matches!(nal[0] & 31, 7 | 8)).all(|nal|
            original_sets.iter().any(|original| original.as_slice() == *nal)), "AVC parameters change inside the source");
        let pts = packet.pts().context("video packet has no presentation timestamp")?;
        let at = pts as f64 * source.video.time_base - source.start_time;
        clock.add(at)?;
        let at_us = micros(at);
        if at_us >= micros(plan.t_in) && at_us < micros(plan.t_out) { count += 1; }
        if needed.contains(&at_us) {
            ensure!(nals.iter().any(|nal| nal[0] & 31 == 5), "copy boundary is not an IDR picture");
            needed.retain(|&t| t != at_us);
        }
    }
    clock.finish()?;
    ensure!(needed.is_empty(), "copy boundary packet was not found");
    ensure!(count > 0, "no retained video pictures");
    Ok(count)
}

pub fn cut(input: &str, output: &str, start_us: i64, end_us: i64, muted: bool,
    callbacks: Arc<dyn Callbacks>) -> Result<Report> {
    ensure!(Path::new(input).is_absolute() && Path::new(output).is_absolute(), "local absolute paths required");
    ensure!(start_us >= 0 && end_us > start_us, "invalid trim range");
    ensure!(!Path::new(output).exists() || std::fs::metadata(output)?.len() == 0, "output must be a fresh staging file");
    ensure!(!callbacks.is_cancelled(), "cancelled");
    let mut staging = StagingFile { path: output, complete: false };
    smartcut_core::init()?;
    let stop = Arc::clone(&callbacks);
    let _reads = smartcut_core::input::stop_reads_when(move || stop.is_cancelled());
    let result = (|| {
        let stopped = || callbacks.is_cancelled();
        let progress = |value: f64| callbacks.progress((value * 15.0).clamp(0.0, 15.0) as i32);
        let source = smartcut_core::scan_stoppable(input, &smartcut_core::index::PacketScan, Some(&progress), Some(&stopped))?;
        check_source(&source, end_us)?;
        let options = PlanOptions { allow_open_gop: false, ..Default::default() };
        // plan_on adjusts coarse-frame clocks. A single plain MP4 needs no join
        // handling, so use plan_range and verify its coordinates unchanged.
        let plan = plan_range(&source.video, source.duration, &source.points,
            start_us as f64 / 1_000_000.0, end_us as f64 / 1_000_000.0, &options);
        check_plan(&plan, start_us, end_us)?;
        let expected = inspect_packets(&source, &plan, callbacks.as_ref())?;
        let encoded_segments = plan.segments.iter().filter(|s| s.kind == SegmentKind::Reencode).count();
        let parameter_set_ids = avc1::unused_ids(input, encoded_segments)?;
        ensure!(!callbacks.is_cancelled(), "cancelled");
        let intermediate_path = format!("{output}.smartcut.mp4");
        ensure!(!Path::new(&intermediate_path).exists(), "intermediate staging file already exists");
        let _intermediate = StagingFile { path: &intermediate_path, complete: false };
        let report_callback = Arc::clone(&callbacks);
        let stats = smartcut_core::cut::cut_with_stats(&source, &[plan], &intermediate_path,
            &smartcut_core::cut::CutOptions {
                strict: true,
                h264_parameter_set_ids: parameter_set_ids,
                audio_mode: smartcut_core::cut::AudioMode::Reencode,
                drop_streams: if muted { source.audios.iter().map(|a| a.stream_index).collect() } else { Vec::new() },
                ..Default::default()
            }, Some(Box::new(move |_, total, _| report_callback.progress(15 + (total * 65.0).clamp(0.0, 65.0) as i32))))?;
        ensure!(stats.copied_video_packets > 0 && stats.encoded_video_packets > 0, "no actual mixed video output");
        ensure!(stats.copied_video_packets + stats.encoded_video_packets == expected, "retained picture count changed");
        let final_video_packets = avc1::remux(&intermediate_path, output, callbacks.as_ref())?;
        ensure!(final_video_packets == expected, "AVC1 remux changed the retained picture count");
        ensure!(!smartcut_core::input::reads_failed(), "input read failed");
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
