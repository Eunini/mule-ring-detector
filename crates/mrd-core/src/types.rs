//! Transaction record types, timestamp parsing and currency normalisation.

use serde::{Deserialize, Serialize};

/// Event time in whole minutes since the Unix epoch. The IBM AML data has minute resolution.
pub type Minutes = u32;

/// Payment rail of a transfer, as encoded in the IBM AMLworld data.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum PaymentFormat {
    Ach,
    Cash,
    Cheque,
    CreditCard,
    Bitcoin,
    Wire,
    Reinvestment,
    Other,
}

impl PaymentFormat {
    pub const ALL: [PaymentFormat; 7] = [
        PaymentFormat::Ach,
        PaymentFormat::Cash,
        PaymentFormat::Cheque,
        PaymentFormat::CreditCard,
        PaymentFormat::Bitcoin,
        PaymentFormat::Wire,
        PaymentFormat::Reinvestment,
    ];

    pub fn parse(s: &str) -> Self {
        match s {
            "ACH" => Self::Ach,
            "Cash" => Self::Cash,
            "Cheque" => Self::Cheque,
            "Credit Card" => Self::CreditCard,
            "Bitcoin" => Self::Bitcoin,
            "Wire" => Self::Wire,
            "Reinvestment" => Self::Reinvestment,
            _ => Self::Other,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Ach => "ACH",
            Self::Cash => "Cash",
            Self::Cheque => "Cheque",
            Self::CreditCard => "Credit Card",
            Self::Bitcoin => "Bitcoin",
            Self::Wire => "Wire",
            Self::Reinvestment => "Reinvestment",
            Self::Other => "Other",
        }
    }
}

/// One transfer as it enters the engine.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Txn {
    /// Stable identifier of the transfer (row index in the original file for replays).
    pub id: u64,
    pub ts: Minutes,
    pub from_bank: String,
    pub from_account: String,
    pub to_bank: String,
    pub to_account: String,
    pub amount_paid: f64,
    pub payment_currency: String,
    pub amount_received: f64,
    pub receiving_currency: String,
    pub format: PaymentFormat,
    /// Ground-truth label when known (replay/evaluation only; never read by detectors).
    #[serde(default)]
    pub label: Option<bool>,
}

impl Txn {
    pub fn from_key(&self) -> String {
        account_key(&self.from_bank, &self.from_account)
    }
    pub fn to_key(&self) -> String {
        account_key(&self.to_bank, &self.to_account)
    }
    /// Amount converted to US dollars using the paid side.
    pub fn amount_usd(&self) -> f64 {
        self.amount_paid * usd_rate(&self.payment_currency)
    }
    pub fn is_self_transfer(&self) -> bool {
        self.from_bank == self.to_bank && self.from_account == self.to_account
    }
}

/// Accounts are identified by (bank, account) as in the source data.
pub fn account_key(bank: &str, account: &str) -> String {
    let mut s = String::with_capacity(bank.len() + account.len() + 1);
    s.push_str(bank);
    s.push(':');
    s.push_str(account);
    s
}

/// US dollars per unit of currency. Rates are the medians of `Amount Received / Amount Paid`
/// over the cross-currency rows of the IBM HI-Small file (label-free, derived once and frozen).
pub fn usd_rate(currency: &str) -> f64 {
    match currency {
        "US Dollar" => 1.0,
        "Euro" => 1.1718,
        "UK Pound" => 1.2917,
        "Swiss Franc" => 1.0929,
        "Yuan" => 0.14931,
        "Shekel" => 0.29612,
        "Rupee" => 0.013616,
        "Ruble" => 0.012853,
        "Yen" => 0.0094877,
        "Bitcoin" => 11881.6,
        "Canadian Dollar" => 0.75798,
        "Australian Dollar" => 0.70781,
        "Mexican Peso" => 0.047297,
        "Saudi Riyal" => 0.26659,
        "Brazil Real" => 0.17710,
        _ => 1.0,
    }
}

/// Parse `YYYY/MM/DD HH:MM` (IBM format) or `YYYY-MM-DDTHH:MM[:SS][Z]` into minutes since epoch.
pub fn parse_ts(s: &str) -> Option<Minutes> {
    let b = s.as_bytes();
    if b.len() < 16 {
        return None;
    }
    let num = |r: std::ops::Range<usize>| -> Option<i64> {
        let mut v = 0i64;
        for &c in &b[r] {
            if !c.is_ascii_digit() {
                return None;
            }
            v = v * 10 + i64::from(c - b'0');
        }
        Some(v)
    };
    let (y, mo, d, h, mi) = (
        num(0..4)?,
        num(5..7)?,
        num(8..10)?,
        num(11..13)?,
        num(14..16)?,
    );
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 {
        return None;
    }
    let days = days_from_civil(y, mo, d);
    let m = days * 1440 + h * 60 + mi;
    u32::try_from(m).ok()
}

/// Format minutes since epoch as RFC 3339 UTC.
pub fn format_ts(m: Minutes) -> String {
    let m = i64::from(m);
    let (days, rem) = (m.div_euclid(1440), m.rem_euclid(1440));
    let (y, mo, d) = civil_from_days(days);
    format!("{y:04}-{mo:02}-{d:02}T{:02}:{:02}:00Z", rem / 60, rem % 60)
}

// Howard Hinnant's civil calendar algorithms.
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn parses_ibm_timestamp() {
        let m = parse_ts("2022/09/01 00:20").unwrap();
        assert_eq!(format_ts(m), "2022-09-01T00:20:00Z");
        assert_eq!(parse_ts("2022-09-01T00:20:00Z"), Some(m));
        assert!(parse_ts("2022/13/01 00:20").is_none());
        assert!(parse_ts("garbage").is_none());
    }

    #[test]
    fn fx_is_sane() {
        assert!((usd_rate("Euro") - 1.17).abs() < 0.01);
        assert_eq!(
            PaymentFormat::parse("Credit Card"),
            PaymentFormat::CreditCard
        );
        assert_eq!(PaymentFormat::CreditCard.as_str(), "Credit Card");
    }

    proptest! {
        #[test]
        fn ts_roundtrip(days in 0i64..40_000, minute in 0i64..1440) {
            let m = u32::try_from(days * 1440 + minute).unwrap();
            let s = format_ts(m);
            prop_assert_eq!(parse_ts(&s), Some(m));
        }
    }
}
