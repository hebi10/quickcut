use std::{process::Command, sync::Arc};
use ffmpeg_next as ff;
use quickcut_smartcut::{cut, Callbacks};

struct Observer;
impl Callbacks for Observer { fn is_cancelled(&self) -> bool { false } fn progress(&self, _: i32) {} }

#[test]
fn understated_level_is_declined_without_publishing_or_changing_source() {
    let directory = std::env::temp_dir().join(format!("quickcut-low-level-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let source = directory.join("source.mp4"); let output = directory.join("output.mp4");
    let _ = std::fs::remove_file(&output);
    assert!(Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
        "color=c=red:size=320x240:rate=30:duration=6", "-c:v", "libx264", "-preset", "ultrafast",
        "-pix_fmt", "yuv420p", "-bf", "0", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0"])
        .arg(&source).status().unwrap().success());
    let mut bytes = std::fs::read(&source).unwrap();
    let config = bytes.windows(4).position(|word| word == b"avcC").unwrap() + 4;
    bytes[config + 3] = 10;
    assert_eq!(bytes[config + 5] & 31, 1);
    let sps = config + 8;
    bytes[sps + 3] = 10; // 300 macroblocks cannot fit level 1.0's 99.
    std::fs::write(&source, &bytes).unwrap();
    let failure = cut(source.to_str().unwrap(), output.to_str().unwrap(), 1_950_000, 5_500_000,
        false, Arc::new(Observer)).unwrap_err();
    assert!(format!("{failure:#}").contains("SPS level"), "{failure:#}");
    assert!(!output.exists());
    assert!(!directory.join("output.mp4.smartcut.mp4").exists());
    assert_eq!(std::fs::read(&source).unwrap(), bytes);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn constrained_baseline_boundaries_keep_the_profile_and_first_red_picture() {
    let directory = std::env::temp_dir().join(format!("quickcut-baseline-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let source = directory.join("source.mp4");
    let output = directory.join("output.mp4");
    let _ = std::fs::remove_file(&output);
    assert!(Command::new("ffmpeg").args(["-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=red:size=320x240:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
        "-vf", "drawbox=x=0:y=0:w=iw:h=ih:color=blue:t=fill:enable='gte(t,2)'",
        "-t", "6", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-bf", "0", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-c:a", "aac",
        "-b:a", "64k", "-shortest"]).arg(&source).status().unwrap().success());
    let report = cut(source.to_str().unwrap(), output.to_str().unwrap(), 1_950_000, 5_500_000,
        false, Arc::new(Observer)).unwrap();
    assert_eq!((report.copied_video_packets, report.encoded_video_packets), (60, 46));
    let mut input = ff::format::input(&output).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let parameters = input.stream(video).unwrap().parameters();
    let config = unsafe {
        let p = parameters.as_ptr();
        std::slice::from_raw_parts((*p).extradata, (*p).extradata_size as usize)
    };
    let sets = smartcut_core::bitstream::parameter_sets("h264", config);
    let profiles: Vec<u8> = sets.iter().filter(|s| s[0] & 31 == 7).map(|s| s[1]).collect();
    assert_eq!(profiles, vec![66, 66, 66], "original and both boundaries must remain Baseline, without a High/CABAC transition");
    // Android libavc rejects differently sized reference buffers inside one
    // static AVC configuration, even when every SPS has the same profile.
    let headers = Command::new("ffmpeg").args(["-hide_banner", "-i"]).arg(&output)
        .args(["-map", "0:v", "-c", "copy", "-bsf:v", "trace_headers", "-frames:v", "1", "-f", "null", "-"])
        .output().unwrap();
    assert!(headers.status.success());
    let header_log = String::from_utf8_lossy(&headers.stderr);
    let references: Vec<u32> = header_log.lines().filter(|line| line.contains("max_num_ref_frames"))
        .map(|line| line.rsplit(" = ").next().unwrap().parse().unwrap()).collect();
    assert_eq!(references, vec![1, 1, 1], "all static SPS must preserve the source reference-buffer size");
    let (_, first) = input.packets().find(|(s, _)| s.index() == video).unwrap();
    assert_eq!(first.pts(), Some(0));
    assert_eq!(first.dts(), Some(0));
    assert!(first.is_key());
    let decoded = Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"])
        .arg(&output).args(["-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"])
        .output().unwrap();
    assert!(decoded.status.success());
    let center = (120 * 320 + 160) * 3;
    assert!(decoded.stdout[center] > 200 && decoded.stdout[center + 2] < 30,
        "the requested pre-keyframe red picture must remain first");
    std::fs::remove_dir_all(directory).unwrap();
}
