//! Parser for the AMLworld `*_Patterns.txt` files (laundering attempts grouped by typology).

use anyhow::{Context, Result};
use rustc_hash::FxHashMap;
use std::io::BufRead;

#[derive(Debug, Clone, PartialEq)]
pub struct Pattern {
    pub index: usize,
    pub typology: String,
    /// Raw row keys (first 10 CSV fields joined with ',').
    pub keys: Vec<String>,
}

/// Matching key of a transaction row: the first ten raw fields (everything but the label).
pub fn row_key(fields: &[&str]) -> String {
    fields[..10].join(",")
}

pub fn parse_patterns<R: BufRead>(r: R) -> Result<Vec<Pattern>> {
    let mut out = Vec::new();
    let mut cur: Option<Pattern> = None;
    for (n, line) in r.lines().enumerate() {
        let line = line.with_context(|| format!("line {n}"))?;
        let line = line.trim();
        if let Some(rest) = line.strip_prefix("BEGIN LAUNDERING ATTEMPT - ") {
            let typ = rest.split(':').next().unwrap_or(rest).trim().to_string();
            cur = Some(Pattern {
                index: out.len(),
                typology: typ,
                keys: Vec::new(),
            });
        } else if line.starts_with("END LAUNDERING ATTEMPT") {
            if let Some(p) = cur.take() {
                out.push(p);
            }
        } else if !line.is_empty() {
            if let Some(p) = cur.as_mut() {
                let fields: Vec<&str> = line.split(',').collect();
                if fields.len() >= 10 {
                    p.keys.push(row_key(&fields));
                }
            }
        }
    }
    Ok(out)
}

/// key -> list of pattern indices containing it.
pub fn key_index(patterns: &[Pattern]) -> FxHashMap<String, Vec<usize>> {
    let mut m: FxHashMap<String, Vec<usize>> = FxHashMap::default();
    for p in patterns {
        for k in &p.keys {
            m.entry(k.clone()).or_default().push(p.index);
        }
    }
    m
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_blocks() {
        let s = "BEGIN LAUNDERING ATTEMPT - FAN-OUT:  Max 16-degree Fan-Out\n\
2022/09/01 00:06,021174,800737690,012,80011F990,2848.96,Euro,2848.96,Euro,ACH,1\n\
END LAUNDERING ATTEMPT - FAN-OUT\n\n\
BEGIN LAUNDERING ATTEMPT - CYCLE:  Max 10 hops\n\
2022/09/01 00:03,01467,8013C4030,020,80BC62F10,58702.10,Yuan,58702.10,Yuan,ACH,1\n\
2022/09/01 02:52,020,80BC62F10,0240229,80F025640,7332.87,Swiss Franc,7332.87,Swiss Franc,ACH,1\n\
END LAUNDERING ATTEMPT - CYCLE\n";
        let p = parse_patterns(s.as_bytes()).unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(p[0].typology, "FAN-OUT");
        assert_eq!(p[1].typology, "CYCLE");
        assert_eq!(p[1].keys.len(), 2);
        assert_eq!(
            p[0].keys[0],
            "2022/09/01 00:06,021174,800737690,012,80011F990,2848.96,Euro,2848.96,Euro,ACH"
        );
        assert_eq!(key_index(&p).len(), 3);
    }
}
