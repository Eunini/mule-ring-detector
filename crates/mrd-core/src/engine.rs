//! The streaming engine: graph update, detectors, feature extraction, scoring and alerting for
//! one transfer at a time.

use crate::alert::{Alert, AlertDetector, EvidenceEdge, FeatureContribution};
use crate::detectors::{self, DetectorConfig, DetectorKind, EdgeCtx, Hit, Scratch};
use crate::features::{ln1p, Features, N_FEATURES};
use crate::graph::{GraphConfig, GraphStats, TemporalGraph};
use crate::model::ModelFile;
use crate::ring::Rings;
use crate::types::{format_ts, PaymentFormat, Txn};
use rustc_hash::FxHashSet;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct EngineConfig {
    pub window_minutes: u32,
    pub max_adj: usize,
    pub detectors: DetectorConfig,
}

impl EngineConfig {
    pub fn standard() -> Self {
        let g = GraphConfig::default();
        Self {
            window_minutes: g.window,
            max_adj: g.max_adj,
            detectors: DetectorConfig::default(),
        }
    }
}

/// Everything the engine computed for one transfer.
#[derive(Debug, Clone)]
pub struct Scored {
    pub txn_id: u64,
    pub features: Features,
    /// Fired detectors (rules) with evidence.
    pub hits: Vec<Hit>,
    pub score: Option<f64>,
    pub alert: bool,
    /// Edge sequence number in the graph (None for self-transfers, which are not graph edges).
    pub seq: Option<u64>,
}

pub struct Engine {
    pub cfg: EngineConfig,
    graph: TemporalGraph,
    rule_rings: Rings,
    alert_rings: Rings,
    scratch: Scratch,
    model: Option<ModelFile>,
    /// First-stage models producing per-edge risk scores for neighbour features.
    stage1: Vec<ModelFile>,
    /// Training mode: score each edge with the stage-1 model *not* trained on its fold.
    crossfit: bool,
    threshold: f64,
    buf: Vec<f32>,
    set: FxHashSet<u32>,
    processed: u64,
}

impl Engine {
    pub fn new(cfg: EngineConfig, model: Option<ModelFile>) -> Self {
        let gcfg = GraphConfig {
            window: cfg.window_minutes,
            max_adj: cfg.max_adj,
            sweep_every: 60,
        };
        let d = &cfg.detectors;
        let threshold = model.as_ref().map_or(1.0, |m| m.threshold);
        let stage1 = model.as_ref().map(|m| m.stage1.clone()).unwrap_or_default();
        Self {
            stage1,
            crossfit: false,
            graph: TemporalGraph::new(gcfg),
            rule_rings: Rings::new(cfg.window_minutes, d.ring_rebuild, d.evidence_cap),
            alert_rings: Rings::new(cfg.window_minutes, d.ring_rebuild, d.evidence_cap),
            scratch: Scratch::default(),
            threshold,
            model,
            buf: Vec::with_capacity(N_FEATURES),
            set: FxHashSet::default(),
            processed: 0,
            cfg,
        }
    }

    /// Use explicit first-stage models (training replays); `crossfit` selects out-of-fold
    /// scoring so an edge's own label never influences its neighbour features.
    pub fn with_stage1(mut self, stage1: Vec<ModelFile>, crossfit: bool) -> Self {
        self.stage1 = stage1;
        self.crossfit = crossfit;
        self
    }

    pub fn set_crossfit(&mut self, on: bool) {
        self.crossfit = on;
    }

    pub fn threshold(&self) -> f64 {
        self.threshold
    }

    pub fn set_threshold(&mut self, t: f64) {
        self.threshold = t;
    }

    pub fn model(&self) -> Option<&ModelFile> {
        self.model.as_ref()
    }

    pub fn graph(&self) -> &TemporalGraph {
        &self.graph
    }

    pub fn stats(&self) -> GraphStats {
        self.graph.stats()
    }

    pub fn processed(&self) -> u64 {
        self.processed
    }

    /// Process one transfer: update state, compute detectors/features, score, and decide.
    pub fn process(&mut self, t: &Txn) -> Scored {
        self.processed += 1;
        let ts = self.graph.advance(t.ts);
        self.rule_rings.maybe_rebuild(ts);
        self.alert_rings.maybe_rebuild(ts);
        let amount_usd = t.amount_usd();
        let mut f: Features = [0.0; N_FEATURES];
        if let Some(i) = PaymentFormat::ALL.iter().position(|p| *p == t.format) {
            f[i] = 1.0;
        }
        f[7] = ln1p(amount_usd);
        f[8] = f32::from(u8::from(t.payment_currency != t.receiving_currency));
        f[9] = f32::from(u8::from(t.from_bank == t.to_bank));
        f[10] = f32::from(u8::from(
            t.amount_paid >= 100.0 && (t.amount_paid % 100.0).abs() < 1e-9,
        ));

        let src = self.graph.intern(&t.from_key(), ts);
        let dst = self.graph.intern(&t.to_key(), ts);
        let mut hits = Vec::new();
        let mut seq = None;
        if src != dst {
            let s = self.graph.insert(src, dst, amount_usd as f32, t.id);
            let is_ach = t.format == PaymentFormat::Ach;
            self.graph.annotate_last(src, dst, |a| a.ach = is_ach);
            seq = Some(s);
            let e = EdgeCtx {
                src,
                dst,
                ts,
                amount_usd,
                seq: s,
            };
            self.graph_features(&e, &mut f, &mut hits);
            if !self.stage1.is_empty() {
                self.neighbour_features(&e, &mut f);
                let s1 = self.stage1_score(&f, &t.from_key()) as f32;
                self.graph.annotate_last(src, dst, |a| a.score = s1);
            }
        }
        let score = self.model.as_ref().map(|m| m.score(&f, &mut self.buf));
        let alert = score.is_some_and(|p| p >= self.threshold);
        if alert {
            if let Some(s) = seq {
                self.alert_rings.add(src, dst, ts, s);
            }
        }
        Scored {
            txn_id: t.id,
            features: f,
            hits,
            score,
            alert,
            seq,
        }
    }

    fn stage1_score(&mut self, f: &Features, src_key: &str) -> f64 {
        if self.crossfit && self.stage1.len() == 2 {
            let other = 1 - fold_of(src_key);
            self.stage1[other].score(f, &mut self.buf)
        } else {
            let n = self.stage1.len() as f64;
            let mut sum = 0.0;
            for m in &self.stage1 {
                sum += m.score(f, &mut self.buf);
            }
            sum / n
        }
    }

    /// Max first-stage score among the live edges around the new edge (excluding itself).
    fn neighbour_features(&self, e: &EdgeCtx, f: &mut Features) {
        let g = &self.graph;
        let max = |it: &mut dyn Iterator<Item = &crate::graph::Adj>| {
            it.filter(|a| a.seq != e.seq)
                .map(|a| a.score)
                .fold(0.0f32, f32::max)
        };
        f[30] = max(&mut g.in_live(e.src));
        f[31] = max(&mut g.out_live(e.src));
        f[32] = max(&mut g.in_live(e.dst)).max(max(&mut g.out_live(e.dst)));
    }

    fn graph_features(&mut self, e: &EdgeCtx, f: &mut Features, hits: &mut Vec<Hit>) {
        let g = &self.graph;
        let cfg = &self.cfg.detectors;
        let sc = &mut self.scratch;
        let (fo, h) = detectors::fan_out(g, e, cfg, sc);
        f[11] = ln1p(fo);
        hits.extend(h);
        let (fi, h) = detectors::fan_in(g, e, cfg, sc);
        f[12] = ln1p(fi);
        hits.extend(h);
        f[13] = ln1p(detectors::distinct_peers(g.in_live(e.src), &mut self.set) as f64);
        f[14] = ln1p(detectors::distinct_peers(g.out_live(e.dst), &mut self.set) as f64);
        let (sg, h) = detectors::scatter_gather(g, e, cfg, sc);
        f[15] = sg as f32;
        hits.extend(h);
        let (gs, h) = detectors::gather_scatter(g, e, cfg, sc);
        f[16] = ln1p(gs);
        hits.extend(h);
        if let Some(c) = detectors::cycle(g, e, cfg, sc) {
            f[17] = c.edges.len() as f32;
            f[18] = c.amount_ratio as f32;
            hits.push(Hit {
                kind: DetectorKind::Cycle,
                value: c.edges.len() as f64,
                detail: format!(
                    "transfer closes a {}-hop cycle; smallest/largest amount = {:.2}",
                    c.edges.len(),
                    c.amount_ratio
                ),
                evidence: c.edges,
            });
        }
        let (pt, h) = detectors::pass_through(g, e, cfg);
        match pt {
            Some((gap, ratio)) => {
                f[19] = ln1p(f64::from(gap));
                f[20] = ratio as f32;
            }
            None => f[19] = ln1p(f64::from(cfg.pass_lookback) + 1.0),
        }
        hits.extend(h);
        let (vc, vr, h) = detectors::velocity(g, e, cfg);
        f[21] = ln1p(vc);
        f[22] = ln1p(vr);
        hits.extend(h);
        let lo = e.ts.saturating_sub(cfg.velocity_window);
        f[23] = ln1p(g.in_live(e.dst).rev().take_while(|a| a.ts >= lo).count() as f64);
        f[24] = ln1p(
            g.out_live(e.src)
                .filter(|a| a.peer == e.dst && a.seq != e.seq)
                .count() as f64,
        );
        let (sa, da) = (g.account(e.src), g.account(e.dst));
        f[26] = ln1p(f64::from(sa.total_in + sa.total_out));
        f[27] = ln1p(f64::from(da.total_in + da.total_out));

        let share = |it: &mut dyn Iterator<Item = &crate::graph::Adj>| {
            let (mut n, mut a) = (0u32, 0u32);
            for x in it {
                n += 1;
                a += u32::from(x.ach);
            }
            if n == 0 {
                0.0
            } else {
                a as f32 / n as f32
            }
        };
        f[28] = share(&mut g.out_live(e.src));
        f[29] = share(&mut g.in_live(e.dst));

        // Ring building from structural rule hits, skipping hub accounts.
        let structural = hits.iter().any(|h| h.kind.is_structural());
        let hub = cfg.hub_degree;
        let deg = |id: u32| g.account(id).out.len() + g.account(id).inn.len();
        if structural && deg(e.src) <= hub && deg(e.dst) <= hub {
            self.rule_rings.add(e.src, e.dst, e.ts, e.seq);
        }
        let ring = self.rule_rings.info(e.src).map_or(0, |r| r.size);
        f[25] = ln1p(ring as f64);
    }

    /// Build the analyst-facing alert for a scored transfer (call only when `scored.alert`).
    pub fn build_alert(&mut self, t: &Txn, scored: &Scored) -> Alert {
        let cap = self.cfg.detectors.evidence_cap;
        let src = self.graph.lookup(&t.from_key()).unwrap_or(0);
        let mut detectors_out: Vec<AlertDetector> = scored
            .hits
            .iter()
            .map(|h| AlertDetector {
                name: h.kind.as_str().to_string(),
                value: h.value,
                detail: h.detail.clone(),
                edges: self.edges(&h.evidence, cap),
            })
            .collect();
        let (members, ring_ev) = self.alert_rings.members(src, 50);
        let ring_info = self.alert_rings.info(src);
        if let Some(info) = &ring_info {
            if info.size >= self.cfg.detectors.ring_min_size {
                detectors_out.push(AlertDetector {
                    name: DetectorKind::Ring.as_str().to_string(),
                    value: info.size as f64,
                    detail: format!(
                        "account belongs to a ring of {} accounts linked by {} alerted transfers",
                        info.size, info.edges
                    ),
                    edges: self.edges(&ring_ev, cap),
                });
            }
        }
        let top = self
            .model
            .as_ref()
            .map(|m| m.explain(&scored.features, 5))
            .unwrap_or_default()
            .into_iter()
            .map(|(name, contribution)| FeatureContribution { name, contribution })
            .collect();
        Alert {
            alert_id: format!("txn-{}", t.id),
            tx_id: t.id,
            timestamp: format_ts(t.ts),
            from_account: t.from_key(),
            to_account: t.to_key(),
            amount_usd: (t.amount_usd() * 100.0).round() / 100.0,
            currency: t.payment_currency.clone(),
            payment_format: t.format.as_str().to_string(),
            score: scored.score.unwrap_or(0.0),
            threshold: self.threshold,
            model_version: self
                .model
                .as_ref()
                .map_or_else(|| "rules".to_string(), |m| m.version.clone()),
            ring_id: ring_info.map(|r| format!("R-{}", r.ring_id)),
            ring_accounts: members
                .iter()
                .map(|&m| self.graph.name(m).to_string())
                .collect(),
            detectors: detectors_out,
            top_features: top,
        }
    }

    fn edges(&self, seqs: &[u64], cap: usize) -> Vec<EvidenceEdge> {
        seqs.iter()
            .filter_map(|&s| self.graph.edge(s))
            .take(cap)
            .map(|e| EvidenceEdge {
                tx_id: e.txn_id,
                from: self.graph.name(e.src).to_string(),
                to: self.graph.name(e.dst).to_string(),
                amount_usd: (f64::from(e.amount_usd) * 100.0).round() / 100.0,
                timestamp: format_ts(e.ts),
            })
            .collect()
    }

    /// Ring id (alert-ring) of an account, for evaluation.
    pub fn alert_ring_of(&mut self, key: &str) -> Option<u64> {
        let id = self.graph.lookup(key)?;
        self.alert_rings.info(id).map(|r| r.ring_id)
    }
}

/// Deterministic two-way partition of accounts used for stage-1 cross-fitting.
pub fn fold_of(account_key: &str) -> usize {
    use std::hash::{Hash, Hasher};
    let mut h = rustc_hash::FxHasher::default();
    account_key.hash(&mut h);
    (h.finish() >> 7) as usize & 1
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::parse_ts;

    fn txn(id: u64, ts: &str, a: &str, b: &str, amt: f64) -> Txn {
        Txn {
            id,
            ts: parse_ts(ts).unwrap(),
            from_bank: "1".into(),
            from_account: a.into(),
            to_bank: "1".into(),
            to_account: b.into(),
            amount_paid: amt,
            payment_currency: "US Dollar".into(),
            amount_received: amt,
            receiving_currency: "US Dollar".into(),
            format: PaymentFormat::Ach,
            label: None,
        }
    }

    #[test]
    fn cycle_produces_features_and_evidence() {
        let mut e = Engine::new(EngineConfig::standard(), None);
        e.process(&txn(0, "2022/09/01 00:00", "a", "b", 1000.0));
        e.process(&txn(1, "2022/09/01 01:00", "b", "c", 990.0));
        e.process(&txn(2, "2022/09/01 02:00", "c", "d", 980.0));
        let s = e.process(&txn(3, "2022/09/01 03:00", "d", "a", 970.0));
        assert_eq!(s.features[17], 4.0);
        assert!(s
            .hits
            .iter()
            .any(|h| h.kind == DetectorKind::Cycle && h.evidence.len() == 4));
        assert_eq!(s.features[0], 1.0, "ACH one-hot");
        assert!(s.score.is_none() && !s.alert);
    }

    #[test]
    fn alert_contains_evidence_edges_and_accounts() {
        let mut e = Engine::new(EngineConfig::standard(), None);
        e.set_threshold(0.0);
        let mut last = None;
        for i in 0..6 {
            let t = txn(i, "2022/09/01 00:00", "hub", &format!("m{i}"), 100.0);
            last = Some((t.clone(), e.process(&t)));
        }
        let (t, s) = last.unwrap();
        let a = e.build_alert(&t, &s);
        assert_eq!(a.alert_id, "txn-5");
        let fo = a.detectors.iter().find(|d| d.name == "fan_out").unwrap();
        assert_eq!(fo.edges.len(), 6);
        assert_eq!(fo.edges[0].from, "1:hub");
        let json = serde_json::to_value(&a).unwrap();
        assert!(json.get("fromAccount").is_some(), "camelCase contract");
    }

    #[test]
    fn neighbour_scores_come_from_previous_edges_only() {
        use crate::features::stage1_mask;
        use crate::model::{lr::Lr, Model};
        let mask = stage1_mask();
        let constant = ModelFile {
            version: "s1".into(),
            feature_names: crate::features::FEATURE_NAMES.iter().map(|s| s.to_string()).collect(),
            feature_mask: mask.clone(),
            threshold: 0.5,
            model: Model::Lr(Lr {
                mean: vec![0.0; mask.len()],
                std: vec![1.0; mask.len()],
                weights: vec![0.0; mask.len()],
                bias: 0.0,
            }),
            notes: serde_json::Value::Null,
            stage1: Vec::new(),
        };
        let mut e = Engine::new(EngineConfig::standard(), None).with_stage1(vec![constant], false);
        let first = e.process(&txn(0, "2022/09/01 00:00", "a", "b", 10.0));
        assert_eq!(first.features[31], 0.0, "no earlier edge, no neighbour score");
        let second = e.process(&txn(1, "2022/09/01 00:05", "a", "c", 10.0));
        assert!((second.features[31] - 0.5).abs() < 1e-6, "previous outbound edge of a was scored");
        assert_eq!(second.features[28], 1.0, "all of a's outbound edges are ACH");
        let third = e.process(&txn(2, "2022/09/01 00:06", "b", "d", 10.0));
        assert!((third.features[30] - 0.5).abs() < 1e-6, "inbound a->b was scored");
    }

    #[test]
    fn folds_are_balanced() {
        let ones = (0..2000).filter(|i| fold_of(&format!("{i}:acct")) == 1).count();
        assert!((800..1200).contains(&ones), "{ones}");
    }

    #[test]
    fn self_transfer_is_not_a_graph_edge() {
        let mut e = Engine::new(EngineConfig::standard(), None);
        let s = e.process(&txn(0, "2022/09/01 00:00", "a", "a", 5.0));
        assert!(s.seq.is_none());
        assert_eq!(e.stats().live_edges, 0);
    }
}
