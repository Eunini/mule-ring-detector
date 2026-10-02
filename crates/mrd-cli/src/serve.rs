//! Minimal synchronous HTTP ingest API.
//!
//! `POST /v1/transactions` takes one transfer (or an array) as JSON, runs it through the engine
//! and returns the score, fired detectors and, when the score crosses the threshold, the full
//! alert (which is also forwarded to the case service if `--push` is configured).
//! `GET /health` and `GET /v1/stats` expose liveness and engine state size.

use crate::AlertSink;
use anyhow::Result;
use mrd_core::types::{parse_ts, PaymentFormat, Txn};
use mrd_core::Engine;
use serde::{Deserialize, Serialize};
use std::io::Read;
use std::time::Instant;
use tiny_http::{Header, Method, Response, Server};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IngestTxn {
    pub id: u64,
    pub timestamp: String,
    pub from_bank: String,
    pub from_account: String,
    pub to_bank: String,
    pub to_account: String,
    pub amount: f64,
    pub currency: String,
    pub received_amount: Option<f64>,
    pub receiving_currency: Option<String>,
    pub payment_format: String,
}

impl IngestTxn {
    pub fn into_txn(self) -> Result<Txn, String> {
        let ts = parse_ts(&self.timestamp)
            .ok_or_else(|| format!("bad timestamp {:?}", self.timestamp))?;
        if !(self.amount.is_finite() && self.amount >= 0.0) {
            return Err("amount must be a non-negative number".into());
        }
        Ok(Txn {
            id: self.id,
            ts,
            from_bank: self.from_bank,
            from_account: self.from_account,
            to_bank: self.to_bank,
            to_account: self.to_account,
            amount_paid: self.amount,
            amount_received: self.received_amount.unwrap_or(self.amount),
            receiving_currency: self
                .receiving_currency
                .unwrap_or_else(|| self.currency.clone()),
            payment_currency: self.currency,
            format: PaymentFormat::parse(&self.payment_format),
            label: None,
        })
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IngestResult {
    pub tx_id: u64,
    pub score: f64,
    pub threshold: f64,
    pub alert: bool,
    pub detectors: Vec<String>,
    pub latency_us: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub alert_payload: Option<mrd_core::alert::Alert>,
}

pub fn handle(engine: &mut Engine, sink: &mut AlertSink, t: Txn) -> Result<IngestResult> {
    let t0 = Instant::now();
    let s = engine.process(&t);
    let payload = if s.alert {
        Some(engine.build_alert(&t, &s))
    } else {
        None
    };
    let latency_us = t0.elapsed().as_secs_f64() * 1e6;
    if let Some(a) = &payload {
        sink.send(a.clone())?;
        sink.flush()?;
    }
    Ok(IngestResult {
        tx_id: t.id,
        score: s.score.unwrap_or(0.0),
        threshold: engine.threshold(),
        alert: s.alert,
        detectors: s.hits.iter().map(|h| h.kind.as_str().to_string()).collect(),
        latency_us,
        alert_payload: payload,
    })
}

fn json_response(code: u16, body: String) -> Response<std::io::Cursor<Vec<u8>>> {
    let h =
        Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).expect("static header");
    Response::from_string(body)
        .with_status_code(code)
        .with_header(h)
}

pub fn run(listen: &str, mut engine: Engine, mut sink: AlertSink) -> Result<()> {
    let server = Server::http(listen).map_err(|e| anyhow::anyhow!("bind {listen}: {e}"))?;
    eprintln!("mrd ingest API listening on http://{listen}");
    for mut req in server.incoming_requests() {
        let resp = match (req.method(), req.url()) {
            (Method::Get, "/health") => json_response(200, r#"{"status":"UP"}"#.into()),
            (Method::Get, "/v1/stats") => {
                let st = engine.stats();
                json_response(
                    200,
                    serde_json::json!({"processed": engine.processed(), "graph": st, "threshold": engine.threshold()})
                        .to_string(),
                )
            }
            (Method::Post, "/v1/transactions") => {
                let mut body = String::new();
                if req
                    .as_reader()
                    .take(1 << 20)
                    .read_to_string(&mut body)
                    .is_err()
                {
                    json_response(400, r#"{"error":"unreadable body"}"#.into())
                } else {
                    let parsed: Result<Vec<IngestTxn>, _> = if body.trim_start().starts_with('[') {
                        serde_json::from_str(&body)
                    } else {
                        serde_json::from_str::<IngestTxn>(&body).map(|t| vec![t])
                    };
                    match parsed {
                        Err(e) => json_response(
                            400,
                            serde_json::json!({"error": e.to_string()}).to_string(),
                        ),
                        Ok(items) => {
                            let mut out = Vec::new();
                            let mut err = None;
                            for it in items {
                                match it.into_txn() {
                                    Ok(t) => match handle(&mut engine, &mut sink, t) {
                                        Ok(r) => out.push(r),
                                        Err(e) => err = Some((502, e.to_string())),
                                    },
                                    Err(e) => err = Some((400, e)),
                                }
                                if err.is_some() {
                                    break;
                                }
                            }
                            match err {
                                Some((c, e)) => {
                                    json_response(c, serde_json::json!({"error": e}).to_string())
                                }
                                None => json_response(200, serde_json::to_string(&out)?),
                            }
                        }
                    }
                }
            }
            _ => json_response(404, r#"{"error":"not found"}"#.into()),
        };
        let _ = req.respond(resp);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ingest_payload_maps_to_txn() {
        let j = r#"{"id":7,"timestamp":"2022-09-09T10:20:00Z","fromBank":"001","fromAccount":"A",
            "toBank":"002","toAccount":"B","amount":12.5,"currency":"Euro","paymentFormat":"ACH"}"#;
        let t: IngestTxn = serde_json::from_str(j).unwrap();
        let t = t.into_txn().unwrap();
        assert_eq!(t.format, PaymentFormat::Ach);
        assert_eq!(t.receiving_currency, "Euro");
        assert_eq!(t.amount_received, 12.5);
        let bad: IngestTxn =
            serde_json::from_str(&j.replace("2022-09-09T10:20:00Z", "nope")).unwrap();
        assert!(bad.into_txn().is_err());
    }
}
