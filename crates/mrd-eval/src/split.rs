//! Time-based train / validation / test split.

use mrd_core::types::{format_ts, Minutes};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Part {
    Train,
    Val,
    Test,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Splits {
    /// First minute of validation.
    pub train_end: Minutes,
    /// First minute of test.
    pub val_end: Minutes,
}

impl Splits {
    pub fn of(&self, ts: Minutes) -> Part {
        if ts < self.train_end {
            Part::Train
        } else if ts < self.val_end {
            Part::Val
        } else {
            Part::Test
        }
    }

    /// Cut at the given fractions of transactions (by time), snapped up to the next midnight
    /// so that the boundaries are whole days.
    pub fn from_fractions(sorted_ts: &[Minutes], train: f64, val: f64) -> Splits {
        assert!(!sorted_ts.is_empty(), "no timestamps");
        let at = |f: f64| {
            let i = ((sorted_ts.len() as f64 * f) as usize).min(sorted_ts.len() - 1);
            let t = sorted_ts[i];
            t.div_ceil(1440) * 1440
        };
        let train_end = at(train);
        let val_end = at(val).max(train_end + 1440);
        Splits { train_end, val_end }
    }

    pub fn describe(&self) -> String {
        format!(
            "train < {} <= val < {} <= test",
            format_ts(self.train_end),
            format_ts(self.val_end)
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snaps_to_midnight_and_orders_parts() {
        let ts: Vec<Minutes> = (0..10 * 1440).step_by(10).collect();
        let s = Splits::from_fractions(&ts, 0.6, 0.8);
        assert_eq!(s.train_end % 1440, 0);
        assert_eq!(s.val_end % 1440, 0);
        assert!(s.train_end < s.val_end);
        assert_eq!(s.of(0), Part::Train);
        assert_eq!(s.of(s.train_end), Part::Val);
        assert_eq!(s.of(s.val_end), Part::Test);
    }
}
