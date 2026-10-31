//! Bind validated immutable analysis to the current file capability without an
//! infallible Source::clone (which duplicates every access/leading index).
use anyhow::Result;
use smartcut_core::{analysis_budget::AnalysisBudget, AccessPoint, AudioInfo, Source, VideoInfo};

fn option_string(value: &Option<String>, budget: &AnalysisBudget) -> Result<Option<String>> {
    value.as_deref().map(|text| budget.string(text)).transpose()
}
fn audio(value: &AudioInfo, budget: &AnalysisBudget) -> Result<AudioInfo> {
    let said = value.said.as_ref().map(|said| -> Result<smartcut_core::si::SoundTrack> {
        Ok(smartcut_core::si::SoundTrack { component_tag: said.component_tag, main: said.main,
            language: option_string(&said.language, budget)?, name: option_string(&said.name, budget)?,
            arrangement: said.arrangement })
    }).transpose()?;
    Ok(AudioInfo { stream_index: value.stream_index, pid: value.pid,
        language: option_string(&value.language, budget)?, codec: budget.string(&value.codec)?,
        sample_rate: value.sample_rate, channels: value.channels, bits: value.bits,
        time_base: value.time_base, bit_rate: value.bit_rate, said })
}

pub(crate) fn bind(source: &Source, input: &str, budget: &AnalysisBudget) -> Result<Source> {
    // These are the adapter's existing eligibility restrictions. Do not silently
    // omit newly supported metadata if the wider core ever changes shape.
    ensure_fast!(source.input.parts.is_empty() && source.input.range.is_none()
        && source.joins.is_empty() && source.captions.is_empty() && source.graphics.is_empty()
        && source.subpictures.is_empty() && source.dropped.is_empty()
        && source.video.vc1.is_none() && source.video.field_shape.is_none(),
        Container, "unsupported source binding metadata");
    let mut points = Vec::new();
    budget.reserve(&mut points, source.points.len())?;
    for point in &source.points {
        points.push(AccessPoint { time: point.time, lead_start: point.lead_start,
            lead_indices: budget.copy(&point.lead_indices)?, droppable: point.droppable,
            pos: point.pos, measured: point.measured });
    }
    let value = &source.video;
    let video = VideoInfo { stream_index: value.stream_index, codec: budget.string(&value.codec)?,
        width: value.width, height: value.height, frame_rate: value.frame_rate,
        base_rate: value.base_rate, has_b_frames: value.has_b_frames, time_base: value.time_base,
        sample_aspect_ratio: value.sample_aspect_ratio, framing: value.framing, shape: value.shape,
        pulldown: value.pulldown, variable_rate: value.variable_rate, field_order: value.field_order,
        bit_rate: value.bit_rate, vc1: None, field_shape: None };
    let mut audios = Vec::new();
    budget.reserve(&mut audios, source.audios.len())?;
    for value in &source.audios { audios.push(audio(value, budget)?); }
    let audio = source.audio.as_ref().map(|value| audio(value, budget)).transpose()?;
    // String -> OsString -> PathBuf transfers the owned allocation on Android
    // and the Unix host test platform; no extra path-sized clone is needed.
    let file = std::path::PathBuf::from(budget.string(input)?);
    Ok(Source {
        path: budget.string(input)?,
        input: smartcut_core::input::Input { spec: budget.string(input)?, url: budget.string(input)?,
            file, range: None, parts: Vec::new() },
        video, audio, audios, captions: Vec::new(), graphics: Vec::new(), subpictures: Vec::new(),
        dropped: Vec::new(), duration: source.duration, start_time: source.start_time,
        chapters: budget.copy(&source.chapters)?, points, leading_known: source.leading_known,
        index_name: source.index_name, read_whole: source.read_whole, seek_margin: source.seek_margin,
        on_a_ts: source.on_a_ts, joins: Vec::new(), byte_seekable: source.byte_seekable,
    })
}
