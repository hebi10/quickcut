use super::*;
use std::io::{Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Default)]
struct Observer { cancelled: AtomicBool, progress: Mutex<Vec<i32>> }
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { self.cancelled.load(Ordering::Relaxed) }
    fn progress(&self, value: i32) { self.progress.lock().unwrap().push(value); }
}

struct Fixture { directory: std::path::PathBuf, input: String }
impl Fixture {
    fn new(name: &str) -> Self {
        smartcut_core::init().unwrap();
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

#[cfg(unix)]
#[test]
fn same_size_mutation_with_restored_mtime_changes_identity() {
    let fixture = Fixture::new("mutation");
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let observer = Observer::default();
    let original = load_with_cache(&fixture.input, &observer, &|_| {}, &store).unwrap();
    let before = SourceIdentity::read(&fixture.input).unwrap();
    let metadata = std::fs::metadata(&fixture.input).unwrap();
    // Restoring mtime must not defeat the ctime-nanosecond part of the key.
    std::thread::sleep(std::time::Duration::from_millis(2));
    let mut file = std::fs::OpenOptions::new().write(true).open(&fixture.input).unwrap();
    // The ftyp minor-version field can change without invalidating the movie.
    file.seek(SeekFrom::Start(15)).unwrap();
    file.write_all(&[1]).unwrap();
    file.set_times(std::fs::FileTimes::new().set_modified(metadata.modified().unwrap())).unwrap();
    let after = SourceIdentity::read(&fixture.input).unwrap();
    assert_eq!(before.size, after.size);
    assert_eq!((before.modified_seconds, before.modified_nanos), (after.modified_seconds, after.modified_nanos));
    assert_ne!(before, after, "same-sized writes must invalidate completed analysis");
    let changed = load_with_cache(&fixture.input, &observer, &|_| {}, &store).unwrap();
    assert!(!Arc::ptr_eq(&original.analysis, &changed.analysis));
}

#[cfg(unix)]
#[test]
fn fd_reuse_cannot_retrieve_another_files_analysis_and_cached_paths_are_empty() {
    use std::os::fd::AsRawFd;
    unsafe extern "C" { fn dup2(oldfd: i32, newfd: i32) -> i32; }
    let fixture = Fixture::new("descriptors");
    let file = std::fs::File::open(&fixture.input).unwrap();
    let fd_path = format!("/proc/self/fd/{}", file.as_raw_fd());
    let observer = Observer::default();
    let progress = |value| observer.progress(value);
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let first = load_with_cache(&fd_path, &observer, &progress, &store).unwrap();
    assert!(first.analysis.source.path.is_empty());
    assert!(first.analysis.source.input.spec.is_empty());
    assert!(first.analysis.source.input.url.is_empty());
    assert!(first.analysis.source.input.file.as_os_str().is_empty());
    let separately_opened = std::fs::File::open(&fixture.input).unwrap();
    let second_path = format!("/proc/self/fd/{}", separately_opened.as_raw_fd());
    let second = load_with_cache(&second_path, &observer, &progress, &store).unwrap();
    assert!(Arc::ptr_eq(&first.analysis, &second.analysis), "new descriptors for one inode must reuse analysis");
    assert_eq!(second.source.path, second_path, "the active Source must use the current handle");
    let replacement = fixture.directory.join("replacement.mp4");
    std::fs::copy(&fixture.input, &replacement).unwrap();
    let replacement_file = std::fs::File::open(replacement).unwrap();
    assert_eq!(unsafe { dup2(replacement_file.as_raw_fd(), file.as_raw_fd()) }, file.as_raw_fd());
    let third = load_with_cache(&fd_path, &observer, &progress, &store).unwrap();
    assert!(!Arc::ptr_eq(&first.analysis, &third.analysis), "a recycled fd is not file identity");
    assert!(first.verify(&fd_path, &observer).is_err(), "identity is rechecked at the operation boundary");
}

#[test]
fn cancelled_or_failed_analysis_is_never_inserted() {
    let fixture = Fixture::new("cancel");
    let observer = Observer::default();
    let progress = |value| {
        if value >= 45 { observer.cancelled.store(true, Ordering::Relaxed); }
    };
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    assert!(load_with_cache(&fixture.input, &observer, &progress, &store).is_err());
    let identity = SourceIdentity::read(&fixture.input).unwrap();
    assert!(store.lock().unwrap().get(&identity).is_none());
    observer.cancelled.store(false, Ordering::Relaxed);
    load_with_cache(&fixture.input, &observer, &|_| {}, &store).unwrap();
    assert!(store.lock().unwrap().get(&identity).is_some());
    let broken = fixture.directory.join("broken.mp4");
    std::fs::write(&broken, b"not an MP4 source").unwrap();
    let broken = broken.to_str().unwrap();
    assert!(load_with_cache(broken, &observer, &|_| {}, &store).is_err());
    assert!(store.lock().unwrap().get(&SourceIdentity::read(broken).unwrap()).is_none());
}

#[test]
fn source_change_during_analysis_is_rejected_before_cache_insert() {
    let fixture = Fixture::new("changing");
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let observer = Observer::default();
    let identity = SourceIdentity::read(&fixture.input).unwrap();
    let progress = |value| {
        if value == 100 {
            let mut file = std::fs::OpenOptions::new().write(true).open(&fixture.input).unwrap();
            file.seek(SeekFrom::Start(15)).unwrap();
            file.write_all(&[1]).unwrap();
        }
    };
    assert!(load_with_cache(&fixture.input, &observer, &progress, &store).is_err());
    assert!(store.lock().unwrap().get(&identity).is_none());
    assert!(store.lock().unwrap().entries.is_empty());
}

#[test]
fn cancellation_at_completed_scan_is_checked_before_cache_insert() {
    let fixture = Fixture::new("late-cancel");
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let observer = Observer::default();
    let progress = |value| {
        if value == 100 { observer.cancelled.store(true, Ordering::Relaxed); }
    };
    assert!(load_with_cache(&fixture.input, &observer, &progress, &store).is_err());
    assert!(store.lock().unwrap().entries.is_empty());
}

#[test]
fn cache_enforces_byte_and_entry_limits_and_evicts_least_recently_used() {
    let fixture = Fixture::new("bounds");
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let loaded = load_with_cache(&fixture.input, &Observer::default(), &|_| {}, &store).unwrap();
    let bytes = loaded.analysis.retained_bytes().unwrap();
    let mut local = AnalysisCache::new(2, bytes * 2);
    let key = loaded.identity;
    let other = SourceIdentity { inode: key.inode + 1, ..key };
    let last = SourceIdentity { inode: key.inode + 2, ..key };
    local.insert(key, loaded.analysis.clone());
    local.insert(other, loaded.analysis.clone());
    assert!(local.get(&key).is_some());
    local.insert(last, loaded.analysis.clone());
    assert!(local.get(&other).is_none());
    assert!(local.get(&key).is_some());
    assert_eq!(local.entries.len(), 2);
    assert_eq!(local.bytes, bytes * 2);
    let mut byte_limited = AnalysisCache::new(4, bytes * 2);
    for identity in [key, other, last] { byte_limited.insert(identity, loaded.analysis.clone()); }
    assert_eq!(byte_limited.entries.len(), 2, "the byte limit applies independently of entry count");
    assert!(byte_limited.get(&key).is_none());
    assert_eq!(byte_limited.bytes, bytes * 2);
    let mut too_small = AnalysisCache::new(4, bytes - 1);
    too_small.insert(key, loaded.analysis);
    assert!(too_small.entries.is_empty(), "oversized analysis is useful for this call but not cached");
    assert_eq!(too_small.bytes, 0);
}

#[test]
fn payload_corruption_after_valid_index_entries_is_rejected_and_never_cached() {
    use ffmpeg_next as ff;
    let fixture = Fixture::new("late-payload");
    let mut input = ff::format::input(&fixture.input).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let mut late_nal = None;
    for (stream, packet) in input.packets() {
        if stream.index() != video { continue; }
        let bytes = packet.data().unwrap();
        let mut at = 0;
        while at + 4 < bytes.len() {
            let size = u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
            if matches!(bytes[at + 4] & 31, 1 | 5) {
                late_nal = Some((packet.position() as u64 + at as u64 + 4, bytes[at + 4] | 0x80));
                break;
            }
            at += 4 + size;
        }
    }
    drop(input);
    let (offset, corrupt) = late_nal.unwrap();
    let mut file = std::fs::OpenOptions::new().write(true).open(&fixture.input).unwrap();
    file.seek(SeekFrom::Start(offset)).unwrap();
    file.write_all(&[corrupt]).unwrap();
    drop(file);
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let error = match load_with_cache(&fixture.input, &Observer::default(), &|_| {}, &store) {
        Ok(_) => panic!("the packet index is not a substitute for full payload inspection"),
        Err(error) => error,
    };
    assert_eq!(crate::decline::code(&error), 19, "{error:#}");
    assert!(store.lock().unwrap().entries.is_empty());
}

#[test]
fn source_analysis_reuses_completed_packet_facts_without_another_scan() {
    let fixture = Fixture::new("reuse");
    let observer = Observer::default();
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let first = load_with_cache(&fixture.input, &observer, &|p| observer.progress(p), &store).unwrap();
    assert_eq!(first.analysis.packets.picture_times_us.len(), 180);
    assert_eq!(first.analysis.packets.idr_times_us.len(), 3);
    observer.progress.lock().unwrap().clear();
    let second = load_with_cache(&fixture.input, &observer, &|p| observer.progress(p), &store).unwrap();
    assert!(Arc::ptr_eq(&first.analysis, &second.analysis));
    assert_eq!(*observer.progress.lock().unwrap(), vec![100]);
    let mut incomplete = first.source.clone();
    incomplete.read_whole = false;
    assert!(crate::check_source(&incomplete, 0, true).is_err());
    incomplete.read_whole = true;
    incomplete.leading_known = false;
    assert!(crate::check_source(&incomplete, 0, true).is_err());
}

#[test]
fn cancellation_during_packet_inspection_never_publishes_partial_facts() {
    use std::sync::atomic::AtomicUsize;
    struct CancelAfterChecks(AtomicUsize);
    impl Callbacks for CancelAfterChecks {
        fn is_cancelled(&self) -> bool { self.0.fetch_add(1, Ordering::Relaxed) >= 3 }
        fn progress(&self, _: i32) {}
    }
    let fixture = Fixture::new("mid-inspection-cancel");
    let store = Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES));
    let callbacks = CancelAfterChecks(AtomicUsize::new(0));
    let progress = std::sync::atomic::AtomicBool::new(false);
    let result = load_with_cache(&fixture.input, &callbacks, &|p| {
        if p == 100 { progress.store(true, Ordering::Relaxed); }
    }, &store);
    assert!(result.is_err());
    assert!(!progress.load(Ordering::Relaxed), "cancellation must stop before the final completion callback");
    assert!(store.lock().unwrap().entries.is_empty());
}
