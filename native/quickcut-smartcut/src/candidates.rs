use crate::Callbacks;
use anyhow::{ensure, Result};

const MAX_BOUNDARIES_PER_SIDE: usize = 16;
const MAX_CANDIDATE_PLANS_PER_RANGE: usize = 64;
pub(crate) const MAX_TOTAL_CANDIDATE_PLANS: usize = 1024;

/// The points have already passed measured closed-GOP and actual IDR checks.
/// Try the least destructive pairs first; a failed plan never approves a pair.
pub(crate) fn choose_range(points: &[i64], start: i64, end: i64, source_end: i64,
    callbacks: &dyn Callbacks, budget: &mut usize,
    mut valid: impl FnMut(i64, i64) -> bool) -> Result<(i64, i64)> {
    ensure!(!callbacks.is_cancelled(), "cancelled");
    let first = points.partition_point(|&point| point < start);
    let last = points.partition_point(|&point| point <= end);
    ensure!(first < last, "no safe inward boundaries");
    let starts: Vec<_> = points[first..last].iter().copied().take(MAX_BOUNDARIES_PER_SIDE).collect();
    let ends: Vec<_> = std::iter::once(end).filter(|_| end == source_end)
        .chain(points[first..last].iter().rev().copied().filter(|&point| point != end || end != source_end))
        .take(MAX_BOUNDARIES_PER_SIDE).collect();
    let mut pairs: Vec<_> = starts.iter().flat_map(|&from| ends.iter().copied()
        .filter(move |&to| to - from >= 100_000).map(move |to| (from, to))).collect();
    pairs.sort_unstable_by_key(|&(from, to)| ((from - start) + (end - to), from));
    for (from, to) in pairs.into_iter().take(MAX_CANDIDATE_PLANS_PER_RANGE) {
        ensure!(!callbacks.is_cancelled(), "cancelled");
        ensure!(*budget > 0, "safe boundary search budget exhausted");
        *budget -= 1;
        if valid(from, to) {
            ensure!(!callbacks.is_cancelled(), "cancelled");
            return Ok((from, to));
        }
    }
    anyhow::bail!("no validated copy range within the inward search budget")
}

#[cfg(test)]
#[path = "candidate_tests.rs"]
mod tests;
