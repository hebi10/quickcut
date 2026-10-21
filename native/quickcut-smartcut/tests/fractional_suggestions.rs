use quickcut_smartcut::{cut_ranges_copy_video, suggest_copy_ranges, Callbacks};
use std::{process::Command, sync::Arc};

struct Observer;
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, _: i32) {}
}

#[test]
fn fractional_millisecond_idr_boundaries_are_preserved_through_copy_export() {
    let directory = std::env::temp_dir().join(format!("quickcut-fractional-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let input = directory.join("source.mp4");
    let output = directory.join("result.mp4");
    assert!(Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=160x96:rate=30000/1001:duration=8", "-an", "-c:v", "libx264",
        "-preset", "veryfast", "-threads", "1", "-g", "50", "-keyint_min", "50",
        "-sc_threshold", "0", "-bf", "2", "-x264-params", "open-gop=0"]).arg(&input)
        .status().unwrap().success());
    let ranges = suggest_copy_ranges(input.to_str().unwrap(), &[(1_000_000, 6_900_000)],
        8_008_000, true, Arc::new(Observer)).unwrap();
    assert_eq!(ranges, vec![(1_668_333, 6_673_333)]);
    assert_ne!(ranges[0].0 % 1_000, 0);
    let report = cut_ranges_copy_video(input.to_str().unwrap(), output.to_str().unwrap(), &ranges,
        true, Arc::new(Observer)).unwrap();
    assert_eq!((report.copied_video_packets, report.encoded_video_packets), (150, 0));
    assert!(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"]).arg(&output)
        .args(["-f", "null", "-"]).status().unwrap().success());
    std::fs::remove_dir_all(directory).unwrap();
}
