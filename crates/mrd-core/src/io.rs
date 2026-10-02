//! Reading and writing transfers in the IBM AMLworld CSV layout.
//!
//! Columns: `Timestamp,From Bank,Account,To Bank,Account,Amount Received,Receiving Currency,
//! Amount Paid,Payment Currency,Payment Format,Is Laundering` and, for time-sorted replay files
//! produced by `mrd prepare`, a trailing `Row Id` column holding the original row index.

use crate::types::{parse_ts, PaymentFormat, Txn};
use anyhow::{anyhow, Context, Result};
use std::io::{Read, Write};

pub const IBM_HEADER: [&str; 11] = [
    "Timestamp",
    "From Bank",
    "Account",
    "To Bank",
    "Account",
    "Amount Received",
    "Receiving Currency",
    "Amount Paid",
    "Payment Currency",
    "Payment Format",
    "Is Laundering",
];

/// Streaming reader over an IBM-format CSV (optionally with a `Row Id` column).
pub struct TxnReader<R: Read> {
    rdr: csv::Reader<R>,
    rec: csv::StringRecord,
    row: u64,
}

impl TxnReader<std::fs::File> {
    pub fn open(path: &std::path::Path) -> Result<Self> {
        let f = std::fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
        Ok(Self::new(f))
    }
}

impl<R: Read> TxnReader<R> {
    pub fn new(r: R) -> Self {
        let rdr = csv::ReaderBuilder::new()
            .has_headers(true)
            .buffer_capacity(1 << 20)
            .from_reader(r);
        Self {
            rdr,
            rec: csv::StringRecord::new(),
            row: 0,
        }
    }

    /// Read the next transfer; `None` at end of input.
    pub fn next_txn(&mut self) -> Option<Result<Txn>> {
        match self.rdr.read_record(&mut self.rec) {
            Ok(true) => {
                let r = parse_record(&self.rec, self.row);
                self.row += 1;
                Some(r)
            }
            Ok(false) => None,
            Err(e) => Some(Err(e.into())),
        }
    }
}

impl<R: Read> Iterator for TxnReader<R> {
    type Item = Result<Txn>;
    fn next(&mut self) -> Option<Self::Item> {
        self.next_txn()
    }
}

pub fn parse_record(rec: &csv::StringRecord, row: u64) -> Result<Txn> {
    if rec.len() < 10 {
        return Err(anyhow!(
            "row {row}: expected >=10 columns, got {}",
            rec.len()
        ));
    }
    let ts = parse_ts(&rec[0]).ok_or_else(|| anyhow!("row {row}: bad timestamp {:?}", &rec[0]))?;
    let num = |i: usize| -> Result<f64> {
        rec[i]
            .parse::<f64>()
            .with_context(|| format!("row {row}: bad amount {:?}", &rec[i]))
    };
    let label = match rec.get(10) {
        Some("1") => Some(true),
        Some("0") => Some(false),
        _ => None,
    };
    let id = match rec.get(11) {
        Some(s) if !s.is_empty() => s
            .parse::<u64>()
            .with_context(|| format!("row {row}: bad row id"))?,
        _ => row,
    };
    Ok(Txn {
        id,
        ts,
        from_bank: rec[1].to_string(),
        from_account: rec[2].to_string(),
        to_bank: rec[3].to_string(),
        to_account: rec[4].to_string(),
        amount_received: num(5)?,
        receiving_currency: rec[6].to_string(),
        amount_paid: num(7)?,
        payment_currency: rec[8].to_string(),
        format: PaymentFormat::parse(&rec[9]),
        label,
    })
}

/// Sort a raw IBM file by (timestamp, original row) and write it with a `Row Id` column.
/// Raw string fields are preserved byte-for-byte so pattern-file matching stays exact.
pub fn prepare_sorted(input: &std::path::Path, output: &std::path::Path) -> Result<usize> {
    let mut rdr = csv::ReaderBuilder::new()
        .has_headers(true)
        .from_path(input)
        .with_context(|| format!("open {}", input.display()))?;
    let mut rows: Vec<(u32, u64, csv::ByteRecord)> = Vec::new();
    let mut rec = csv::ByteRecord::new();
    let mut i = 0u64;
    while rdr.read_byte_record(&mut rec)? {
        let ts = std::str::from_utf8(&rec[0])
            .ok()
            .and_then(parse_ts)
            .ok_or_else(|| anyhow!("row {i}: bad timestamp"))?;
        rows.push((ts, i, rec.clone()));
        i += 1;
    }
    rows.sort_by_key(|r| (r.0, r.1));
    let f = std::fs::File::create(output)?;
    let mut w =
        csv::WriterBuilder::new().from_writer(std::io::BufWriter::with_capacity(1 << 20, f));
    let mut header: Vec<&str> = IBM_HEADER.to_vec();
    header.push("Row Id");
    w.write_record(&header)?;
    for (_, id, r) in &rows {
        let mut out = r.clone();
        out.push_field(id.to_string().as_bytes());
        w.write_byte_record(&out)?;
    }
    w.flush()?;
    Ok(rows.len())
}

/// Write transfers in IBM layout (used by the synthetic generator).
pub fn write_ibm<W: Write>(w: W, txns: &[Txn]) -> Result<()> {
    let mut w = csv::Writer::from_writer(w);
    w.write_record(IBM_HEADER)?;
    for t in txns {
        w.write_record(ibm_fields(t))?;
    }
    w.flush()?;
    Ok(())
}

/// The 11 IBM string fields of a transfer, formatted as in the source data.
pub fn ibm_fields(t: &Txn) -> [String; 11] {
    [
        ibm_ts(t.ts),
        t.from_bank.clone(),
        t.from_account.clone(),
        t.to_bank.clone(),
        t.to_account.clone(),
        format!("{:.2}", t.amount_received),
        t.receiving_currency.clone(),
        format!("{:.2}", t.amount_paid),
        t.payment_currency.clone(),
        t.format.as_str().to_string(),
        if t.label == Some(true) { "1" } else { "0" }.to_string(),
    ]
}

/// `YYYY/MM/DD HH:MM` as used by the IBM files.
pub fn ibm_ts(m: crate::types::Minutes) -> String {
    let iso = crate::types::format_ts(m);
    format!(
        "{}/{}/{} {}",
        &iso[0..4],
        &iso[5..7],
        &iso[8..10],
        &iso[11..16]
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "Timestamp,From Bank,Account,To Bank,Account,Amount Received,Receiving Currency,Amount Paid,Payment Currency,Payment Format,Is Laundering\n\
2022/09/01 00:20,010,8000EBD30,010,8000EBD30,3697.34,US Dollar,3697.34,US Dollar,Reinvestment,0\n\
2022/09/01 00:06,021174,800737690,012,80011F990,2848.96,Euro,2848.96,Euro,ACH,1\n";

    #[test]
    fn reads_ibm_rows() {
        let rows: Vec<Txn> = TxnReader::new(SAMPLE.as_bytes())
            .map(|r| r.unwrap())
            .collect();
        assert_eq!(rows.len(), 2);
        assert!(rows[0].is_self_transfer());
        assert_eq!(rows[1].label, Some(true));
        assert_eq!(rows[1].format, PaymentFormat::Ach);
        assert_eq!(rows[1].id, 1);
        assert_eq!(ibm_ts(rows[1].ts), "2022/09/01 00:06");
        assert!((rows[1].amount_usd() - 2848.96 * 1.1718).abs() < 1e-6);
    }

    #[test]
    fn prepare_sorts_and_keeps_row_ids() {
        let dir = std::env::temp_dir().join(format!("mrd-io-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let (a, b) = (dir.join("raw.csv"), dir.join("sorted.csv"));
        std::fs::write(&a, SAMPLE).unwrap();
        assert_eq!(prepare_sorted(&a, &b).unwrap(), 2);
        let rows: Vec<Txn> = TxnReader::open(&b).unwrap().map(|r| r.unwrap()).collect();
        assert_eq!(rows[0].id, 1);
        assert_eq!(rows[1].id, 0);
        assert!(rows[0].ts <= rows[1].ts);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
