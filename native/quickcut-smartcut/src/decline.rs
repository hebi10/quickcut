use anyhow::{Error, Result};

// Stable JNI codes, shared with FastExportReason. Never expose paths or raw errors in the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i64)]
pub(crate) enum Reason {
    SourceUnreadable = 1,
    VideoCodec = 2,
    PixelFormat = 3,
    PictureStructure = 4,
    FrameRate = 5,
    Dimensions = 6,
    ColorFormat = 7,
    AudioFormat = 8,
    TrackLayout = 9,
    SourceDuration = 10,
    Container = 11,
    Rotation = 12,
    Metadata = 13,
    CodecConfiguration = 14,
    UnsafeSplice = 15,
    NoCopySegment = 16,
    ExactBoundary = 17,
    Timestamps = 18,
    PacketStructure = 19,
    OutputValidation = 20,
    ProcessingFailed = 21,
    AnalysisBudget = 22,
}

impl std::fmt::Display for Reason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "{self:?}") }
}
impl std::error::Error for Reason {}

pub(crate) fn code(error: &Error) -> i64 {
    if error.downcast_ref::<smartcut_core::analysis_budget::AnalysisBudgetExceeded>().is_some() {
        return Reason::AnalysisBudget as i64;
    }
    error.downcast_ref::<Reason>().map_or(0, |reason| *reason as i64)
}

pub(crate) fn explain<T>(result: Result<T>, reason: Reason) -> Result<T> {
    result.map_err(|error| if code(&error) == 0 { error.context(reason) } else { error })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn budget_unavailability_survives_packet_and_source_error_contexts() {
        let budget = smartcut_core::analysis_budget::AnalysisBudget::new(0);
        let mut values = Vec::<i64>::new();
        let error = explain(explain(budget.reserve(&mut values, 1), Reason::PacketStructure),
            Reason::SourceUnreadable).unwrap_err();
        assert_eq!(code(&error), 22, "JNI must emit the budget reason, not a generic scan failure");
        assert_eq!(values.capacity(), 0);
    }
}
