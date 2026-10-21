use std::{collections::BTreeMap, path::{Path, PathBuf}, process::Command, sync::Arc};

use ffmpeg_next as ff;
use quickcut_smartcut::{cut, Callbacks};

struct Observer;
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, _: i32) {}
}

struct Fixture {
    name: &'static str,
    pixel_format: &'static str,
    primaries: &'static str,
    transfer: &'static str,
    matrix: &'static str,
    static_metadata: bool,
}

type Fields = BTreeMap<String, String>;

#[derive(Debug, Default, PartialEq, Eq)]
struct FrameEvidence {
    picture: Fields,
    mastering: Option<Fields>,
    content_light: Option<Fields>,
}

fn fields(values: &[(&str, &str)]) -> Fields {
    values.iter().map(|(key, value)| (key.to_string(), value.to_string())).collect()
}

fn expected_frame(fixture: &Fixture) -> FrameEvidence {
    FrameEvidence {
        picture: fields(&[
            ("pix_fmt", fixture.pixel_format), ("color_range", "tv"),
            ("color_space", fixture.matrix), ("color_primaries", fixture.primaries),
            ("color_transfer", fixture.transfer),
        ]),
        mastering: fixture.static_metadata.then(|| fields(&[
            ("red_x", "34000/50000"), ("red_y", "16000/50000"),
            ("green_x", "13250/50000"), ("green_y", "34500/50000"),
            ("blue_x", "7500/50000"), ("blue_y", "3000/50000"),
            ("white_point_x", "15635/50000"), ("white_point_y", "16450/50000"),
            ("min_luminance", "50/10000"), ("max_luminance", "10000000/10000"),
        ])),
        content_light: fixture.static_metadata.then(|| fields(&[
            ("max_content", "1000"), ("max_average", "400"),
        ])),
    }
}

fn run(command: &mut Command) -> std::process::Output {
    let output = command.output().unwrap_or_else(|error| panic!("{command:?}: {error}"));
    assert!(output.status.success(), "{command:?}\n{}", String::from_utf8_lossy(&output.stderr));
    output
}

fn probe(command: &mut Command) -> String {
    let output = run(command);
    // ffprobe can report a successful exit after logging decoder errors.
    assert!(output.stderr.is_empty(), "{command:?}\n{}", String::from_utf8_lossy(&output.stderr));
    String::from_utf8(output.stdout).unwrap()
}

fn make_fixture(fixture: &Fixture, path: &Path) {
    let mut parameters = String::from(
        "pools=1:frame-threads=1:keyint=60:min-keyint=60:scenecut=0:open-gop=0:bframes=2:log-level=error:repeat-headers=1");
    if fixture.static_metadata {
        parameters.push_str(":master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=1000,400");
    }
    run(Command::new("ffmpeg").args([
        "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30",
        "-t", "8", "-an", "-c:v", "libx265", "-preset", "fast",
        "-pix_fmt", fixture.pixel_format, "-color_range", "tv",
        "-color_primaries", fixture.primaries, "-color_trc", fixture.transfer,
        "-colorspace", fixture.matrix, "-x265-params", &parameters, "-tag:v", "hvc1",
    ]).arg(path));
}

fn stream_evidence(path: &Path) -> Fields {
    probe(Command::new("ffprobe").args([
        "-v", "error", "-select_streams", "v:0", "-show_entries",
        "stream=codec_name,profile,pix_fmt,color_range,color_space,color_primaries,color_transfer",
        "-of", "default=noprint_wrappers=1",
    ]).arg(path)).lines().map(|line| {
        let (key, value) = line.split_once('=').unwrap();
        (key.to_string(), value.to_string())
    }).collect()
}

fn frame_evidence(path: &Path) -> Vec<FrameEvidence> {
    // Decode every picture. Stream headers alone cannot demonstrate that the
    // rewritten boundary pictures retain their color and static HDR signalling.
    let output = probe(Command::new("ffprobe").args([
        "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries",
        "frame=pix_fmt,color_range,color_space,color_primaries,color_transfer:frame_side_data=side_data_type,red_x,red_y,green_x,green_y,blue_x,blue_y,white_point_x,white_point_y,min_luminance,max_luminance,max_content,max_average",
        "-of", "default=noprint_wrappers=0",
    ]).arg(path));
    let mut frames = Vec::new();
    let mut frame: Option<FrameEvidence> = None;
    let mut side: Option<Fields> = None;
    for line in output.lines() {
        match line {
            "[FRAME]" => {
                assert!(frame.is_none());
                frame = Some(FrameEvidence::default());
            }
            "[/FRAME]" => {
                assert!(side.is_none());
                frames.push(frame.take().unwrap());
            }
            "[SIDE_DATA]" => {
                assert!(side.is_none());
                side = Some(Fields::new());
            }
            "[/SIDE_DATA]" => {
                let mut data = side.take().unwrap();
                match data.remove("side_data_type").as_deref() {
                    Some("Mastering display metadata") => {
                        assert!(frame.as_mut().unwrap().mastering.replace(data).is_none());
                    }
                    Some("Content light level metadata") => {
                        assert!(frame.as_mut().unwrap().content_light.replace(data).is_none());
                    }
                    _ => {}, // Encoder-identification SEI is not HDR metadata.
                }
            }
            _ => {
                let (key, value) = line.split_once('=').unwrap();
                let target = match side.as_mut() {
                    Some(side) => side,
                    None => &mut frame.as_mut().unwrap().picture,
                };
                assert!(target.insert(key.to_string(), value.to_string()).is_none());
            }
        }
    }
    assert!(frame.is_none() && side.is_none());
    frames
}

fn decode_without_errors(path: &Path) {
    run(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-err_detect", "explode", "-i"])
        .arg(path).args(["-map", "0:v:0", "-f", "null", "-"]));
}

struct Picture {
    pts_us: i64,
    nals: Vec<Vec<u8>>,
}

fn pictures(path: &Path) -> (Vec<Vec<u8>>, Vec<Picture>) {
    let mut input = ff::format::input(path).unwrap();
    let stream = input.streams().best(ff::media::Type::Video).unwrap();
    let video = stream.index();
    let time_base: f64 = stream.time_base().into();
    let parameters = stream.parameters();
    let configuration = unsafe {
        let raw = parameters.as_ptr();
        assert!(!(*raw).extradata.is_null() && (*raw).extradata_size >= 23);
        std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize)
    };
    assert_eq!(configuration[0], 1, "expected an HEVC configuration record");
    let length_width = (configuration[21] & 3) as usize + 1;
    let mut at = 23;
    let mut initialization = Vec::new();
    for _ in 0..configuration[22] {
        assert!(at + 3 <= configuration.len());
        let kind = configuration[at] & 63;
        let count = u16::from_be_bytes(configuration[at + 1..at + 3].try_into().unwrap());
        at += 3;
        for _ in 0..count {
            assert!(at + 2 <= configuration.len());
            let length = u16::from_be_bytes(configuration[at..at + 2].try_into().unwrap()) as usize;
            at += 2;
            assert!(length >= 2 && at + length <= configuration.len());
            // Do not seed a fresh decoder with any container-level HDR SEI.
            // Only the actual boundary packets may supply that metadata.
            if matches!(kind, 32..=34) {
                initialization.push(configuration[at..at + length].to_vec());
            }
            at += length;
        }
    }
    assert_eq!(at, configuration.len());
    for kind in 32..=34 {
        assert!(initialization.iter().any(|nal| (nal[0] >> 1) & 63 == kind));
    }
    let mut pictures = Vec::new();
    for (stream, packet) in input.packets() {
        if stream.index() != video { continue; }
        let bytes = packet.data().unwrap();
        let mut at = 0;
        let mut nals = Vec::new();
        while at < bytes.len() {
            assert!(at + length_width <= bytes.len());
            let length = bytes[at..at + length_width].iter()
                .fold(0usize, |length, byte| (length << 8) | *byte as usize);
            at += length_width;
            assert!(length >= 2 && at + length <= bytes.len());
            nals.push(bytes[at..at + length].to_vec());
            at += length;
        }
        assert!(nals.iter().any(|nal| (nal[0] >> 1) & 63 <= 31));
        pictures.push(Picture {
            pts_us: (packet.pts().unwrap() as f64 * time_base * 1_000_000.0).round() as i64,
            nals,
        });
    }
    (initialization, pictures)
}

fn vcl(picture: &Picture) -> Vec<&[u8]> {
    picture.nals.iter().filter(|nal| (nal[0] >> 1) & 63 <= 31)
        .map(Vec::as_slice).collect()
}

fn independent_segment(path: &Path, initialization: &[Vec<u8>], pictures: &[Picture], start_us: i64, end_us: i64) {
    let selected: Vec<_> = pictures.iter().filter(|picture|
        (start_us..end_us).contains(&picture.pts_us)).collect();
    assert!(!selected.is_empty());
    assert!(selected[0].nals.iter().any(|nal| matches!((nal[0] >> 1) & 63, 19 | 20)),
        "each isolated segment must begin at an independently decodable IDR");
    let mut bytes = Vec::new();
    for nal in initialization.iter().chain(selected.iter().flat_map(|picture| &picture.nals)) {
        bytes.extend_from_slice(&[0, 0, 0, 1]);
        bytes.extend_from_slice(nal);
    }
    std::fs::write(path, bytes).unwrap();
}

fn directory(fixture: &Fixture) -> PathBuf {
    let directory = std::env::temp_dir().join(format!("quickcut-hdr-{}-{}", fixture.name, std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let _ = std::fs::remove_file(directory.join("cut.mp4"));
    let _ = std::fs::remove_file(directory.join("cut.mp4.smartcut.mp4"));
    directory
}

fn check_supported(fixture: Fixture) {
    let directory = directory(&fixture);
    let source = directory.join("source.mp4");
    let output = directory.join("cut.mp4");
    make_fixture(&fixture, &source);
    let original = std::fs::read(&source).unwrap();
    let source_stream = stream_evidence(&source);
    let mut expected_stream = expected_frame(&fixture).picture;
    expected_stream.insert("codec_name".into(), "hevc".into());
    expected_stream.insert("profile".into(), "Main 10".into());
    assert_eq!(source_stream, expected_stream, "fixture must really be Main 10 with the requested color");
    let source_frames = frame_evidence(&source);
    assert_eq!(source_frames.len(), 240);
    for (index, frame) in source_frames.iter().enumerate() {
        assert_eq!(frame, &expected_frame(&fixture), "source frame {index}");
    }

    let report = cut(source.to_str().unwrap(), output.to_str().unwrap(), 3_000_000, 7_500_000,
        false, Arc::new(Observer)).unwrap();
    assert_eq!((report.copied_video_packets, report.encoded_video_packets), (60, 75));
    decode_without_errors(&output);
    assert_eq!(stream_evidence(&output), source_stream);
    let output_frames = frame_evidence(&output);
    assert_eq!(output_frames.len(), 135, "all retained pictures must actually decode");
    assert_eq!(output_frames, source_frames[90..225], "color and static metadata on every retained picture");

    let (_, source_pictures) = pictures(&source);
    let (initialization, output_pictures) = pictures(&output);
    let middle: Vec<_> = source_pictures.iter().filter(|picture|
        (4_000_000..6_000_000).contains(&picture.pts_us)).collect();
    assert_eq!(middle.len(), 60);
    for picture in middle {
        let retained = output_pictures.iter().find(|output|
            (output.pts_us - (picture.pts_us - 3_000_000)).abs() <= 1).unwrap();
        assert_eq!(vcl(retained), vcl(picture), "copied middle VCL and presentation times must remain unchanged");
    }

    // Restart the external decoder for each region, without MP4 metadata or
    // any SEI from hvcC. In particular, the tail cannot inherit mastering data
    // from the copied middle and hide a failure to encode its own HDR SEI.
    for (name, start_us, end_us, source_start, source_end) in [
        ("encoded-head", 0, 1_000_000, 90, 120),
        ("copied-middle", 1_000_000, 3_000_000, 120, 180),
        ("encoded-tail", 3_000_000, 4_500_000, 180, 225),
    ] {
        let segment = directory.join(format!("{name}.hevc"));
        independent_segment(&segment, &initialization, &output_pictures, start_us, end_us);
        decode_without_errors(&segment);
        let decoded = frame_evidence(&segment);
        assert_eq!(decoded.len(), source_end - source_start, "{name} decoded picture count");
        assert_eq!(decoded, source_frames[source_start..source_end], "{name} in-band color and static metadata");
    }
    assert_eq!(std::fs::read(&source).unwrap(), original);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn main10_sdr_splices_keep_ten_bit_pictures_and_bt709() {
    check_supported(Fixture {
        name: "sdr10", pixel_format: "yuv420p10le", primaries: "bt709",
        transfer: "bt709", matrix: "bt709", static_metadata: false,
    });
}

#[test]
fn hdr10_splices_preserve_pq_and_static_metadata_in_both_encoded_boundaries() {
    check_supported(Fixture {
        name: "pq10", pixel_format: "yuv420p10le", primaries: "bt2020",
        transfer: "smpte2084", matrix: "bt2020nc", static_metadata: true,
    });
}

#[test]
fn hlg_splices_keep_ten_bit_pictures_and_bt2020() {
    check_supported(Fixture {
        name: "hlg10", pixel_format: "yuv420p10le", primaries: "bt2020",
        transfer: "arib-std-b67", matrix: "bt2020nc", static_metadata: false,
    });
}

#[test]
fn twelve_bit_hevc_is_declined_without_publishing_or_changing_source() {
    let fixture = Fixture {
        name: "sdr12", pixel_format: "yuv420p12le", primaries: "bt709",
        transfer: "bt709", matrix: "bt709", static_metadata: false,
    };
    let directory = directory(&fixture);
    let source = directory.join("source.mp4");
    let output = directory.join("cut.mp4");
    make_fixture(&fixture, &source);
    let original = std::fs::read(&source).unwrap();
    assert_eq!(stream_evidence(&source).get("pix_fmt").map(String::as_str), Some("yuv420p12le"));
    decode_without_errors(&source);
    let frames = frame_evidence(&source);
    assert_eq!(frames.len(), 240, "the rejected source must be a valid twelve-bit recording");
    for frame in frames { assert_eq!(frame, expected_frame(&fixture)); }
    let error = cut(source.to_str().unwrap(), output.to_str().unwrap(), 3_000_000, 7_500_000,
        false, Arc::new(Observer)).unwrap_err();
    assert!(format!("{error:#}").contains("PixelFormat"), "{error:#}");
    assert!(!output.exists());
    assert!(!directory.join("cut.mp4.smartcut.mp4").exists());
    assert_eq!(std::fs::read(&source).unwrap(), original);
    std::fs::remove_dir_all(directory).unwrap();
}
