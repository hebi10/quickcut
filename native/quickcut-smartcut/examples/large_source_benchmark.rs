//! Local-only benchmark driver. Arguments and errors are deliberately not logged.
use quickcut_smartcut::{cut_ranges_copy_video, suggest_copy_ranges, Callbacks};
use std::{sync::{Arc, atomic::{AtomicBool, Ordering}}, time::Instant};

#[derive(Default)]
struct Observer { scanned: AtomicBool }
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, value: i32) {
        if value == 0 { self.scanned.store(true, Ordering::Relaxed); }
    }
}

fn run() -> Result<(), ()> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 6 { return Err(()); }
    let start: i64 = args[3].parse().map_err(|_| ())?;
    let end: i64 = args[4].parse().map_err(|_| ())?;
    let duration: i64 = args[5].parse().map_err(|_| ())?;
    if start < 0 || start >= end || end > duration { return Err(()); }
    let observer = Arc::new(Observer::default());
    let before = Instant::now();
    let ranges = suggest_copy_ranges(&args[1], &[(start, end)], duration, false,
        observer.clone()).map_err(|_| ())?;
    let initial_ms = before.elapsed().as_millis();
    observer.scanned.store(false, Ordering::Relaxed);
    let before = Instant::now();
    let repeated = suggest_copy_ranges(&args[1], &[(start, end)], duration, false,
        observer.clone()).map_err(|_| ())?;
    let reuse_ms = before.elapsed().as_millis();
    let reuse_scanned = observer.scanned.swap(false, Ordering::Relaxed);
    if repeated != ranges || ranges.is_empty() { return Err(()); }
    let before = Instant::now();
    let result = cut_ranges_copy_video(&args[1], &args[2], &ranges, false,
        observer.clone()).map_err(|_| ())?;
    println!("{{\"initial_recommendation_ms\":{initial_ms},\"repeated_recommendation_ms\":{reuse_ms},\"copy_export_ms\":{},\"repeated_recommendation_rescanned\":{reuse_scanned},\"export_rescanned\":{},\"retained_duration_us\":{},\"copied_video_packets\":{},\"encoded_video_packets\":{}}}",
        before.elapsed().as_millis(), observer.scanned.load(Ordering::Relaxed),
        ranges.iter().map(|(a,b)| b-a).sum::<i64>(), result.copied_video_packets,
        result.encoded_video_packets);
    Ok(())
}
fn main() {
    if run().is_err() {
        eprintln!("Local benchmark declined or failed; source details are not logged.");
        std::process::exit(1);
    }
}
