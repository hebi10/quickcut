//! Synthetic long-timestamp coverage; this does not reproduce any particular
//! user's three-hour recording or establish the cause of its failure.

use std::{
    collections::hash_map::DefaultHasher,
    fs::File,
    hash::Hasher,
    io::{BufReader, Read},
    path::{Path, PathBuf},
    process::{Command, Output},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use ffmpeg_next as ff;
use quickcut_smartcut::{cut, Callbacks};

const START_US: i64 = 10_791_000_000;
const END_US: i64 = 10_795_500_000;
const DURATION_US: i64 = END_US - START_US;

struct Observer;
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, _: i32) {}
}

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!(
            "quickcut-three-hours-{}-{nonce}", std::process::id()
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); }
}

fn run(command: &mut Command) -> Output {
    let output = command.output().expect("host integration tests require ffmpeg");
    assert!(output.status.success(), "{command:?} failed: {}",
        String::from_utf8_lossy(&output.stderr));
    output
}

fn fingerprint(path: &Path) -> (u64, u64) {
    let file = File::open(path).unwrap();
    let length = file.metadata().unwrap().len();
    let mut reader = BufReader::new(file);
    let mut hash = DefaultHasher::new();
    let mut bytes = [0u8; 65_536];
    loop {
        let count = reader.read(&mut bytes).unwrap();
        if count == 0 { break; }
        hash.write(&bytes[..count]);
    }
    (length, hash.finish())
}

fn pictures_in(path: &Path, start_us: i64, end_us: i64) -> Vec<(i64, Vec<u8>)> {
    let mut input = ff::format::input(path).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let time_base: f64 = input.stream(video).unwrap().time_base().into();
    let mut pictures = Vec::new();
    // Demuxing the small source is cheap. Only the selected access units are
    // retained, and none of the three-hour source is decoded here.
    for (stream, packet) in input.packets() {
        if stream.index() != video { continue; }
        let pts_us = (packet.pts().unwrap() as f64 * time_base * 1_000_000.0).round() as i64;
        if !(start_us..end_us).contains(&pts_us) { continue; }
        let data = packet.data().unwrap();
        let mut at = 0;
        let mut picture = Vec::new();
        while at < data.len() {
            assert!(at + 4 <= data.len(), "truncated AVC NAL length");
            let length = u32::from_be_bytes(data[at..at + 4].try_into().unwrap()) as usize;
            at += 4;
            assert!(length > 0 && at + length <= data.len(), "truncated AVC NAL");
            if (1..=5).contains(&(data[at] & 31)) {
                picture.extend_from_slice(&data[at..at + length]);
            }
            at += length;
        }
        assert!(!picture.is_empty());
        pictures.push((pts_us, picture));
    }
    pictures
}

#[test]
fn three_hour_source_keeps_long_timestamps_and_smart_cuts_near_the_end() {
    let directory = Directory::new();
    let seed = directory.0.join("eight-seconds.mp4");
    let source = directory.0.join("three-hours.mp4");
    let output = directory.0.join("near-end.mp4");
    run(Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=64x48:rate=10:duration=8", "-an", "-c:v", "libx264", "-preset",
        "veryfast", "-threads", "1", "-crf", "35", "-pix_fmt", "yuv420p", "-g", "20",
        "-keyint_min", "20", "-sc_threshold", "0", "-bf", "2", "-x264-params",
        "open-gop=0"]).arg(&seed));
    // 1,350 eight-second repetitions are exactly three hours. Stream copying
    // keeps fixture creation small and avoids encoding three hours of video.
    // A video-only source avoids introducing unrelated audio loop seams.
    run(Command::new("ffmpeg").args(["-v", "error", "-y", "-stream_loop", "1349", "-i"])
        .arg(&seed).args(["-map", "0:v:0", "-c", "copy", "-t", "10800", "-movflags",
            "+faststart"]).arg(&source));
    let original = fingerprint(&source);
    assert!(original.0 < 16 * 1024 * 1024, "the long-source fixture must stay small");
    let source_media = ff::format::input(&source).unwrap();
    assert_eq!(source_media.duration(), 10_800_000_000);
    assert_eq!(source_media.nb_streams(), 1);
    drop(source_media);
    assert!(START_US > i64::from(i32::MAX), "exercise timestamps beyond signed 32-bit microseconds");

    let result = cut(source.to_str().unwrap(), output.to_str().unwrap(), START_US, END_US,
        false, Arc::new(Observer));
    assert_eq!(fingerprint(&source), original, "the source must remain unchanged");
    let report = result.unwrap_or_else(|error| panic!("near-end long-source cut failed: {error:#}"));
    assert_eq!((report.copied_video_packets, report.encoded_video_packets), (20, 25),
        "retain the complete middle GOP and encode only the two selected boundaries");

    let result_media = ff::format::input(&output).unwrap();
    assert_eq!(result_media.nb_streams(), 1);
    let video = result_media.streams().best(ff::media::Type::Video).unwrap();
    let video_duration_us = (video.duration() as f64 * f64::from(video.time_base()) * 1_000_000.0).round() as i64;
    assert!((video_duration_us - DURATION_US).abs() <= 1);
    assert!((result_media.duration() - DURATION_US).abs() <= 1_000);
    drop(result_media);
    let decoded = run(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"])
        .arg(&output).args(["-map", "0:v:0", "-fps_mode", "passthrough", "-pix_fmt",
            "yuv420p", "-f", "rawvideo", "-"]));
    assert_eq!(decoded.stdout.len(), 45 * 64 * 48 * 3 / 2,
        "all 45 retained pictures must decode without truncation or duplication");

    let retained_source = pictures_in(&source, START_US, END_US);
    let output_pictures = pictures_in(&output, 0, DURATION_US);
    assert_eq!(retained_source.len(), 45);
    assert_eq!(output_pictures.len(), 45);
    let actual_copies = output_pictures.iter().filter(|(output_pts, picture)|
        retained_source.iter().any(|(source_pts, source_picture)|
            (*source_pts - START_US - *output_pts).abs() <= 1 && source_picture == picture)
    ).count();
    assert_eq!(actual_copies as u64, report.copied_video_packets,
        "copied VCL payloads must match the source at its original three-hour timestamps");
}
