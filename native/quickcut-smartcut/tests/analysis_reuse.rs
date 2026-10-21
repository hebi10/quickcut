use quickcut_smartcut::{cut_ranges_copy_video, suggest_copy_ranges, Callbacks};
use std::sync::{Arc, Mutex};
#[derive(Default)]
struct Observer { progress: Mutex<Vec<i32>> }
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, value: i32) { self.progress.lock().unwrap().push(value); }
}

struct Fixture { directory: std::path::PathBuf, input: String }
impl Fixture {
    fn new(name: &str) -> Self {
        let nonce = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let directory = std::env::temp_dir().join(format!("quickcut-analysis-{name}-{}-{nonce}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let input = directory.join("source.mp4");
        assert!(std::process::Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
            "testsrc2=size=160x96:rate=30:duration=6", "-an", "-c:v", "libx264", "-threads", "1",
            "-preset", "ultrafast", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0",
            "-x264-params", "open-gop=0"]).arg(&input).status().unwrap().success());
        Self { directory, input: input.to_str().unwrap().to_owned() }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.directory); }
}


#[test]
fn recommendation_analysis_is_reused_by_copy_export() {
    let fixture = Fixture::new("reuse");
    let observer = Arc::new(Observer::default());
    let ranges = suggest_copy_ranges(&fixture.input, &[(500_000, 5_500_000)], 6_000_000,
        true, observer.clone()).unwrap();
    assert!(observer.progress.lock().unwrap().contains(&0), "the first recommendation scans the source");
    observer.progress.lock().unwrap().clear();
    let output = fixture.directory.join("copied.mp4");
    let report = cut_ranges_copy_video(&fixture.input, output.to_str().unwrap(), &ranges,
        true, observer.clone()).unwrap();
    assert_eq!(report.encoded_video_packets, 0);
    assert_eq!(report.copied_video_packets, 60);
    assert!(!observer.progress.lock().unwrap().contains(&0), "export must reuse the complete packet validation");
}

