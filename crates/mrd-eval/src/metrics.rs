//! Classification metrics for heavily imbalanced alerting problems.

use serde::Serialize;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize)]
pub struct Confusion {
    pub tp: u64,
    pub fp: u64,
    pub fn_: u64,
    pub tn: u64,
}

impl Confusion {
    pub fn add(&mut self, predicted: bool, actual: bool) {
        match (predicted, actual) {
            (true, true) => self.tp += 1,
            (true, false) => self.fp += 1,
            (false, true) => self.fn_ += 1,
            (false, false) => self.tn += 1,
        }
    }
    pub fn precision(&self) -> f64 {
        ratio(self.tp, self.tp + self.fp)
    }
    pub fn recall(&self) -> f64 {
        ratio(self.tp, self.tp + self.fn_)
    }
    pub fn f1(&self) -> f64 {
        let (p, r) = (self.precision(), self.recall());
        if p + r == 0.0 {
            0.0
        } else {
            2.0 * p * r / (p + r)
        }
    }
    pub fn total(&self) -> u64 {
        self.tp + self.fp + self.fn_ + self.tn
    }
    /// Alerts raised per 10,000 transactions.
    pub fn alerts_per_10k(&self) -> f64 {
        let t = self.total();
        if t == 0 {
            0.0
        } else {
            (self.tp + self.fp) as f64 * 10_000.0 / t as f64
        }
    }
}

fn ratio(a: u64, b: u64) -> f64 {
    if b == 0 {
        0.0
    } else {
        a as f64 / b as f64
    }
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq)]
pub struct PrPoint {
    pub threshold: f64,
    pub precision: f64,
    pub recall: f64,
    pub f1: f64,
    pub alerts: u64,
}

/// Full precision-recall sweep over distinct score thresholds (predict positive if score >= t).
pub fn pr_sweep(scores: &[(f64, bool)]) -> Vec<PrPoint> {
    let mut v: Vec<(f64, bool)> = scores.to_vec();
    v.sort_by(|a, b| b.0.total_cmp(&a.0));
    let pos = v.iter().filter(|x| x.1).count() as u64;
    let mut out = Vec::new();
    let (mut tp, mut fp) = (0u64, 0u64);
    let mut i = 0;
    while i < v.len() {
        let t = v[i].0;
        while i < v.len() && v[i].0 == t {
            if v[i].1 {
                tp += 1;
            } else {
                fp += 1;
            }
            i += 1;
        }
        let p = ratio(tp, tp + fp);
        let r = ratio(tp, pos);
        let f1 = if p + r == 0.0 {
            0.0
        } else {
            2.0 * p * r / (p + r)
        };
        out.push(PrPoint {
            threshold: t,
            precision: p,
            recall: r,
            f1,
            alerts: tp + fp,
        });
    }
    out
}

/// Average precision (area under the step PR curve).
pub fn average_precision(scores: &[(f64, bool)]) -> f64 {
    let sweep = pr_sweep(scores);
    let mut ap = 0.0;
    let mut prev_r = 0.0;
    for p in &sweep {
        ap += (p.recall - prev_r) * p.precision;
        prev_r = p.recall;
    }
    ap
}

/// Threshold maximising F1 (ties: the higher threshold, i.e. fewer alerts).
pub fn best_f1(scores: &[(f64, bool)]) -> Option<PrPoint> {
    pr_sweep(scores)
        .into_iter()
        .fold(None, |best: Option<PrPoint>, p| match best {
            Some(b) if b.f1 >= p.f1 => Some(b),
            _ => Some(p),
        })
}

/// Down-sample a PR sweep to about `n` points for plotting.
pub fn thin(points: &[PrPoint], n: usize) -> Vec<PrPoint> {
    if points.len() <= n {
        return points.to_vec();
    }
    let step = points.len() as f64 / n as f64;
    let mut out: Vec<PrPoint> = (0..n).map(|i| points[(i as f64 * step) as usize]).collect();
    out.push(*points.last().expect("non-empty"));
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn confusion_basics() {
        let mut c = Confusion::default();
        c.add(true, true);
        c.add(true, false);
        c.add(false, true);
        c.add(false, false);
        assert_eq!(c.precision(), 0.5);
        assert_eq!(c.recall(), 0.5);
        assert_eq!(c.f1(), 0.5);
        assert_eq!(c.alerts_per_10k(), 5000.0);
    }

    #[test]
    fn perfect_ranking_has_ap_one() {
        let s = vec![(0.9, true), (0.8, true), (0.2, false), (0.1, false)];
        assert!((average_precision(&s) - 1.0).abs() < 1e-12);
        let b = best_f1(&s).unwrap();
        assert_eq!(b.threshold, 0.8);
        assert_eq!(b.f1, 1.0);
    }

    proptest! {
        #[test]
        fn sweep_is_monotone_in_recall(v in proptest::collection::vec((0.0f64..1.0, any::<bool>()), 1..200)) {
            let sw = pr_sweep(&v);
            for w in sw.windows(2) {
                prop_assert!(w[1].recall >= w[0].recall);
                prop_assert!(w[1].alerts > w[0].alerts);
            }
            let ap = average_precision(&v);
            prop_assert!((0.0..=1.0 + 1e-9).contains(&ap));
            // The F1 at the chosen threshold equals the F1 of an explicit confusion matrix.
            if let Some(b) = best_f1(&v) {
                let mut c = Confusion::default();
                for (s, y) in &v { c.add(*s >= b.threshold, *y); }
                prop_assert!((c.f1() - b.f1).abs() < 1e-12);
            }
        }
    }
}
