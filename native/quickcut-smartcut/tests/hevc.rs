use std::{process::Command, sync::Arc};
use ffmpeg_next as ff;
use quickcut_smartcut::{cut, Callbacks};

struct Observer;
impl Callbacks for Observer { fn is_cancelled(&self) -> bool { false } fn progress(&self, _: i32) {} }

fn pictures(path: &std::path::Path) -> Vec<(i64, Vec<u8>)> {
    let mut input = ff::format::input(path).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let time_base: f64 = input.stream(video).unwrap().time_base().into();
    let mut pictures = Vec::new();
    for (stream, packet) in input.packets() {
        if stream.index() != video { continue; }
        let data = packet.data().unwrap();
        let mut at = 0;
        let mut vcl = Vec::new();
        while at < data.len() {
            let length = u32::from_be_bytes(data[at..at + 4].try_into().unwrap()) as usize;
            at += 4;
            assert!(length >= 2 && at + length <= data.len());
            if (data[at] >> 1) & 63 <= 31 { vcl.extend_from_slice(&data[at..at + length]); }
            at += length;
        }
        assert!(!vcl.is_empty());
        pictures.push(((packet.pts().unwrap() as f64 * time_base * 1_000_000.0).round() as i64, vcl));
    }
    pictures
}

#[test]
fn main_hevc_b_frames_copy_the_middle_and_decode_every_retained_picture() {
    let directory = std::env::temp_dir().join(format!("quickcut-hevc-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let source = directory.join("source.mp4");
    let output = directory.join("cut.mp4");
    let _ = std::fs::remove_file(&output);
    assert!(Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=320x240:rate=30", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100",
        "-t", "8", "-c:v", "libx265", "-preset", "fast", "-pix_fmt", "yuv420p", "-x265-params",
        "pools=1:frame-threads=1:keyint=60:min-keyint=60:scenecut=0:open-gop=0:bframes=2:log-level=error",
        "-tag:v", "hvc1", "-c:a", "aac", "-shortest"]).arg(&source).status().unwrap().success());
    let original = std::fs::read(&source).unwrap();
    let report = cut(source.to_str().unwrap(), output.to_str().unwrap(), 3_000_000, 7_500_000,
        false, Arc::new(Observer)).unwrap();
    assert_eq!((report.copied_video_packets, report.encoded_video_packets), (60, 75));
    assert!(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"]).arg(&output)
        .args(["-f", "null", "-"]).status().unwrap().success());
    let count = Command::new("ffprobe").args(["-v", "error", "-select_streams", "v:0", "-count_frames",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0"]).arg(&output).output().unwrap();
    assert!(count.status.success());
    assert_eq!(String::from_utf8(count.stdout).unwrap().trim(), "135");
    let middle: Vec<_> = pictures(&source).into_iter().filter(|(pts, _)| (4_000_000..6_000_000).contains(pts)).collect();
    let result = pictures(&output);
    assert_eq!(middle.len(), 60);
    for (pts, vcl) in middle {
        assert!(result.iter().any(|(out_pts, out_vcl)| (*out_pts - (pts - 3_000_000)).abs() <= 1
            && out_vcl == &vcl), "copied HEVC picture bytes and PTS must stay unchanged");
    }
    assert_eq!(std::fs::read(&source).unwrap(), original);
    std::fs::remove_dir_all(directory).unwrap();
}
