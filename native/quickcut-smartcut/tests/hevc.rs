use std::{process::Command, sync::Arc};
use ffmpeg_next as ff;
use quickcut_smartcut::{cut, cut_ranges, Callbacks};

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

fn decoded_hashes(path: &std::path::Path) -> Vec<String> {
    let result = Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"])
        .arg(path).args(["-map", "0:v:0", "-fps_mode", "passthrough", "-f", "framemd5", "-"]).output().unwrap();
    assert!(result.status.success(), "{}", String::from_utf8_lossy(&result.stderr));
    String::from_utf8(result.stdout).unwrap().lines().filter(|line| !line.starts_with('#'))
        .map(|line| line.rsplit(',').next().unwrap().trim().to_owned()).collect()
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
    // Setting this flag on the source's first IDR is legal: it has no prior
    // pictures there. Reusing that same picture at a join must not discard the
    // preceding retained GOP's delayed B pictures.
    let flagged = directory.join("no-output-prior.mp4");
    let mut bytes = original.clone();
    let mut input = ff::format::input(&source).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let (_, packet) = input.packets().find(|(stream, _)| stream.index() == video).unwrap();
    let mut at = packet.position() as usize;
    let end = at + packet.size();
    let mut patched = false;
    while at < end {
        let length = u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
        at += 4;
        if matches!((bytes[at] >> 1) & 63, 19 | 20) {
            assert!(length >= 3 && bytes[at + 2] & 0x80 != 0);
            bytes[at + 2] |= 0x40;
            patched = true;
        }
        at += length;
    }
    assert!(patched);
    std::fs::write(&flagged, bytes).unwrap();
    let source_count = Command::new("ffprobe").args(["-v", "error", "-select_streams", "v:0", "-count_frames",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0"]).arg(&flagged).output().unwrap();
    assert!(source_count.status.success());
    assert_eq!(String::from_utf8(source_count.stdout).unwrap().trim(), "240", "the source is valid");
    let opening_output = directory.join("safe-opening.mp4");
    let opening = cut(flagged.to_str().unwrap(), opening_output.to_str().unwrap(),
        0, 2_000_000, false, Arc::new(Observer)).unwrap();
    assert_eq!((opening.copied_video_packets, opening.encoded_video_packets), (60, 0),
        "the same IDR is safe when no earlier output pictures exist");
    let opening_count = Command::new("ffprobe").args(["-v", "error", "-select_streams", "v:0", "-count_frames",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0"]).arg(&opening_output).output().unwrap();
    assert!(opening_count.status.success());
    assert_eq!(String::from_utf8(opening_count.stdout).unwrap().trim(), "60");
    let joined_output = directory.join("repaired-join.mp4");
    let result = cut_ranges(flagged.to_str().unwrap(), joined_output.to_str().unwrap(),
        &[(6_000_000, 8_000_000), (0, 3_000_000)], false, Arc::new(Observer)).unwrap();
    assert!(result.copied_video_packets >= 120);
    assert_eq!(result.copied_video_packets + result.encoded_video_packets, 150);
    let joined = decoded_hashes(&joined_output);
    assert_eq!(joined.len(), 150, "a splice must retain all delayed B pictures");
    let original_pictures = decoded_hashes(&flagged);
    let expected: Vec<_> = original_pictures[180..240].iter()
        .chain(&original_pictures[..60]).cloned().collect();
    assert_eq!(&joined[..120], expected.as_slice(), "copied pictures must decode identically across the repaired join");
    let copy_output = directory.join("repaired-copy-only.mp4");
    let copied = cut_ranges(flagged.to_str().unwrap(), copy_output.to_str().unwrap(),
        &[(6_000_000, 8_000_000), (0, 2_000_000)], false, Arc::new(Observer)).unwrap();
    assert_eq!((copied.copied_video_packets, copied.encoded_video_packets), (120, 0));
    assert_eq!(decoded_hashes(&copy_output), expected);
    let flagged_original = std::fs::read(&flagged).unwrap();
    // At 30 fps, [3.01, 7.5) starts with source frame 91 and contains 134
    // pictures. Its 29-frame encoded head places the copied GOP at frame 29
    // (0.966667 s), although subtracting the requested bounds gives 0.99 s.
    // Reversing and repeating the opening GOP also exercises actual flag
    // repairs after those off-frame spans. Each [0, 2.02) retains 61 pictures.
    for (name, ranges, copied, encoded, copied_spans) in [
        ("off-frame-single", vec![(3_010_000, 7_500_000)], 60, 74,
            vec![(29, 120)]),
        ("off-frame-reversed-repeated", vec![(3_010_000, 7_500_000), (0, 2_020_000), (0, 2_020_000)],
            180, 76, vec![(29, 120), (134, 0), (195, 0)]),
    ] {
        let off_frame_output = directory.join(format!("{name}.mp4"));
        let _ = std::fs::remove_file(&off_frame_output);
        let report = if ranges.len() == 1 {
            cut(flagged.to_str().unwrap(), off_frame_output.to_str().unwrap(),
                ranges[0].0, ranges[0].1, false, Arc::new(Observer))
        } else {
            cut_ranges(flagged.to_str().unwrap(), off_frame_output.to_str().unwrap(),
                &ranges, false, Arc::new(Observer))
        }.unwrap_or_else(|error| panic!("{name}: {error:#}"));
        assert_eq!((report.copied_video_packets, report.encoded_video_packets), (copied, encoded), "{name}");
        let expected_count = (copied + encoded) as usize;
        let decoded = decoded_hashes(&off_frame_output);
        assert_eq!(decoded.len(), expected_count, "{name}: every retained picture must decode");
        let mut pts: Vec<_> = pictures(&off_frame_output).into_iter().map(|(pts, _)| pts).collect();
        pts.sort_unstable();
        assert_eq!(pts.len(), expected_count, "{name}: packet count must match decoded count");
        for (frame, pts) in pts.into_iter().enumerate() {
            let expected_pts = (frame as f64 * 1_000_000.0 / 30.0).round() as i64;
            assert!((pts - expected_pts).abs() <= 2,
                "{name}: frame {frame} has PTS {pts}, expected {expected_pts}");
        }
        for (output_at, source_at) in copied_spans {
            assert_eq!(&decoded[output_at..output_at + 60], &original_pictures[source_at..source_at + 60],
                "{name}: copied GOP at output frame {output_at} must match its source pictures");
        }
    }
    assert_eq!(std::fs::read(&flagged).unwrap(), flagged_original);
    assert_eq!(std::fs::read(&source).unwrap(), original);
    std::fs::remove_dir_all(directory).unwrap();
}
