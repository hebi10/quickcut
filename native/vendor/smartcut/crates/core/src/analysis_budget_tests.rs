use super::*;

#[test]
fn refused_growth_does_not_allocate_or_change_the_vector() {
    let budget = AnalysisBudget::new(32);
    let mut values = Vec::<u64>::new();
    budget.reserve(&mut values, 4).unwrap();
    values.extend([1, 2, 3, 4]);
    let before = (values.capacity(), budget.charged());
    let error = budget.reserve(&mut values, 1).unwrap_err();
    assert!(error.downcast_ref::<AnalysisBudgetExceeded>().is_some());
    assert_eq!((values.capacity(), budget.charged()), before);
    assert_eq!(values, [1, 2, 3, 4]);
}

#[test]
fn reallocation_charges_the_old_and_new_storage_and_shared_vectors() {
    let budget = AnalysisBudget::new(96);
    let mut first = Vec::<u64>::new();
    budget.reserve(&mut first, 4).unwrap();
    first.extend([1, 2, 3, 4]);
    budget.reserve(&mut first, 1).unwrap();
    assert_eq!(budget.charged(), 96);
    let mut duplicate = Vec::<u64>::new();
    assert!(budget.reserve(&mut duplicate, 4).is_err());
    assert_eq!(duplicate.capacity(), 0);
}

#[test]
fn overflow_is_a_typed_decline_before_reservation() {
    let budget = AnalysisBudget::new(usize::MAX);
    let mut values = Vec::<u64>::new();
    let error = budget.reserve(&mut values, usize::MAX).unwrap_err();
    assert!(error.downcast_ref::<AnalysisBudgetExceeded>().is_some());
    assert_eq!(values.capacity(), 0);
}
