//! Only completed, immutable source analysis is retained. No descriptor or
//! provider path outlives the call which granted it, and a cache hit still has
//! to pass the caller's exact range, audio and output validation rules.
use crate::{check_source, display::Display, packet_inspection::InspectingScan, Callbacks, PacketFacts};
use crate::decline::{explain, Reason};
use anyhow::{ensure, Result};
use smartcut_core::Source;
use std::{collections::VecDeque, mem::size_of, sync::{Arc, Mutex, OnceLock}};

const MAX_ENTRIES: usize = 4;
const MAX_RETAINED_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SourceIdentity {
    device: u64,
    inode: u64,
    size: u64,
    modified_seconds: i64,
    modified_nanos: i64,
    changed_seconds: i64,
    changed_nanos: i64,
}

impl SourceIdentity {
    #[cfg(unix)]
    pub(crate) fn read(input: &str) -> Result<Self> {
        use std::os::unix::fs::MetadataExt;
        let metadata = smartcut_core::input::local_metadata(input)?;
        ensure!(metadata.is_file() && metadata.len() > 0, "source is not a regular nonempty file");
        Ok(Self { device: metadata.dev(), inode: metadata.ino(), size: metadata.len(),
            modified_seconds: metadata.mtime(), modified_nanos: metadata.mtime_nsec(),
            changed_seconds: metadata.ctime(), changed_nanos: metadata.ctime_nsec() })
    }

    #[cfg(not(unix))]
    pub(crate) fn read(_input: &str) -> Result<Self> {
        anyhow::bail!("stable local source identity is unavailable on this platform")
    }
}

pub(crate) struct AnalyzedSource {
    // Paths are deliberately empty. Bind a clone to the live input capability
    // every time, including the first operation, before calling core code.
    source: Source,
    pub(crate) packets: PacketFacts,
    pub(crate) display: Display,
}

impl AnalyzedSource {
    fn new(mut source: Source, packets: PacketFacts, display: Display) -> Self {
        source.path = String::new();
        source.input.spec = String::new();
        source.input.url = String::new();
        source.input.file = std::path::PathBuf::new();
        Self { source, packets, display }
    }

    fn bind(&self, input: &str) -> Source {
        let mut source = self.source.clone();
        source.path = input.to_owned();
        source.input.spec = input.to_owned();
        source.input.url = input.to_owned();
        source.input.file = input.into();
        source
    }

    /// Count allocated capacities, including nested access-point/audio data.
    /// Unknown/compound metadata is never cached; source validation already
    /// excludes it, but keep the budget correct if supported formats expand.
    fn retained_bytes(&self) -> Option<usize> {
        let source = &self.source;
        if !source.input.parts.is_empty() || !source.joins.is_empty()
            || !source.captions.is_empty() || !source.graphics.is_empty()
            || !source.subpictures.is_empty() || !source.dropped.is_empty()
            || source.video.vc1.is_some() || source.video.field_shape.is_some() {
            return None;
        }
        fn audio_bytes(audio: &smartcut_core::AudioInfo) -> usize {
            audio.codec.capacity() + audio.language.as_ref().map_or(0, String::capacity)
                + audio.said.as_ref().map_or(0, |said|
                    said.language.as_ref().map_or(0, String::capacity)
                    + said.name.as_ref().map_or(0, String::capacity))
        }
        let bytes = size_of::<Self>() + size_of::<CacheEntry>() + 2 * size_of::<usize>()
            + source.video.codec.capacity()
            + source.points.capacity() * size_of::<smartcut_core::AccessPoint>()
            + source.points.iter().map(|p| p.lead_indices.capacity() * size_of::<usize>()).sum::<usize>()
            + source.chapters.capacity() * size_of::<f64>()
            + source.audios.capacity() * size_of::<smartcut_core::AudioInfo>()
            + source.audios.iter().map(audio_bytes).sum::<usize>()
            + source.audio.as_ref().map_or(0, audio_bytes)
            + source.input.parts.capacity() * size_of::<std::path::PathBuf>()
            + source.joins.capacity() * size_of::<smartcut_core::restamp::Seam>()
            + source.captions.capacity() * size_of::<smartcut_core::CaptionInfo>()
            + source.graphics.capacity() * size_of::<smartcut_core::GraphicsInfo>()
            + source.subpictures.capacity() * size_of::<smartcut_core::SubpictureInfo>()
            + source.dropped.capacity() * size_of::<smartcut_core::DroppedStream>()
            + self.packets.retained_bytes()
            + self.display.0.as_ref().map_or(0, Vec::capacity);
        Some(bytes)
    }
}

struct CacheEntry { identity: SourceIdentity, analysis: Arc<AnalyzedSource>, bytes: usize }
struct AnalysisCache {
    entries: VecDeque<CacheEntry>,
    bytes: usize,
    max_entries: usize,
    max_bytes: usize,
}
impl AnalysisCache {
    fn new(max_entries: usize, max_bytes: usize) -> Self {
        Self { entries: VecDeque::new(), bytes: 0, max_entries, max_bytes }
    }
    fn get(&mut self, identity: &SourceIdentity) -> Option<Arc<AnalyzedSource>> {
        let at = self.entries.iter().position(|entry| &entry.identity == identity)?;
        let entry = self.entries.remove(at)?;
        let result = Arc::clone(&entry.analysis);
        self.entries.push_back(entry);
        Some(result)
    }
    fn remove(&mut self, identity: &SourceIdentity) {
        if let Some(at) = self.entries.iter().position(|entry| &entry.identity == identity) {
            self.bytes -= self.entries.remove(at).unwrap().bytes;
        }
    }
    fn insert(&mut self, identity: SourceIdentity, analysis: Arc<AnalyzedSource>) {
        let Some(bytes) = analysis.retained_bytes() else { return; };
        if self.max_entries == 0 || bytes > self.max_bytes { return; }
        self.remove(&identity);
        while self.entries.len() >= self.max_entries || self.bytes > self.max_bytes - bytes {
            if let Some(entry) = self.entries.pop_front() { self.bytes -= entry.bytes; }
        }
        self.bytes += bytes;
        self.entries.push_back(CacheEntry { identity, analysis, bytes });
    }
}

fn cache() -> &'static Mutex<AnalysisCache> {
    static CACHE: OnceLock<Mutex<AnalysisCache>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(AnalysisCache::new(MAX_ENTRIES, MAX_RETAINED_BYTES)))
}

pub(crate) struct AnalysisLease {
    identity: SourceIdentity,
    pub(crate) analysis: Arc<AnalyzedSource>,
    pub(crate) source: Source,
}
impl AnalysisLease {
    pub(crate) fn verify(&self, input: &str, callbacks: &dyn Callbacks) -> Result<()> {
        ensure!(!callbacks.is_cancelled() && !smartcut_core::input::reads_stopped(), "cancelled");
        ensure_fast!(!smartcut_core::input::reads_failed(), SourceUnreadable, "input read failed");
        let current = explain(SourceIdentity::read(input), Reason::SourceUnreadable)?;
        ensure_fast!(current == self.identity, SourceUnreadable,
            "source changed during analysis or export");
        Ok(())
    }
}

pub(crate) fn load(input: &str, callbacks: &dyn Callbacks,
    progress: &(dyn Fn(i32) + Sync)) -> Result<AnalysisLease> {
    load_with_cache(input, callbacks, progress, cache())
}

fn load_with_cache(input: &str, callbacks: &dyn Callbacks,
    progress: &(dyn Fn(i32) + Sync), store: &Mutex<AnalysisCache>) -> Result<AnalysisLease> {
    ensure!(!callbacks.is_cancelled(), "cancelled");
    let identity = explain(SourceIdentity::read(input), Reason::SourceUnreadable)?;
    let cached = store.lock().unwrap_or_else(|p| p.into_inner()).get(&identity);
    if let Some(analysis) = cached {
        let source = analysis.bind(input);
        let lease = AnalysisLease { identity, analysis, source };
        progress(100);
        lease.verify(input, callbacks)?;
        return Ok(lease);
    }
    progress(0);
    ensure!(!callbacks.is_cancelled(), "cancelled");
    let stopped = || callbacks.is_cancelled();
    let scanning = |value: f64| progress((value * 95.0).clamp(0.0, 95.0) as i32);
    let scan = InspectingScan { callbacks, facts: std::cell::RefCell::new(None) };
    let source = explain(smartcut_core::scan_stoppable(input, &scan,
        Some(&scanning), Some(&stopped)), Reason::SourceUnreadable)?;
    // Range/audio rules remain per-operation. The completed index and payload
    // facts come from one full traversal, and both must have completed.
    check_source(&source, 0, true)?;
    let packets = scan.facts.into_inner().ok_or_else(|| anyhow::anyhow!("missing packet inspection"))?;
    let display = explain(Display::read(input), Reason::Rotation)?;
    let analysis = Arc::new(AnalyzedSource::new(source, packets, display));
    let source = analysis.bind(input);
    let lease = AnalysisLease { identity, analysis, source };
    // A callback can cancel even at the end. Insert only after it returns and
    // identity/read completion have all been rechecked.
    progress(100);
    lease.verify(input, callbacks)?;
    store.lock().unwrap_or_else(|p| p.into_inner()).insert(identity, Arc::clone(&lease.analysis));
    Ok(lease)
}

#[cfg(test)]
#[path = "analysis_cache_tests.rs"]
mod tests;
