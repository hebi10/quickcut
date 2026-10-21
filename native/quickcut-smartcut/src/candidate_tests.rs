use super::*;
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Default)]
struct Observer(AtomicBool);
impl Callbacks for Observer {
    fn is_cancelled(&self) -> bool { self.0.load(Ordering::Relaxed) }
    fn progress(&self, _: i32) {}
}

#[test]
fn preserves_microsecond_boundaries_and_continues_after_an_unsafe_nearest_plan() {
    let mut budget = MAX_TOTAL_CANDIDATE_PLANS;
    let mut attempted = Vec::new();
    let result = choose_range(&[1_000_333, 2_000_667, 3_000_999, 4_001_333],
        800_000, 4_500_000, 5_000_000, &Observer::default(), &mut budget,
        |start, end| { attempted.push((start, end)); start != 1_000_333 }).unwrap();
    assert_eq!(attempted.first(), Some(&(1_000_333, 4_001_333)));
    assert_eq!(result, (2_000_667, 4_001_333));
    assert!(result.0 >= 800_000 && result.1 <= 4_500_000);
}

#[test]
fn candidate_search_is_bounded_and_cancellable_without_returning_an_unchecked_pair() {
    let points: Vec<_> = (0..200).map(|n| n * 1_000_001).collect();
    let mut budget = MAX_TOTAL_CANDIDATE_PLANS;
    let mut attempts = 0;
    assert!(choose_range(&points, 0, 190_000_000, 200_000_000,
        &Observer::default(), &mut budget, |_, _| { attempts += 1; false }).is_err());
    assert!(attempts <= MAX_CANDIDATE_PLANS_PER_RANGE);
    let observer = Observer::default();
    let mut attempts = 0;
    assert!(choose_range(&points, 0, 190_000_000, 200_000_000,
        &observer, &mut budget, |_, _| {
            attempts += 1;
            observer.0.store(true, Ordering::Relaxed);
            false
        }).is_err());
    assert_eq!(attempts, 1);
}

#[test]
fn never_proposes_a_short_clip_or_moves_a_boundary_outward() {
    let mut budget = MAX_TOTAL_CANDIDATE_PLANS;
    assert!(choose_range(&[1_000_001, 1_050_001], 999_999, 1_099_999, 2_000_000,
        &Observer::default(), &mut budget, |_, _| true).is_err());
    assert_eq!(choose_range(&[1_000_001, 1_100_001], 999_999, 1_100_001, 2_000_000,
        &Observer::default(), &mut budget, |_, _| true).unwrap(), (1_000_001, 1_100_001));
}
