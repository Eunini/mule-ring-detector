//! Synthetic transfer generator modelled on IBM AMLSim / AMLworld laundering typologies.
//!
//! Used for the CI fixture and tests only; reported results come from the IBM HI-Small file.
//! Background traffic has regular counterparties, merchants (legitimate fan-in hubs) and
//! payroll accounts (legitimate fan-out), so that structural detectors alone are not enough.

use mrd_core::io::ibm_fields;
use mrd_core::types::{parse_ts, Minutes, PaymentFormat, Txn};
use std::io::Write;

#[derive(Debug, Clone)]
pub struct SynthConfig {
    pub accounts: usize,
    pub banks: usize,
    pub days: u32,
    pub txns_per_account_day: f64,
    pub patterns_per_typology: usize,
    pub seed: u64,
}

impl Default for SynthConfig {
    fn default() -> Self {
        Self {
            accounts: 650,
            banks: 20,
            days: 12,
            txns_per_account_day: 0.4,
            patterns_per_typology: 6,
            seed: 42,
        }
    }
}

pub const TYPOLOGIES: [&str; 8] = [
    "FAN-OUT",
    "FAN-IN",
    "CYCLE",
    "SCATTER-GATHER",
    "GATHER-SCATTER",
    "BIPARTITE",
    "STACK",
    "RANDOM",
];

pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Self {
        Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1)
    }
    pub fn next_u64(&mut self) -> u64 {
        // xorshift64*
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    pub fn f64(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }
    pub fn below(&mut self, n: usize) -> usize {
        (self.f64() * n as f64) as usize % n.max(1)
    }
    pub fn range(&mut self, lo: usize, hi: usize) -> usize {
        lo + self.below(hi - lo + 1)
    }
    pub fn normal(&mut self) -> f64 {
        let (u1, u2) = (self.f64().max(1e-12), self.f64());
        (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
    }
    pub fn lognormal(&mut self, median: f64, sigma: f64) -> f64 {
        median * (sigma * self.normal()).exp()
    }
    pub fn poisson(&mut self, lambda: f64) -> usize {
        let l = (-lambda).exp();
        let (mut k, mut p) = (0, 1.0);
        loop {
            p *= self.f64();
            if p <= l {
                return k;
            }
            k += 1;
        }
    }
}

#[derive(Clone)]
struct Acct {
    bank: String,
    id: String,
    currency: &'static str,
    rate: f64,
    peers: Vec<usize>,
}

const CURRENCIES: [(&str, f64); 5] = [
    ("US Dollar", 0.55),
    ("Euro", 0.25),
    ("UK Pound", 0.08),
    ("Yuan", 0.07),
    ("Rupee", 0.05),
];

pub struct SynthOutput {
    pub txns: Vec<Txn>,
    /// (typology, transactions) per laundering attempt.
    pub patterns: Vec<(String, Vec<Txn>)>,
}

pub fn generate(cfg: &SynthConfig) -> SynthOutput {
    let mut rng = Rng::new(cfg.seed);
    let start: Minutes = parse_ts("2022/09/01 00:00").expect("valid");
    let mut accts: Vec<Acct> = (0..cfg.accounts)
        .map(|i| {
            let mut c = rng.f64();
            let mut currency = CURRENCIES[0].0;
            for (name, p) in CURRENCIES {
                if c < p {
                    currency = name;
                    break;
                }
                c -= p;
            }
            Acct {
                bank: format!("{:03}", 1 + rng.below(cfg.banks)),
                id: format!("{:09X}", 0x8000_0000_u64 + (i as u64) * 7919),
                currency,
                rate: rng.lognormal(cfg.txns_per_account_day, 0.8),
                peers: Vec::new(),
            }
        })
        .collect();
    let n = accts.len();
    let merchants: Vec<usize> = (0..n.div_ceil(50)).map(|_| rng.below(n)).collect();
    let payroll: Vec<usize> = (0..n.div_ceil(100)).map(|_| rng.below(n)).collect();
    for (i, acct) in accts.iter_mut().enumerate() {
        let k = rng.range(2, 6);
        for _ in 0..k {
            let p = if rng.f64() < 0.4 {
                merchants[rng.below(merchants.len())]
            } else {
                rng.below(n)
            };
            if p != i {
                acct.peers.push(p);
            }
        }
    }
    let fmt_pick = |r: &mut Rng| -> PaymentFormat {
        let x = r.f64();
        match x {
            _ if x < 0.34 => PaymentFormat::Cheque,
            _ if x < 0.58 => PaymentFormat::CreditCard,
            _ if x < 0.69 => PaymentFormat::Ach,
            _ if x < 0.79 => PaymentFormat::Cash,
            _ if x < 0.88 => PaymentFormat::Reinvestment,
            _ if x < 0.95 => PaymentFormat::Wire,
            _ => PaymentFormat::Bitcoin,
        }
    };
    let mk = |id: &mut u64,
              ts: Minutes,
              a: &Acct,
              b: &Acct,
              amount: f64,
              format: PaymentFormat,
              label: bool| {
        let cur = if format == PaymentFormat::Bitcoin {
            "Bitcoin"
        } else {
            a.currency
        };
        let amt = if format == PaymentFormat::Bitcoin {
            amount / 11881.6
        } else {
            amount
        };
        let t = Txn {
            id: *id,
            ts,
            from_bank: a.bank.clone(),
            from_account: a.id.clone(),
            to_bank: b.bank.clone(),
            to_account: b.id.clone(),
            amount_paid: (amt * 100.0).round() / 100.0,
            payment_currency: cur.to_string(),
            amount_received: (amt * 100.0).round() / 100.0,
            receiving_currency: cur.to_string(),
            format,
            label: Some(label),
        };
        *id += 1;
        t
    };
    let mut id = 0u64;
    let mut txns = Vec::new();
    for day in 0..cfg.days {
        for i in 0..n {
            let k = rng.poisson(accts[i].rate);
            for _ in 0..k {
                let ts = start + day * 1440 + rng.below(1440) as Minutes;
                let format = fmt_pick(&mut rng);
                let j = if format == PaymentFormat::Reinvestment {
                    i
                } else if !accts[i].peers.is_empty() && rng.f64() < 0.8 {
                    accts[i].peers[rng.below(accts[i].peers.len())]
                } else {
                    rng.below(n)
                };
                let amount = rng.lognormal(250.0, 1.3);
                txns.push(mk(&mut id, ts, &accts[i], &accts[j], amount, format, false));
            }
        }
        if day % 7 == 3 {
            for &p in &payroll {
                let staff = rng.range(8, 20);
                let ts = start + day * 1440 + 9 * 60;
                for _ in 0..staff {
                    let j = rng.below(n);
                    if j != p {
                        let amt = rng.lognormal(3000.0, 0.3);
                        txns.push(mk(
                            &mut id,
                            ts + rng.below(30) as Minutes,
                            &accts[p],
                            &accts[j],
                            amt,
                            PaymentFormat::Ach,
                            false,
                        ));
                    }
                }
            }
        }
    }

    // Laundering attempts.
    let mut patterns = Vec::new();
    let mut fresh = 0usize;
    for rep in 0..cfg.patterns_per_typology {
        for typ in TYPOLOGIES {
            let span_days = cfg.days.saturating_sub(2).max(1);
            let t0 = start
                + rng.below(span_days as usize) as Minutes * 1440
                + rng.below(1440) as Minutes;
            let mut pick = |rng: &mut Rng, accts: &mut Vec<Acct>| -> usize {
                if rng.f64() < 0.5 {
                    rng.below(n)
                } else {
                    fresh += 1;
                    let a = Acct {
                        bank: format!("{:03}", 1 + rng.below(cfg.banks)),
                        id: format!(
                            "{:09X}",
                            0x9000_0000_u64 + fresh as u64 * 104_729 + rep as u64
                        ),
                        currency: "US Dollar",
                        rate: 0.0,
                        peers: Vec::new(),
                    };
                    accts.push(a);
                    accts.len() - 1
                }
            };
            let mut edges: Vec<(usize, usize, f64)> = Vec::new();
            let base = rng.lognormal(6000.0, 0.8);
            match typ {
                "FAN-OUT" => {
                    let s = pick(&mut rng, &mut accts);
                    for _ in 0..rng.range(4, 12) {
                        let d = pick(&mut rng, &mut accts);
                        edges.push((s, d, base * rng.f64().max(0.2)));
                    }
                }
                "FAN-IN" => {
                    let d = pick(&mut rng, &mut accts);
                    for _ in 0..rng.range(4, 12) {
                        let s = pick(&mut rng, &mut accts);
                        edges.push((s, d, base * rng.f64().max(0.2)));
                    }
                }
                "CYCLE" => {
                    let k = rng.range(3, 7);
                    let nodes: Vec<usize> = (0..k).map(|_| pick(&mut rng, &mut accts)).collect();
                    let mut a = base;
                    for i in 0..k {
                        edges.push((nodes[i], nodes[(i + 1) % k], a));
                        a *= 0.95 + 0.04 * rng.f64();
                    }
                }
                "SCATTER-GATHER" => {
                    let s = pick(&mut rng, &mut accts);
                    let d = pick(&mut rng, &mut accts);
                    for _ in 0..rng.range(3, 8) {
                        let m = pick(&mut rng, &mut accts);
                        let a = base * rng.f64().max(0.2);
                        edges.push((s, m, a));
                        edges.push((m, d, a * 0.95));
                    }
                }
                "GATHER-SCATTER" => {
                    let hub = pick(&mut rng, &mut accts);
                    for _ in 0..rng.range(3, 8) {
                        let s = pick(&mut rng, &mut accts);
                        edges.push((s, hub, base * rng.f64().max(0.2)));
                    }
                    for _ in 0..rng.range(3, 8) {
                        let d = pick(&mut rng, &mut accts);
                        edges.push((hub, d, base * rng.f64().max(0.2)));
                    }
                }
                "BIPARTITE" | "STACK" => {
                    let layers = if typ == "STACK" { 3 } else { 2 };
                    let groups: Vec<Vec<usize>> = (0..layers)
                        .map(|_| {
                            (0..rng.range(2, 4))
                                .map(|_| pick(&mut rng, &mut accts))
                                .collect()
                        })
                        .collect();
                    for w in groups.windows(2) {
                        for &a in &w[0] {
                            for &b in &w[1] {
                                if rng.f64() < 0.8 {
                                    edges.push((a, b, base * rng.f64().max(0.2)));
                                }
                            }
                        }
                    }
                }
                _ => {
                    let mut cur = pick(&mut rng, &mut accts);
                    let mut a = base;
                    for _ in 0..rng.range(3, 7) {
                        let nxt = pick(&mut rng, &mut accts);
                        edges.push((cur, nxt, a));
                        a *= 0.93 + 0.06 * rng.f64();
                        cur = nxt;
                    }
                }
            }
            let span = 1440 * rng.range(1, 4) as Minutes;
            let mut ptx = Vec::new();
            let m = edges.len().max(1) as Minutes;
            for (k, (a, b, amt)) in edges.into_iter().enumerate() {
                if a == b {
                    continue;
                }
                let ts = t0 + (k as Minutes) * span / m + rng.below(30) as Minutes;
                let format = if rng.f64() < 0.85 {
                    PaymentFormat::Ach
                } else {
                    fmt_pick(&mut rng)
                };
                let format = if format == PaymentFormat::Reinvestment {
                    PaymentFormat::Ach
                } else {
                    format
                };
                let t = mk(&mut id, ts, &accts[a], &accts[b], amt, format, true);
                txns.push(t.clone());
                ptx.push(t);
            }
            patterns.push((typ.to_string(), ptx));
        }
    }
    // Shuffle-free but unsorted like the original files: laundering rows are appended last.
    SynthOutput { txns, patterns }
}

/// Write the patterns file in the AMLworld layout.
pub fn write_patterns<W: Write>(mut w: W, patterns: &[(String, Vec<Txn>)]) -> std::io::Result<()> {
    for (typ, txs) in patterns {
        writeln!(w, "BEGIN LAUNDERING ATTEMPT - {typ}:  synthetic")?;
        for t in txs {
            writeln!(w, "{}", ibm_fields(t).join(","))?;
        }
        writeln!(w, "END LAUNDERING ATTEMPT - {typ}")?;
        writeln!(w)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministic_and_labelled() {
        let cfg = SynthConfig {
            accounts: 200,
            days: 6,
            patterns_per_typology: 1,
            ..SynthConfig::default()
        };
        let a = generate(&cfg);
        let b = generate(&cfg);
        assert_eq!(a.txns.len(), b.txns.len());
        assert_eq!(a.patterns.len(), TYPOLOGIES.len());
        let pos = a.txns.iter().filter(|t| t.label == Some(true)).count();
        let in_patterns: usize = a.patterns.iter().map(|p| p.1.len()).sum();
        assert_eq!(pos, in_patterns);
        assert!(pos > 20 && pos < a.txns.len() / 5);
        let mut buf = Vec::new();
        write_patterns(&mut buf, &a.patterns).unwrap();
        let parsed = crate::patterns::parse_patterns(buf.as_slice()).unwrap();
        assert_eq!(parsed.len(), a.patterns.len());
    }
}
