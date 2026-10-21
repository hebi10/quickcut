use quickcut_smartcut::{cut_ranges_copy_video, suggest_copy_ranges, Callbacks};
use std::{path::PathBuf, process::Command, sync::Arc};

struct Observer;
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, _: i32) {}
}

#[test]
fn recommendations_only_remove_more_and_export_every_retained_picture_without_video_encoding() {
    let directory = std::env::temp_dir().join(format!("quickcut-suggestions-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let input = directory.join("source.mp4");
    let output = directory.join("result.mp4");
    assert!(Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=160x96:rate=30:duration=8", "-c:v", "libx264", "-preset", "veryfast",
        "-threads", "1", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0", "-bf", "2",
        "-x264-params", "open-gop=0"]).arg(&input).status().unwrap().success());
    let path = input.to_str().unwrap();
    let observer = || Arc::new(Observer) as Arc<dyn Callbacks>;
    assert_eq!(suggest_copy_ranges(path, &[(3_000_000, 7_500_000)], 8_000_000, true, observer()).unwrap(),
        vec![(4_000_000, 6_000_000)]);
    // A container can report a longer header duration than its video track.
    // An interior range still has exactly the same valid copy boundaries.
    assert_eq!(suggest_copy_ranges(path, &[(3_000_000, 7_500_000)], 9_000_000, true, observer()).unwrap(),
        vec![(4_000_000, 6_000_000)]);
    let ranges = suggest_copy_ranges(path, &[(0, 3_100_000), (5_100_000, 8_000_000)],
        8_000_000, true, observer()).unwrap();
    assert_eq!(ranges, vec![(0, 2_000_000), (6_000_000, 8_000_000)]);
    let report = cut_ranges_copy_video(path, output.to_str().unwrap(), &ranges, true, observer()).unwrap();
    assert_eq!(report.encoded_video_packets, 0);
    assert_eq!(report.copied_video_packets, 120);
    let hashes = |path: &PathBuf, filter: Option<&str>| {
        let mut command = Command::new("ffmpeg");
        command.args(["-v", "error", "-i"]).arg(path);
        if let Some(filter) = filter { command.args(["-vf", filter]); }
        let result = command.args(["-an", "-fps_mode", "passthrough", "-f", "framemd5", "-"]).output().unwrap();
        assert!(result.status.success(), "{}", String::from_utf8_lossy(&result.stderr));
        String::from_utf8(result.stdout).unwrap().lines().filter(|line| !line.starts_with('#'))
            .map(|line| line.rsplit(',').next().unwrap().trim().to_owned()).collect::<Vec<_>>()
    };
    assert_eq!(hashes(&input, Some("select='lt(t,2)+gte(t,6)'")), hashes(&output, None));
    assert!(suggest_copy_ranges(path, &[(3_000_000, 3_900_000)], 8_000_000, true, observer()).is_err());
    let rejected = directory.join("must-not-encode.mp4");
    assert!(cut_ranges_copy_video(path, rejected.to_str().unwrap(), &[(3_000_000, 7_500_000)],
        true, observer()).is_err());
    assert!(!rejected.exists(), "fast-only must refuse before writing an encoded output");
    std::fs::remove_dir_all(directory).unwrap();
}
