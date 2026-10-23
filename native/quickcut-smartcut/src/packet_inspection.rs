//! Source-wide payload validation runs inside the index traversal. The scan
//! observes all streams before any index-only filtering; no payload pass is
//! replaced by container tables or keyframe flags.
use super::*;
use std::cell::RefCell;

pub(crate) struct InspectingScan<'a> {
    pub callbacks: &'a dyn Callbacks,
    pub facts: RefCell<Option<PacketFacts>>,
}
impl smartcut_core::index::IndexSource for InspectingScan<'_> {
    fn name(&self) -> &'static str { "inspected packet scan" }
    fn build(&self, input: smartcut_core::index::IndexInput) -> Result<smartcut_core::index::Index> {
        let mut inspector = PacketInspector::new(input.video, input.start_time, &input.ictx, self.callbacks)?;
        let index = smartcut_core::index::walk_observed(input.video, input.start_time,
            input.ictx, input.on, |packet| explain(inspector.observe(packet), Reason::PacketStructure), input.stop)?;
        ensure_fast!(index.whole && index.leading_known, SourceUnreadable, "incomplete source index");
        *self.facts.borrow_mut() = Some(inspector.finish()?);
        Ok(index)
    }
}

struct PacketInspector<'a> {
    video: &'a smartcut_core::VideoInfo,
    start_time: f64,
    callbacks: &'a dyn Callbacks,
    static_hdr: bool,
    is_hevc: bool,
    length_width: usize,
    original_sets: Vec<Vec<u8>>,
    hdr: Option<hevc_hdr::Guard>,
    clock: PictureClock,
    picture_times_us: Vec<i64>,
    idr_times_us: Vec<i64>,
    packets: u64,
}
impl<'a> PacketInspector<'a> {
    fn new(video: &'a smartcut_core::VideoInfo, start_time: f64,
        input: &ff::format::context::Input, callbacks: &'a dyn Callbacks) -> Result<Self> {
        ensure_fast!(matches!(video.codec.as_str(), "h264" | "hevc"), VideoCodec, "only H.264/HEVC is supported");
        ensure_fast!(input.format().name().split(',').any(|n| n == "mp4"), Container, "input is not MP4");
        ensure_fast!(input.streams().filter(|s| s.parameters().medium() == ff::media::Type::Video).count() == 1
            && input.streams().filter(|s| s.parameters().medium() == ff::media::Type::Audio).count() <= 1
            && input.streams().all(|s| matches!(s.parameters().medium(), ff::media::Type::Video | ff::media::Type::Audio)), TrackLayout, "additional streams");
        let stream = input.stream(video.stream_index).context("missing video stream")?;
        let static_hdr = video.codec == "hevc" && [16, 18].contains(&video.shape.transfer);
        ensure_fast!(stream.metadata().get("rotate").is_none(), Rotation, "rotation metadata");
        ensure_fast!(!stream.side_data().any(|d| d.kind() != ff::codec::packet::side_data::Type::DisplayMatrix
            && unsupported_side_data(d.kind(), static_hdr)), Metadata, "unsupported video metadata");
        let parameters = stream.parameters();
        let extradata = unsafe {
            let raw = parameters.as_ptr();
            ensure_fast!(!(*raw).extradata.is_null() && (*raw).extradata_size > 0, CodecConfiguration, "missing AVC configuration");
            std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize)
        };
        let is_hevc = video.codec == "hevc";
        if is_hevc { explain(hevc::validate_configuration(extradata), Reason::CodecConfiguration)?; }
        else { explain(avc1::validate_configuration(input, video.stream_index), Reason::CodecConfiguration)?; }
        let mut hdr = if is_hevc { Some(explain(hevc_hdr::Guard::new(extradata, static_hdr,
            video.shape.transfer), Reason::Metadata)?) } else { None };
        if let Some(guard) = &mut hdr {
            for side in stream.side_data() {
                explain(guard.observe_side_data(side.kind(), side.data()), Reason::Metadata)?;
            }
        }
        let original_sets = smartcut_core::bitstream::parameter_sets(&video.codec, extradata);
        ensure_fast!(!original_sets.is_empty(), CodecConfiguration, "missing AVC parameter sets");
        let length_width = match video.framing {
            smartcut_core::bitstream::NalFraming::Length(width) => width,
            _ => anyhow::bail!("MP4 AVC length prefixes required"),
        };
        Ok(Self { video, start_time, callbacks, static_hdr, is_hevc, length_width,
            original_sets, hdr, clock: PictureClock::new(video.frame_rate, video.time_base),
            picture_times_us: Vec::new(), idr_times_us: Vec::new(), packets: 0 })
    }

    fn observe(&mut self, packet: &ff::Packet) -> Result<()> {
        if self.packets % 64 == 0 { ensure!(!self.callbacks.is_cancelled(), "cancelled"); }
        self.packets += 1;
        ensure_fast!(!packet.flags().contains(ff::codec::packet::Flags::CORRUPT), PacketStructure, "corrupt source packet");
        ensure_fast!(!packet.side_data().any(|d| unsupported_side_data(d.kind(), self.static_hdr)), Metadata, "changing codec configuration or unsupported packet metadata");
        if packet.stream() != self.video.stream_index { return Ok(()); }
        if let Some(guard) = &mut self.hdr {
            for side in packet.side_data() {
                explain(guard.observe_side_data(side.kind(), side.data()), Reason::Metadata)?;
            }
        }
        let bytes = packet.data().context("empty video packet")?;
        let nals = if self.is_hevc { hevc::nals(bytes, self.length_width)? } else { avc_nals(bytes, self.length_width)? };
        if let Some(guard) = &mut self.hdr { explain(guard.inspect(&nals), Reason::Metadata)?; }
        let kind = |nal: &[u8]| if self.is_hevc { (nal[0] >> 1) & 63 } else { nal[0] & 31 };
        let idr = nals.iter().any(|nal| if self.is_hevc { matches!(kind(nal), 19 | 20) } else { kind(nal) == 5 });
        ensure_fast!(nals.iter().any(|nal| if self.is_hevc { kind(nal) <= 31 } else { (1..=5).contains(&kind(nal)) }), PacketStructure, "packet without a coded picture");
        if self.clock.origin.is_none() { ensure_fast!(idr, UnsafeSplice, "source does not begin at an IDR picture"); }
        ensure_fast!(nals.iter().filter(|nal| if self.is_hevc { matches!(kind(nal), 32..=34) } else { matches!(kind(nal), 7 | 8) }).all(|nal|
            self.original_sets.iter().any(|original| original.as_slice() == *nal)), Metadata, "parameters change inside the source");
        let pts = explain(packet.pts().context("video packet has no presentation timestamp"), Reason::Timestamps)?;
        let at = pts as f64 * self.video.time_base - self.start_time;
        self.clock.add(at)?;
        let at_us = micros(at);
        self.picture_times_us.push(at_us);
        if idr { self.idr_times_us.push(at_us); }
        Ok(())
    }

    fn finish(mut self) -> Result<PacketFacts> {
        self.clock.finish()?;
        ensure_fast!(!self.picture_times_us.is_empty(), NoCopySegment, "source has no video pictures");
        ensure!(!self.callbacks.is_cancelled(), "cancelled");
        self.picture_times_us.sort_unstable();
        self.idr_times_us.sort_unstable();
        Ok(PacketFacts { picture_times_us: self.picture_times_us.into_boxed_slice(),
            idr_times_us: self.idr_times_us.into_boxed_slice() })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Observer;
    impl Callbacks for Observer {
        fn is_cancelled(&self) -> bool { false }
        fn progress(&self, _: i32) {}
    }

    #[test]
    fn observed_walk_inspects_every_video_and_audio_packet_exactly_once() {
        let directory = std::env::temp_dir().join(format!("quickcut-observed-walk-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("source.mp4");
        assert!(std::process::Command::new("ffmpeg").args(["-v", "error", "-y", "-f", "lavfi", "-i",
            "testsrc2=size=160x96:rate=30:duration=2", "-f", "lavfi", "-i",
            "sine=sample_rate=48000:duration=2", "-c:v", "libx264", "-threads", "1",
            "-g", "30", "-c:a", "aac"]).arg(&path).status().unwrap().success());
        smartcut_core::init().unwrap();
        let mut demux = ff::format::input(&path).unwrap();
        let expected: Vec<_> = demux.packets().map(|(_, p)| (p.stream(), p.pts(), p.size())).collect();
        assert!(expected.iter().any(|p| p.0 == 1), "fixture must exercise non-video packets");
        struct Scan { observed: RefCell<Vec<(usize, Option<i64>, usize)>>, corrupt_audio: bool }
        impl smartcut_core::index::IndexSource for Scan {
            fn name(&self) -> &'static str { "test observed walk" }
            fn build(&self, input: smartcut_core::index::IndexInput) -> Result<smartcut_core::index::Index> {
                let callbacks = Observer;
                let mut inspector = PacketInspector::new(input.video, input.start_time, &input.ictx, &callbacks)?;
                let index = smartcut_core::index::walk_observed(input.video, input.start_time,
                    input.ictx, input.on, |packet| {
                        self.observed.borrow_mut().push((packet.stream(), packet.pts(), packet.size()));
                        if self.corrupt_audio && packet.stream() != input.video.stream_index {
                            let mut corrupt = packet.clone();
                            corrupt.set_flags(ff::codec::packet::Flags::CORRUPT);
                            inspector.observe(&corrupt)?;
                        }
                        inspector.observe(packet)
                    }, input.stop)?;
                assert_eq!(inspector.finish()?.picture_times_us.len(), 60);
                Ok(index)
            }
        }
        let scan = Scan { observed: RefCell::new(Vec::new()), corrupt_audio: false };
        let source = smartcut_core::scan_stoppable(path.to_str().unwrap(), &scan, None, None).unwrap();
        assert!(source.read_whole && source.leading_known);
        assert_eq!(*scan.observed.borrow(), expected, "one callback for every packet, including audio");
        let scan = Scan { observed: RefCell::new(Vec::new()), corrupt_audio: true };
        let error = smartcut_core::scan_stoppable(path.to_str().unwrap(), &scan, None, None).unwrap_err();
        assert_eq!(decline::code(&error), 19, "corrupt non-video packets must not be skipped");
        std::fs::remove_dir_all(directory).unwrap();
    }
}
