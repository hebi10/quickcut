# SmartCut source provenance

Source: https://github.com/DeepRegular/SmartCut

Pinned commit: `510ee91614a29f9c7fd6a446b75e9767f0df3d8a` (0.8.25).

This directory contains the upstream `rust/` workspace and the unmodified upstream GPL-3.0 license. QuickCut builds only the core and its VC-1/MPEG-2 path dependencies, without desktop playback or CLI executables.

Local changes (2026-10-16): optional desktop playback feature; opt-in strict decode/mux error handling; successful packet-write statistics; explicit per-boundary x264 SPS/PPS IDs; Constrained Baseline profile normalization and source reference-buffer sizing for FFmpeg 7.1/libx264; descriptor-backed custom AVIO for Android scoped-storage inputs. The wrapper in `../../quickcut-smartcut` imposes a narrower, validated H.264 MP4 contract and does not imply Android support by the upstream authors.

Android's platform MP4 extractor recognizes `avc1`, while upstream smart cuts use `avc3` with in-band parameter changes. QuickCut gives each encoded boundary unused SPS/PPS IDs before encoding, then performs a packet-only remux with every parameter set in a fixed `avcC` and no in-band SPS/PPS. Coded picture bytes are not rewritten. This adds a metadata scan and one read/write pass over the intermediate output. At most the intermediate and final staging files coexist; the intermediate is deleted before publication, including on cancellation or failure.

Android libavc rejects multiple SPS entries with different decoder-buffer configurations in one static configuration. QuickCut preserves the source reference count through the encoder and validates profile, level, buffer/reorder sizes, dimensions, crop, progressive structure, and colour signalling across all output SPS entries. An unsupported or mismatched configuration uses the existing exact fallback instead of publishing an incompletely decodable smart cut.

## 2026-10-19 fast-export coverage

QuickCut restarts the input audio decoder for overlapping/backward ranges while
retaining the output AAC encoder and timeline. This prevents source-PTS deduplication
from erasing repeated sound. HEVC boundary encodes use the fast preset, two worker
threads and one frame thread; only the adapter's checked SDR Main/8-bit inputs are
enabled. Codec/rotation/date validation and final MP4 remuxing live in the JNI adapter
and Kotlin exporter rather than changing upstream's general input promises.
