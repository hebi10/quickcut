use std::{
    f64::consts::TAU,
    path::{Path, PathBuf},
    process::{Command, Output},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use ffmpeg_next as ff;
use quickcut_smartcut::{cut, cut_ranges, Callbacks};

const RATE: usize = 48_000;
const EARLY_TONES: [u32; 6] = [320, 520, 720, 60, 920, 1120];
const LATE_TONES: [u32; 6] = [440, 640, 840, 100, 1040, 1240];
const SINGLE_RANGE: &[(i64, i64)] = &[(3_000_000, 7_500_000)];
const REORDERED_RANGES: &[(i64, i64)] = &[(4_500_000, 8_000_000), (0, 3_000_000)];

struct Observer;
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { false }
    fn progress(&self, _: i32) {}
}

struct Directory(PathBuf);
impl Directory {
    fn new(name: &str) -> Self {
        let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!(
            "quickcut-audio-{name}-{}-{nonce}", std::process::id()
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        if std::thread::panicking() {
            eprintln!("retained failed audio fixture: {}", self.0.display());
        } else {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}

fn run(command: &mut Command) -> Output {
    let output = command.output().expect("host integration tests require ffmpeg and ffprobe");
    assert!(output.status.success(), "{command:?} failed: {}",
        String::from_utf8_lossy(&output.stderr));
    output
}

fn fixture(path: &Path, encoder: &str, channels: usize, bit_rate: Option<&str>) {
    // Every channel owns a different tone, and every tone changes at four
    // seconds. LFE stays below 120 Hz so AC-3's low-pass is not a false failure.
    let tones = (0..channels).map(|channel| format!(
        "0.2*sin(2*PI*if(lt(t,4),{},{})*t)",
        EARLY_TONES[channel], LATE_TONES[channel]
    )).collect::<Vec<_>>().join("|");
    let layout = match channels {
        1 => "mono",
        2 => "stereo",
        // AAC's standard six-channel configuration uses back surrounds.
        // AC-3 and E-AC-3 retain their usual side-surround input layout.
        6 if encoder == "aac" => "5.1",
        6 => "5.1(side)",
        _ => unreachable!(),
    };
    let audio = format!(
        "aevalsrc=exprs='{tones}':sample_rate={RATE}:duration=8:channel_layout={layout}"
    );
    let mut command = Command::new("ffmpeg");
    command.args(["-v", "error", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=160x96:rate=30:duration=8", "-f", "lavfi", "-i"])
        .arg(audio).args(["-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264",
            "-preset", "veryfast", "-threads", "1", "-pix_fmt", "yuv420p", "-g", "60",
            "-keyint_min", "60", "-sc_threshold", "0", "-bf", "2", "-x264-params",
            "open-gop=0", "-c:a", encoder]);
    if let Some(bit_rate) = bit_rate { command.args(["-b:a", bit_rate]); }
    // FFmpeg 6 requires explicit opt-in for FLAC's MP4 sample entry.
    if encoder == "flac" { command.args(["-strict", "-2"]); }
    run(command.args(["-movflags", "+faststart"]).arg(path));
}

fn assert_audio_format(path: &Path, codec: &str, channels: usize) {
    let probe = run(Command::new("ffprobe").args(["-v", "error", "-select_streams", "a:0",
        "-show_entries", "stream=codec_name,sample_rate,channels", "-of",
        "default=noprint_wrappers=1"]).arg(path));
    let text = String::from_utf8(probe.stdout).unwrap();
    for expected in [format!("codec_name={codec}"), format!("sample_rate={RATE}"),
        format!("channels={channels}")] {
        assert!(text.lines().any(|line| line == expected),
            "{} must contain {expected}: {text}", path.display());
    }
}

fn assert_standard_aac_configuration(path: &Path, channels: usize) {
    let input = ff::format::input(path).unwrap();
    let audio = input.streams().best(ff::media::Type::Audio).unwrap();
    let parameters = audio.parameters();
    let config = unsafe {
        let raw = parameters.as_ptr();
        assert!(!(*raw).extradata.is_null() && (*raw).extradata_size >= 2,
            "AAC output must include an AudioSpecificConfig");
        std::slice::from_raw_parts((*raw).extradata, (*raw).extradata_size as usize)
    };
    assert_eq!(config[0] >> 3, 2, "output must use AAC-LC");
    assert_eq!(((config[0] & 7) << 1) | (config[1] >> 7), 3,
        "fixture output must retain the standard 48 kHz frequency index");
    let expected_configuration = match channels {
        1 => 1,
        2 => 2,
        6 => 6,
        _ => unreachable!(),
    };
    // A side-surround PCE can still decode in FFmpeg while Android's AAC
    // decoder rejects ConfigRaw before producing sound. Check the actual
    // output header as well as decoded channel count and distinct tones.
    assert_eq!((config[1] >> 3) & 15, expected_configuration,
        "AAC output must use a standard channel configuration, not a PCE: {config:02x?}");
}

fn timing_diagnostics(path: &Path) -> String {
    let probe = run(Command::new("ffprobe").args(["-v", "error", "-select_streams", "v:0",
        "-show_entries", "packet=pts_time,dts_time,duration_time,flags:stream=time_base,start_time,duration_ts,duration,nb_frames,avg_frame_rate,r_frame_rate:format=start_time,duration",
        "-of", "compact"]).arg(path));
    let text = String::from_utf8(probe.stdout).unwrap();
    let lines: Vec<_> = text.lines().collect();
    format!("{}\n{}\n...\n{}", path.display(),
        lines.iter().take(4).copied().collect::<Vec<_>>().join("\n"),
        lines.iter().skip(lines.len().saturating_sub(12)).copied().collect::<Vec<_>>().join("\n"))
}

fn video_pictures(path: &Path) -> Vec<(i64, Vec<u8>)> {
    let mut input = ff::format::input(path).unwrap();
    let video = input.streams().best(ff::media::Type::Video).unwrap().index();
    let time_base: f64 = input.stream(video).unwrap().time_base().into();
    let mut pictures = Vec::new();
    for (stream, packet) in input.packets() {
        if stream.index() != video { continue; }
        let data = packet.data().unwrap();
        let mut at = 0;
        let mut picture = Vec::new();
        while at < data.len() {
            assert!(at + 4 <= data.len(), "truncated AVC NAL length");
            let length = u32::from_be_bytes(data[at..at + 4].try_into().unwrap()) as usize;
            at += 4;
            assert!(length > 0 && at + length <= data.len(), "truncated AVC NAL");
            if (1..=5).contains(&(data[at] & 31)) {
                picture.extend_from_slice(&data[at..at + length]);
            }
            at += length;
        }
        assert!(!picture.is_empty());
        pictures.push(((packet.pts().unwrap() as f64 * time_base * 1_000_000.0).round() as i64,
            picture));
    }
    pictures
}

fn source_time(ranges: &[(i64, i64)], mut output_us: i64) -> i64 {
    for &(start, end) in ranges {
        if output_us < end - start { return start + output_us; }
        output_us -= end - start;
    }
    panic!("output timestamp is beyond the requested ranges");
}

fn assert_audio_content(path: &Path, channels: usize, ranges: &[(i64, i64)]) {
    // Do not pass -ac: a forced output layout could hide an accidental downmix.
    let decoded = run(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"])
        .arg(path).args(["-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", "-"]));
    assert_eq!(decoded.stdout.len() % (4 * channels), 0);
    let samples: Vec<f32> = decoded.stdout.chunks_exact(4)
        .map(|bytes| f32::from_le_bytes(bytes.try_into().unwrap())).collect();
    let duration_us: i64 = ranges.iter().map(|(start, end)| end - start).sum();
    let expected_frames = (duration_us * RATE as i64 / 1_000_000) as usize;
    let actual_frames = samples.len() / channels;
    // Raw AAC decoding can expose its last padded 1024-sample frame, but must
    // never truncate selected sound or add another complete encoder frame.
    assert!((expected_frames..expected_frames + 1024).contains(&actual_frames),
        "decoded audio has {actual_frames} samples per channel; expected {expected_frames} plus less than one AAC frame");

    let window = RATE / 10;
    for first in (0..expected_frames).step_by(window) {
        assert!(first + window <= expected_frames);
        let middle_us = ((first + window / 2) * 1_000_000 / RATE) as i64;
        let tones = if source_time(ranges, middle_us) < 4_000_000 { EARLY_TONES } else { LATE_TONES };
        for channel in 0..channels {
            let frequency = f64::from(tones[channel]);
            let mut power = 0.0;
            let mut real = 0.0;
            let mut imaginary = 0.0;
            for offset in 0..window {
                let sample = f64::from(samples[(first + offset) * channels + channel]);
                assert!(sample.is_finite(), "decoded audio contains a non-finite sample");
                let phase = TAU * frequency * offset as f64 / RATE as f64;
                power += sample * sample;
                real += sample * phase.cos();
                imaginary += sample * phase.sin();
            }
            let rms = (power / window as f64).sqrt();
            let amplitude = 2.0 * real.hypot(imaginary) / window as f64;
            assert!(rms > 0.04 && amplitude > 0.06,
                "channel {channel} lost its {frequency} Hz tone at {middle_us} us: RMS={rms}, tone={amplitude}");
            // The target tone must dominate each channel throughout both
            // sides of every join, not merely leave some audible sound.
            let tone_power_fraction = amplitude * amplitude / (2.0 * rms * rms);
            assert!(tone_power_fraction > 0.70,
                "channel {channel} contains the wrong sound at {middle_us} us: {frequency} Hz fraction={tone_power_fraction}");
        }
    }
}

fn exercise(codec: &str, encoder: &str, channels: usize, bit_rate: Option<&str>,
    ranges: &[(i64, i64)]) {
    let directory = Directory::new(codec);
    // Use MOV's PCM sample entries without depending on version-specific
    // MP4 PCM muxing support. Input still uses the MOV/MP4 demuxer.
    let extension = if codec.starts_with("pcm_") { "mov" } else { "mp4" };
    let source = directory.0.join(format!("source.{extension}"));
    let output = directory.0.join("cut.mp4");
    fixture(&source, encoder, channels, bit_rate);
    assert_audio_format(&source, codec, channels);
    let original = std::fs::read(&source).unwrap();
    let result = if ranges.len() == 1 {
        cut(source.to_str().unwrap(), output.to_str().unwrap(), ranges[0].0, ranges[0].1,
            false, Arc::new(Observer))
    } else {
        cut_ranges(source.to_str().unwrap(), output.to_str().unwrap(), ranges,
            false, Arc::new(Observer))
    };
    assert_eq!(std::fs::read(&source).unwrap(), original, "the source must remain untouched");
    let report = result.unwrap_or_else(|error| panic!("{codec} audio must support a fast cut: {error:#}"));
    let duration_us: i64 = ranges.iter().map(|(start, end)| end - start).sum();
    let expected_pictures = (duration_us * 30 / 1_000_000) as usize;
    assert!(report.copied_video_packets > 0, "audio conversion must keep video smart cutting");
    assert!(report.encoded_video_packets > 0, "fixture must exercise non-keyframe boundaries");
    assert_eq!(report.copied_video_packets + report.encoded_video_packets, expected_pictures as u64);

    let input = ff::format::input(&output).unwrap();
    assert_eq!(input.nb_streams(), 2, "keep exactly one video and one audio track");
    let video = input.streams().best(ff::media::Type::Video).unwrap();
    let video_duration_us = (video.duration() as f64 * f64::from(video.time_base()) * 1_000_000.0).round() as i64;
    let movie_duration_us = input.duration();
    drop(input);
    let duration_mismatch = (video_duration_us - duration_us).abs() > 1
        || (movie_duration_us - duration_us).abs() > 1_000;
    if duration_mismatch {
        eprintln!("{codec} duration diagnostic: video={video_duration_us}, movie={movie_duration_us}, expected={duration_us} us\n{}",
            timing_diagnostics(&output));
    }

    let decoded = run(Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"])
        .arg(&output).args(["-map", "0:v:0", "-fps_mode", "passthrough", "-pix_fmt",
            "yuv420p", "-f", "rawvideo", "-"]));
    assert_eq!(decoded.stdout.len(), expected_pictures * 160 * 96 * 3 / 2,
        "every retained picture must actually decode, without duplication or truncation");
    let source_pictures = video_pictures(&source);
    let output_pictures = video_pictures(&output);
    assert_eq!(output_pictures.len(), expected_pictures);
    let actual_copies = output_pictures.iter().filter(|(pts, picture)| {
        let expected_source_us = source_time(ranges, *pts);
        source_pictures.iter().any(|(source_pts, source_picture)|
            (*source_pts - expected_source_us).abs() <= 1 && source_picture == picture)
    }).count();
    assert_eq!(actual_copies as u64, report.copied_video_packets,
        "reported copies must retain the source VCL bytes at the selected timestamps");
    assert_audio_format(&output, "aac", channels);
    assert_audio_content(&output, channels, ranges);
    assert_standard_aac_configuration(&output, channels);
    // Check duration last so a metadata mismatch cannot hide decoding,
    // unchanged-picture, channel-loss, or audio-join failures in CI.
    if duration_mismatch {
        eprintln!("{codec}: all {expected_pictures} pictures decoded, {actual_copies} copied VCL payloads matched, and all {channels} audio channels passed sample-count and tone checks");
    }
    assert!((video_duration_us - duration_us).abs() <= 1,
        "video duration changed: {video_duration_us} instead of {duration_us} us");
    assert!((movie_duration_us - duration_us).abs() <= 1_000,
        "movie duration changed: {movie_duration_us} instead of {duration_us} us");
}

#[test]
fn stereo_mp3_keeps_both_channels_and_reordered_audio_across_the_join() {
    exercise("mp3", "libmp3lame", 2, Some("192k"), REORDERED_RANGES);
}

#[test]
fn ac3_surround_becomes_aac_without_downmixing_or_reencoding_all_video() {
    exercise("ac3", "ac3", 6, Some("384k"), SINGLE_RANGE);
}

#[test]
fn eac3_surround_becomes_aac_without_downmixing_or_reencoding_all_video() {
    exercise("eac3", "eac3", 6, Some("384k"), SINGLE_RANGE);
}

#[test]
fn lossless_alac_mono_becomes_aac_with_exact_picture_and_audio_bounds() {
    exercise("alac", "alac", 1, None, SINGLE_RANGE);
}

#[test]
fn opus_and_flac_keep_stereo_audio_content_and_copied_video() {
    for (codec, encoder, bit_rate, ranges) in [
        // Revisit the opening after a later range to exercise Opus pre-skip
        // and decoder state across an out-of-order audio join.
        ("opus", "libopus", Some("192k"), REORDERED_RANGES),
        ("flac", "flac", None, SINGLE_RANGE),
    ] {
        exercise(codec, encoder, 2, bit_rate, ranges);
    }
}

#[test]
fn little_endian_pcm_integer_widths_and_float_become_aac_without_reencoding_all_video() {
    for codec in ["pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le"] {
        exercise(codec, codec, 2, None, SINGLE_RANGE);
    }
}

#[test]
fn aac_surround_keeps_all_six_channels_during_a_mixed_video_cut() {
    exercise("aac", "aac", 6, Some("384k"), SINGLE_RANGE);
}
