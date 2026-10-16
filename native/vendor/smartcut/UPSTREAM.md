# SmartCut source provenance

Source: https://github.com/DeepRegular/SmartCut

Pinned commit: `510ee91614a29f9c7fd6a446b75e9767f0df3d8a` (0.8.25).

This directory contains the upstream `rust/` workspace and the unmodified upstream GPL-3.0 license. QuickCut builds only the core and its VC-1/MPEG-2 path dependencies, without desktop playback or CLI executables.

Local changes: optional desktop playback feature; opt-in strict decode/mux error handling; successful packet-write statistics. The wrapper in `../../quickcut-smartcut` imposes a narrower, validated H.264 MP4 contract and does not imply Android support by the upstream authors.
