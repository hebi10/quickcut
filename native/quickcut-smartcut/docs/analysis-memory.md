# Analysis metadata admission

QuickCut admits **64 MiB of requested frame-count-dependent Rust metadata allocations per initial analysis or cached binding**. The completed cache has its existing independent 16 MiB retention limit and four-entry LRU limit. The 64 MiB value is a conservative engineering limit, not a measurement of Android's available heap or a promise about total process RSS.

The shared ledger charges allocations before `try_reserve_exact`. A vector grows geometrically; the ledger charges its entire new requested capacity and never refunds old capacity. This overcounts storage after a reallocation or a dropped scan vector, while covering simultaneous old/new allocation during growth. Arithmetic overflow, budget exhaustion, and allocator reservation failure all produce the typed `AnalysisBudgetExceeded` result. No giant allocation is attempted to test failure.

Covered allocations:

- Core `PacketView` and presentation-time vectors, including growth overlap.
- Adapter picture and IDR timestamp vectors, kept as vectors to avoid an infallible boxed-slice shrink/reallocation.
- The reference judge's growing completed-entry vector and its bounded open-entry vector.
- Access points and their nested leading-picture indices, including the pre-roll marker added during Source assembly. The shared budget follows the index source into assembly. GOP-gap averaging there uses an allocation-free iterator rather than a second per-entry vector.
- Binding an immutable cached or fresh Source: the full second access-point/leading-index copy, chapters, audio metadata and strings, and current capability paths. Binding uses fallible copies rather than `Source::clone`.
- Sorting the budgeted frame/index vectors uses allocation-free unstable sorting. The adapter already rejects duplicate picture timestamps.

At 30 fps, two hours has 216,000 pictures. On 64-bit targets the roughly 40-byte packet view plus two 8-byte timestamp arrays costs about 28 MiB after cumulative geometric-capacity charges, leaving headroom for entry/leading data and the bound index copy. All-IDR and very high-rate sources consume more metadata per unit of running time and may decline earlier. This is deliberate: it does not change frame bounds, codec eligibility, HDR, dimensions, or quality to fit a budget. The existing three-hour/108,000-frame native integration fixtures remain useful default-budget success cases.

This is a bound on the named Rust indexing metadata, not FFmpeg's demux/sample tables, compressed packets, codec buffers, per-packet parsing temporaries, general container probing, allocator overhead, or aggregate memory across concurrent callers. Small fixed bookkeeping allocations (including Arc and the picture-clock heap) also remain outside the ledger. Evicted cache entries still held by active callers outlive retention, as before. Those distinctions must remain visible in any release claims; a general process-memory sandbox would require a separate design.

Reason 22 (`ANALYSIS_BUDGET`) travels through the existing JNI unavailable result, diagnostic enum and notice. FAST_ONLY does not enter Media3. The existing explicitly allowed exact-export mode can use Media3 with the original timeline and format contract. No partial source analysis enters the cache, and analysis completes before an output writer is created.

Tests inject 4 KiB into a real six-second MP4 scan, exercise cached and nested-index binding, and drive the actual core growth helper toward one million synthetic frames with a 64 KiB cap; the loop stops after a small bounded prefix. Core tests also cover shared-vector accounting and arithmetic overflow. The host build script runs the new core filters as well as all existing adapter and descriptor tests. Native compilation/execution requires the pinned toolchain and dependencies; no local native execution was available when this change was authored.

Source-assembly regressions additionally cover zero/one-point defaults, repeated timestamps, clamp behavior and a bounded 65,536-point dense index. Pre-roll tests verify typed refusal before the nested vector grows, sufficient-budget semantics, equivalence to unbudgeted normalization, and the already-at-zero case requiring no marker allocation. These tests run through the existing `analysis_memory_tests` host filter.
