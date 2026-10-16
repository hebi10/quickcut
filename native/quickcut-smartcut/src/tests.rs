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
        if value > 15 && value < 99 && self.output.exists() {
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
            if (1..=5).contains(&(data[at] & 31)) { picture.extend_from_slice(&data[at..at+len]); }
            at += len;
        }
        assert_eq!(at, data.len());
        if !picture.is_empty() { packets.push(picture); }
    }
    packets
}
