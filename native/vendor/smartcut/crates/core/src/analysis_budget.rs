//! Admission for frame-count-dependent analysis metadata. This is a cumulative
//! allocation ledger, deliberately not a live-byte estimate: old capacity is
//! never refunded when a vector grows or is dropped. Thus old + new storage
//! during reallocations, independent vectors, and later index clones all count.
//! It bounds requested Rust metadata storage, not FFmpeg buffers/process RSS.
use std::{cell::Cell, mem::size_of};
use anyhow::Result;

#[derive(Debug)]
pub struct AnalysisBudgetExceeded;
impl std::fmt::Display for AnalysisBudgetExceeded {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("analysis metadata budget unavailable")
    }
}
impl std::error::Error for AnalysisBudgetExceeded {}

pub struct AnalysisBudget { limit: usize, charged: Cell<usize> }
impl AnalysisBudget {
    pub fn new(limit: usize) -> Self { Self { limit, charged: Cell::new(0) } }
    pub fn charged(&self) -> usize { self.charged.get() }

    pub fn charge(&self, bytes: usize) -> Result<()> {
        let next = self.charged.get().checked_add(bytes).ok_or(AnalysisBudgetExceeded)?;
        if next > self.limit { return Err(AnalysisBudgetExceeded.into()); }
        self.charged.set(next);
        Ok(())
    }

    /// Reserve before push/extend. Geometric growth keeps long scans linear;
    /// exact reservation avoids Vec's implicit, unaccounted doubling. Allocation
    /// failure and arithmetic overflow have the same typed unavailable outcome.
    pub fn reserve<T>(&self, values: &mut Vec<T>, additional: usize) -> Result<()> {
        let needed = values.len().checked_add(additional).ok_or(AnalysisBudgetExceeded)?;
        if needed <= values.capacity() { return Ok(()); }
        let capacity = needed.max(values.capacity().checked_mul(2).ok_or(AnalysisBudgetExceeded)?).max(4);
        self.charge(capacity.checked_mul(size_of::<T>()).ok_or(AnalysisBudgetExceeded)?)?;
        values.try_reserve_exact(capacity - values.len()).map_err(|_| AnalysisBudgetExceeded)?;
        Ok(())
    }

    pub fn copy<T: Copy>(&self, values: &[T]) -> Result<Vec<T>> {
        let mut result = Vec::new();
        self.reserve(&mut result, values.len())?;
        result.extend_from_slice(values);
        Ok(result)
    }

    pub fn string(&self, value: &str) -> Result<String> {
        // The source is valid UTF-8 and copying cannot change that.
        Ok(String::from_utf8(self.copy(value.as_bytes())?).expect("copied UTF-8"))
    }
}

#[cfg(test)]
#[path = "analysis_budget_tests.rs"]
mod tests;
