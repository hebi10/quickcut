use super::*;
use smartcut_core::plan::Segment;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

fn segment(kind: SegmentKind, start: f64, end: f64) -> Segment {
    Segment { kind, start, end, frames: 1, copy_until: Some(end), seek_from: 0.0, retouch: None }
}

#[test]
fn rejects_near_keyframe_start_rewrite() {
    let plan = RangePlan { t_in: 2.0, t_out: 5.5, segments: vec![
        segment(SegmentKind::Copy, 2.0, 4.0), segment(SegmentKind::Reencode, 4.0, 5.5)] };
    assert!(check_plan(&plan, 1_980_000, 5_500_000).is_err());
    assert!(check_plan(&plan, 2_010_000, 5_500_000).is_err());
}

#[test]
fn malformed_avc_access_units_are_declined_before_copy() {
    assert!(avc_nals(&[0, 0, 0, 2, 0x65, 0x80], 4).is_ok());
    assert!(avc_nals(&[0, 0, 0, 3, 0x65, 0x80], 4).is_err());
    assert!(avc_nals(&[0, 0, 0, 1, 0xe5], 4).is_err());
    assert!(avc_nals(&[0, 0, 0, 1, 0x74], 4).is_err());
    assert!(avc_nals(&[0, 0, 0, 0], 4).is_err());
}

#[test]
fn actual_pts_accept_b_frames_but_reject_vfr_duplicate_and_missing_pictures() {
    let mut clock = PictureClock::new(30.0, 1.0 / 15_360.0);
    for frame in [0, 3, 1, 2, 6, 4, 5] { clock.add(frame as f64 / 30.0).unwrap(); }
    clock.finish().unwrap();
    let mut duplicate = PictureClock::new(30.0, 1.0 / 15_360.0);
    for frame in [0, 1, 1, 2] { duplicate.add(frame as f64 / 30.0).unwrap(); }
    assert!(duplicate.finish().is_err());
    let mut missing = PictureClock::new(30.0, 1.0 / 15_360.0);
    for frame in [0, 1, 3] { missing.add(frame as f64 / 30.0).unwrap(); }
    assert!(missing.finish().is_err());
    let mut vfr = PictureClock::new(30.0, 1.0 / 15_360.0);
    vfr.add(0.0).unwrap();
    assert!(vfr.add(0.05).is_err());
}

#[test]
fn rejects_hidden_segment_shift_and_gap() {
    let mut plan = RangePlan { t_in: 1.95, t_out: 5.5, segments: vec![
        segment(SegmentKind::Reencode, 1.95, 2.0), segment(SegmentKind::Copy, 2.0, 4.0),
        segment(SegmentKind::Reencode, 4.0, 5.5)] };
    assert!(check_plan(&plan, 1_950_000, 5_500_000).is_ok());
    plan.segments[0].start = 1.951;
    assert!(check_plan(&plan, 1_950_000, 5_500_000).is_err());
    plan.segments[0].start = 1.95;
    plan.segments[1].start = 2.001;
    assert!(check_plan(&plan, 1_950_000, 5_500_000).is_err());
}

#[derive(Default)]
struct Observer { cancelled: AtomicBool, progress: AtomicI32 }
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { self.cancelled.load(Ordering::Relaxed) }
    fn progress(&self, value: i32) { self.progress.store(value, Ordering::Relaxed); }
}

struct CancelDuringWrite { output: std::path::PathBuf, fired: AtomicBool }
impl Callbacks for CancelDuringWrite {
    fn is_cancelled(&self) -> bool { self.fired.load(Ordering::Relaxed) }
    fn progress(&self, value: i32) {
        if value > 15 && value < 99 && (self.output.exists() || std::path::Path::new(&format!("{}.smartcut.mp4", self.output.display())).exists()) {
            self.fired.store(true, Ordering::Relaxed);
        }
    }
}

#[test]
fn cancellation_does_not_create_output() {
    let output = std::env::temp_dir().join(format!("quickcut-cancel-{}.mp4", std::process::id()));
    let callbacks = Arc::new(Observer::default());
    callbacks.cancelled.store(true, Ordering::Relaxed);
    assert!(cut("/not/opened.mp4", output.to_str().unwrap(), 1, 2, false, callbacks).is_err());
    assert!(!output.exists());
}

/// Real codec test: two arbitrary cuts must retain their requested coordinates,
/// preserve a whole middle GOP's VCL bytes, and decode all output frames.
#[test]
fn h264_b_frames_and_aac_real_mixed_cut() {
    use std::process::Command;
    let directory = std::env::temp_dir().join(format!("quickcut-smartcut-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let input = directory.join("source.mp4");
    let status = Command::new("ffmpeg").args(["-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=8",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=8",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "60", "-keyint_min", "60",
        "-sc_threshold", "0", "-bf", "2", "-x264-params", "open-gop=0", "-c:a", "aac",
        "-movflags", "+faststart"]).arg(&input).status().expect("host tests require ffmpeg CLI");
    assert!(status.success());
    let before = std::fs::read(&input).unwrap();
    for (start, end, expected_encoded) in [(1_950_000, 5_500_000, 46), (3_000_000, 7_500_000, 75)] {
        let output = directory.join(format!("cut-{start}.mp4"));
        let _ = std::fs::remove_file(&output);
        let observer = Arc::new(Observer::default());
        let report = cut(input.to_str().unwrap(), output.to_str().unwrap(), start, end, false, observer.clone()).unwrap();
        let encoded = ff::format::input(&output).unwrap();
        let video = encoded.streams().best(ff::media::Type::Video).unwrap();
        assert_eq!(unsafe { (*video.parameters().as_ptr()).codec_tag }, u32::from_le_bytes(*b"avc1"), "Android requires an avc1 sample entry with a complete fixed configuration");
        let parameters = video.parameters();
        let config = unsafe { let p = parameters.as_ptr(); std::slice::from_raw_parts((*p).extradata, (*p).extradata_size as usize) };
        let all_sets = smartcut_core::bitstream::parameter_sets("h264", config);
        assert_eq!(all_sets.iter().filter(|n| n[0] & 31 == 7).count(), 3, "source, head and tail SPS must all be in the fixed avcC");
        assert_eq!(all_sets.iter().filter(|n| n[0] & 31 == 8).count(), 3, "source, head and tail PPS must all be in the fixed avcC");
        assert_eq!(report.copied_video_packets, 60);
        assert_eq!(report.encoded_video_packets, expected_encoded);
        assert_eq!(observer.progress.load(Ordering::Relaxed), 100);
        assert!(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"]).arg(&output)
            .args(["-f", "null", "-"]).status().unwrap().success());
        let source_vcl = vcl_packets(input.to_str().unwrap());
        let output_vcl = vcl_packets(output.to_str().unwrap());
        // The test pattern is animated, making each coded picture distinct.
        let copies = output_vcl.iter().filter(|nal| source_vcl.contains(nal)).count();
        assert_eq!(copies, 60, "actual unchanged VCL payloads must match reported copies");
        if start == 3_000_000 {
            // Inspect a real, successfully decoded avc1 file containing the
            // original/head/tail configurations, not a synthetic map.
            let error = avc1::unused_ids(output.to_str().unwrap(), 2)
                .expect_err("several source SPS/PPS pairs must use the fallback");
            assert!(error.to_string().contains("multiple source AVC configurations"), "wrong decline reason: {error:#}");
        }
    }
    for start in [1_980_000, 2_010_000] {
        let output = directory.join(format!("decline-{start}.mp4"));
        assert!(cut(input.to_str().unwrap(), output.to_str().unwrap(), start, 5_500_000, false, Arc::new(Observer::default())).is_err());
        assert!(!output.exists());
    }
    let muted = directory.join("muted.mp4");
    let _ = std::fs::remove_file(&muted);
    assert!(cut(input.to_str().unwrap(), muted.to_str().unwrap(), 3_000_000, 7_500_000, true, Arc::new(Observer::default())).is_ok());
    let media = ff::format::input(&muted).unwrap();
    assert_eq!(media.nb_streams(), 1);
    let cancelled_output = directory.join("cancel-during-write.mp4");
    let cancelled = Arc::new(CancelDuringWrite { output: cancelled_output.clone(), fired: AtomicBool::new(false) });
    assert!(cut(input.to_str().unwrap(), cancelled_output.to_str().unwrap(), 1_950_000, 7_500_000, false, cancelled.clone()).is_err());
    assert!(cancelled.fired.load(Ordering::Relaxed), "cancellation must happen after actual writing began");
    assert!(!cancelled_output.exists(), "partial staging output must be removed");
    assert!(!std::path::Path::new(&format!("{}.smartcut.mp4", cancelled_output.display())).exists(), "intermediate output must be removed after cancellation");
    #[cfg(unix)]
    {
        use std::os::fd::AsRawFd;
        use std::io::{Seek, SeekFrom};
        let mut descriptor = std::fs::File::open(&input).unwrap();
        descriptor.seek(SeekFrom::Start(123)).unwrap();
        let fd_path = format!("/proc/self/fd/{}", descriptor.as_raw_fd());
        let fd_output = directory.join("from-descriptor.mp4");
        let report = cut(&fd_path, fd_output.to_str().unwrap(), 3_000_000, 7_500_000, false, Arc::new(Observer::default())).unwrap();
        assert_eq!(report.copied_video_packets, 60);
        assert_eq!(descriptor.stream_position().unwrap(), 123, "native reads must not change the caller's descriptor offset");
    }
    assert_eq!(std::fs::read(&input).unwrap(), before, "original input must remain unchanged");
    std::fs::remove_dir_all(directory).unwrap();
}

fn vcl_packets(path: &str) -> Vec<Vec<u8>> {
    let mut input = ff::format::input(path).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let mut packets = Vec::new();
    for (stream, packet) in input.packets() {
        if stream.index() != video { continue; }
        let data = packet.data().unwrap();
        let mut at = 0;
        let mut picture = Vec::new();
        while at + 4 <= data.len() {
            let len = u32::from_be_bytes(data[at..at+4].try_into().unwrap()) as usize;
            at += 4;
            assert!(len > 0 && at + len <= data.len());
            assert!(!matches!(data[at] & 31, 7 | 8), "avc1 samples must not carry changing parameter sets");
            if (1..=5).contains(&(data[at] & 31)) { picture.extend_from_slice(&data[at..at+len]); }
            at += len;
        }
        assert_eq!(at, data.len());
        if !picture.is_empty() { packets.push(picture); }
    }
    packets
}

#[test]
fn multiple_ranges_keep_order_repeated_pictures_and_original_gops() {
    use std::process::Command;
    let directory = std::env::temp_dir().join(format!("quickcut-multi-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let input = directory.join("source.mp4");
    assert!(Command::new("ffmpeg").args(["-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=8",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=8",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "60", "-keyint_min", "60",
        "-sc_threshold", "0", "-bf", "2", "-x264-params", "open-gop=0", "-c:a", "aac"])
        .arg(&input).status().unwrap().success());
    let before = std::fs::read(&input).unwrap();
    let source_vcl = vcl_packets(input.to_str().unwrap());
    for (name, ranges, copied, encoded) in [
        ("join", vec![(0, 3_000_000), (4_500_000, 8_000_000)], 120, 75),
        ("copy", vec![(0, 2_000_000), (4_000_000, 6_000_000)], 120, 0),
        ("repeated", vec![(4_500_000, 8_000_000), (0, 3_000_000), (4_500_000, 8_000_000)], 180, 120),
    ] {
        let output = directory.join(format!("{name}.mp4"));
        let _ = std::fs::remove_file(&output);
        let muted = name == "repeated";
        let report = cut_ranges(input.to_str().unwrap(), output.to_str().unwrap(), &ranges,
            muted, Arc::new(Observer::default())).unwrap();
        assert_eq!(report.copied_video_packets, copied);
        assert_eq!(report.encoded_video_packets, encoded);
        assert_eq!(ff::format::input(&output).unwrap().nb_streams(), if muted { 1 } else { 2 });
        assert!(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"]).arg(&output)
            .args(["-f", "null", "-"]).status().unwrap().success());
        let result = vcl_packets(output.to_str().unwrap());
        assert_eq!(result.len(), (copied + encoded) as usize);
        if name == "join" {
            assert_eq!(result[..60], source_vcl[..60]);
            assert_eq!(result[135..195], source_vcl[180..240]);
        } else if name == "copy" {
            assert_eq!(result[..60], source_vcl[..60]);
            assert_eq!(result[60..120], source_vcl[120..180]);
        } else {
            assert_eq!(result[45..105], source_vcl[180..240]);
            assert_eq!(result[105..165], source_vcl[..60]);
            assert_eq!(result[240..300], source_vcl[180..240]);
        }
    }
    for ranges in [vec![(4_500_000, 8_000_000), (0, 3_000_000)],
        vec![(0, 3_000_000), (2_500_000, 7_500_000)]] {
        let output = directory.join("revisited-audio.mp4");
        let _ = std::fs::remove_file(&output);
        cut_ranges(input.to_str().unwrap(), output.to_str().unwrap(), &ranges,
            false, Arc::new(Observer::default())).unwrap();
        let audio = Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"]).arg(&output)
            .args(["-vn", "-ac", "1", "-ar", "48000", "-f", "s16le", "-"]).output().unwrap();
        assert!(audio.status.success());
        let samples: Vec<i16> = audio.stdout.chunks_exact(2).map(|b| i16::from_le_bytes(b.try_into().unwrap())).collect();
        let expected_us: i64 = ranges.iter().map(|(a, b)| b - a).sum();
        assert!(((samples.len() as i64 * 1_000_000 / 48000) - expected_us).abs() < 80_000);
        for window in samples.chunks_exact(12000) {
            let amplitude: f64 = window.iter().map(|s| f64::from(*s).abs()).sum::<f64>() / window.len() as f64;
            assert!(amplitude > 100.0, "revisited source audio must remain audible: {amplitude}");
        }
    }
    let cancelled_output = directory.join("cancelled.mp4");
    let cancelled = Arc::new(CancelDuringWrite { output: cancelled_output.clone(), fired: AtomicBool::new(false) });
    assert!(cut_ranges(input.to_str().unwrap(), cancelled_output.to_str().unwrap(),
        &[(0, 3_000_000), (4_500_000, 8_000_000)], false, cancelled.clone()).is_err());
    assert!(cancelled.fired.load(Ordering::Relaxed));
    assert!(!cancelled_output.exists());
    assert!(!std::path::Path::new(&format!("{}.smartcut.mp4", cancelled_output.display())).exists());
    let rejected = directory.join("shifted-second-range.mp4");
    assert!(cut_ranges(input.to_str().unwrap(), rejected.to_str().unwrap(),
        &[(0, 3_000_000), (1_980_000, 5_500_000)], true, Arc::new(Observer::default())).is_err());
    assert!(!rejected.exists(), "every range must preserve its exact requested coordinates");
    assert_eq!(std::fs::read(&input).unwrap(), before);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn android_aac_media_duration_keeps_the_full_requested_eof_range() {
    use std::process::Command;
    let directory = std::env::temp_dir().join(format!("quickcut-android-eof-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let input = directory.join("source.mp4");
    assert!(Command::new("ffmpeg").args(["-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=red:size=320x240:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-vf", "drawbox=x=0:y=0:w=iw:h=ih:color=blue:t=fill:enable='gte(t,2)'",
        "-t", "6", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-bf", "0", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0",
        "-c:a", "aac", "-b:a", "64k", "-shortest"]).arg(&input).status().unwrap().success());
    let source = smartcut_core::scan(input.to_str().unwrap()).unwrap();
    assert_eq!(micros(source.duration), 6_000_000);
    // Android takes the longest mdhd duration: 265624 / 44100 seconds,
    // rounded to milliseconds. FFmpeg instead applies the 1024-sample AAC
    // priming edit and reports the 6-second presentation/movie duration.
    let requested_end = 6_023_000;
    let output = directory.join("cut.mp4");
    let _ = std::fs::remove_file(&output);
    let report = cut(input.to_str().unwrap(), output.to_str().unwrap(), 1_950_000,
        requested_end, false, Arc::new(Observer::default())).unwrap();
    assert_eq!(report.copied_video_packets, 120);
    assert_eq!(report.encoded_video_packets, 1);
    let mut result = ff::format::input(&output).unwrap();
    assert!((result.duration() - (requested_end - 1_950_000)).abs() <= 34_000,
        "output must retain the Android-selected EOF interval without broad duration tolerance");
    let audio = result.streams().best(ff::media::Type::Audio).unwrap().index();
    let mut last_audio_end = 0;
    for (stream, packet) in result.packets() {
        if stream.index() == audio {
            let end = packet.pts().unwrap() + packet.duration();
            last_audio_end = last_audio_end.max(micros(end as f64 * f64::from(stream.time_base())));
        }
    }
    assert!(last_audio_end >= 4_040_000, "the source audio tail must survive the full-EOF cut");
    #[cfg(unix)]
    {
        use std::io::{Seek, SeekFrom};
        use std::os::fd::AsRawFd;
        let mut descriptor = std::fs::File::open(&input).unwrap();
        descriptor.seek(SeekFrom::Start(123)).unwrap();
        let fd_path = format!("/proc/self/fd/{}", descriptor.as_raw_fd());
        let fd_output = directory.join("descriptor-eof.mp4");
        let fd_report = cut(&fd_path, fd_output.to_str().unwrap(), 1_950_000,
            requested_end, false, Arc::new(Observer::default())).unwrap();
        assert_eq!(fd_report, report);
        assert_eq!(descriptor.stream_position().unwrap(), 123,
            "the raw metadata probe must preserve the granted descriptor's offset");
    }
    for beyond in [6_024_000, 6_030_000, 6_300_000] {
        let rejected = directory.join(format!("beyond-{beyond}.mp4"));
        assert!(cut(input.to_str().unwrap(), rejected.to_str().unwrap(), 1_950_000,
            beyond, false, Arc::new(Observer::default())).is_err());
        assert!(!rejected.exists());
    }
    std::fs::remove_dir_all(directory).unwrap();
}
