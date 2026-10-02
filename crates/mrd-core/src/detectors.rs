//! Explainable typology detectors evaluated on the temporal graph for every new edge.
//!
//! Each detector returns a numeric measurement (used as a model feature) and, when its rule
//! threshold is crossed, the sequence numbers of the edges that justify the finding so an
//! analyst can see *why* a transfer was flagged.

use crate::graph::{Adj, TemporalGraph};
use crate::types::Minutes;
use rustc_hash::{FxHashMap, FxHashSet};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DetectorKind {
    FanOut,
    FanIn,
    Cycle,
    ScatterGather,
    GatherScatter,
    PassThrough,
    Velocity,
    Ring,
}

impl DetectorKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::FanOut => "fan_out",
            Self::FanIn => "fan_in",
            Self::Cycle => "cycle",
            Self::ScatterGather => "scatter_gather",
            Self::GatherScatter => "gather_scatter",
            Self::PassThrough => "pass_through",
            Self::Velocity => "velocity",
            Self::Ring => "ring",
        }
    }

    /// Detectors whose firing marks an edge as structurally suspicious (ring building input).
    pub fn is_structural(self) -> bool {
        !matches!(self, Self::Velocity | Self::Ring)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DetectorConfig {
    /// Distinct counterparties within the window for fan-in / fan-out.
    pub fan_threshold: usize,
    /// Parallel intermediaries for scatter-gather.
    pub sg_min_paths: usize,
    /// Minimum distinct in- and out-counterparties of a gather-scatter hub.
    pub gs_min_degree: usize,
    /// Maximum cycle length in edges (including the closing edge).
    pub cycle_max_len: usize,
    /// Maximum adjacency entries examined per cycle search.
    pub cycle_budget: usize,
    /// Maximum in-edges expanded per node during the cycle search (most recent first).
    pub cycle_branch: usize,
    /// In-then-out within this many minutes counts as pass-through.
    pub pass_window: Minutes,
    /// Look-back for pass-through matching (feature value).
    pub pass_lookback: Minutes,
    /// Maximum fraction retained ("skim") by a pass-through account.
    pub pass_max_skim: f64,
    pub velocity_window: Minutes,
    pub velocity_min: usize,
    pub velocity_ratio: f64,
    /// Accounts with more live edges than this are hubs and never join rings.
    pub hub_degree: usize,
    pub ring_min_size: usize,
    pub ring_rebuild: Minutes,
    /// Neighbour expansions examined for scatter-gather.
    pub sg_branch: usize,
    pub evidence_cap: usize,
}

impl Default for DetectorConfig {
    fn default() -> Self {
        Self {
            fan_threshold: 5,
            sg_min_paths: 3,
            gs_min_degree: 3,
            cycle_max_len: 6,
            cycle_budget: 2_000,
            cycle_branch: 24,
            pass_window: 120,
            pass_lookback: 24 * 60,
            pass_max_skim: 0.10,
            velocity_window: 24 * 60,
            velocity_min: 10,
            velocity_ratio: 3.0,
            hub_degree: 100,
            ring_min_size: 3,
            ring_rebuild: 360,
            sg_branch: 24,
            evidence_cap: 30,
        }
    }
}

/// A fired detector with its evidence.
#[derive(Debug, Clone, PartialEq)]
pub struct Hit {
    pub kind: DetectorKind,
    pub value: f64,
    pub detail: String,
    pub evidence: Vec<u64>,
}

/// Reusable buffers so the hot path does not allocate.
#[derive(Default)]
pub struct Scratch {
    set_a: FxHashSet<u32>,
    set_b: FxHashSet<u32>,
    best_bound: FxHashMap<u32, Minutes>,
    path: Vec<u64>,
}

/// The new edge being evaluated.
#[derive(Debug, Clone, Copy)]
pub struct EdgeCtx {
    pub src: u32,
    pub dst: u32,
    pub ts: Minutes,
    pub amount_usd: f64,
    pub seq: u64,
}

pub fn distinct_peers<'a>(it: impl Iterator<Item = &'a Adj>, set: &mut FxHashSet<u32>) -> usize {
    set.clear();
    for a in it {
        set.insert(a.peer);
    }
    set.len()
}

/// Latest edge per distinct peer, newest first, capped.
fn latest_per_peer<'a>(
    it: impl DoubleEndedIterator<Item = &'a Adj>,
    cap: usize,
    set: &mut FxHashSet<u32>,
) -> Vec<u64> {
    set.clear();
    let mut out = Vec::new();
    for a in it.rev() {
        if set.insert(a.peer) {
            out.push(a.seq);
            if out.len() >= cap {
                break;
            }
        }
    }
    out
}

/// Fan-out: distinct receivers of `src` within the window.
pub fn fan_out(
    g: &TemporalGraph,
    e: &EdgeCtx,
    cfg: &DetectorConfig,
    s: &mut Scratch,
) -> (f64, Option<Hit>) {
    let n = distinct_peers(g.out_live(e.src), &mut s.set_a);
    let hit = (n >= cfg.fan_threshold).then(|| Hit {
        kind: DetectorKind::FanOut,
        value: n as f64,
        detail: format!(
            "source sent to {n} distinct accounts within {}h",
            g.cfg.window / 60
        ),
        evidence: latest_per_peer(g.out_live(e.src), cfg.evidence_cap, &mut s.set_b),
    });
    (n as f64, hit)
}

/// Fan-in: distinct senders into `dst` within the window.
pub fn fan_in(
    g: &TemporalGraph,
    e: &EdgeCtx,
    cfg: &DetectorConfig,
    s: &mut Scratch,
) -> (f64, Option<Hit>) {
    let n = distinct_peers(g.in_live(e.dst), &mut s.set_a);
    let hit = (n >= cfg.fan_threshold).then(|| Hit {
        kind: DetectorKind::FanIn,
        value: n as f64,
        detail: format!(
            "destination received from {n} distinct accounts within {}h",
            g.cfg.window / 60
        ),
        evidence: latest_per_peer(g.in_live(e.dst), cfg.evidence_cap, &mut s.set_b),
    });
    (n as f64, hit)
}

/// Scatter-gather: some account `s` previously paid `src` and other intermediaries `m` which
/// (like `src` now) pay `dst`. Value = number of parallel intermediaries including `src`.
pub fn scatter_gather(
    g: &TemporalGraph,
    e: &EdgeCtx,
    cfg: &DetectorConfig,
    sc: &mut Scratch,
) -> (f64, Option<Hit>) {
    sc.set_a.clear();
    for a in g.in_live(e.dst) {
        if a.peer != e.src {
            sc.set_a.insert(a.peer);
        }
    }
    if sc.set_a.is_empty() {
        return (0.0, None);
    }
    let mut best: Option<(u32, usize)> = None;
    let mut seen_s = std::mem::take(&mut sc.set_b);
    seen_s.clear();
    for a in g.in_live(e.src).rev() {
        let s = a.peer;
        if s == e.dst || !seen_s.insert(s) {
            continue;
        }
        if seen_s.len() > cfg.sg_branch {
            break;
        }
        let mut count = 0usize;
        let mut mids: Vec<u32> = Vec::new();
        for o in g.out_live(s) {
            if o.peer != e.src && sc.set_a.contains(&o.peer) && !mids.contains(&o.peer) {
                mids.push(o.peer);
                count += 1;
            }
        }
        if count > 0 && best.is_none_or(|(_, c)| count > c) {
            best = Some((s, count));
        }
    }
    sc.set_b = seen_s;
    let Some((s, count)) = best else {
        return (0.0, None);
    };
    let paths = count + 1;
    let hit = (paths >= cfg.sg_min_paths).then(|| {
        let mut ev = vec![e.seq];
        let mids: Vec<u32> = g
            .out_live(s)
            .filter(|o| o.peer != e.src && sc.set_a.contains(&o.peer))
            .map(|o| o.peer)
            .collect();
        if let Some(a) = g.in_live(e.src).rev().find(|a| a.peer == s) {
            ev.push(a.seq);
        }
        for m in mids {
            if ev.len() + 2 > cfg.evidence_cap {
                break;
            }
            if let Some(o) = g.out_live(s).rev().find(|o| o.peer == m) {
                ev.push(o.seq);
            }
            if let Some(i) = g.in_live(e.dst).rev().find(|i| i.peer == m) {
                ev.push(i.seq);
            }
        }
        ev.dedup();
        Hit {
            kind: DetectorKind::ScatterGather,
            value: paths as f64,
            detail: format!(
                "{paths} parallel intermediaries carry funds from one origin to the destination"
            ),
            evidence: ev,
        }
    });
    (paths as f64, hit)
}

/// Gather-scatter: `src` collected from many accounts and is now dispersing to many.
pub fn gather_scatter(
    g: &TemporalGraph,
    e: &EdgeCtx,
    cfg: &DetectorConfig,
    s: &mut Scratch,
) -> (f64, Option<Hit>) {
    let din = distinct_peers(g.in_live(e.src), &mut s.set_a);
    let dout = distinct_peers(g.out_live(e.src), &mut s.set_b);
    let v = din.min(dout);
    let hit = (din >= cfg.gs_min_degree && dout >= cfg.gs_min_degree).then(|| {
        let half = cfg.evidence_cap / 2;
        let mut ev = latest_per_peer(g.in_live(e.src), half, &mut s.set_a);
        ev.extend(latest_per_peer(g.out_live(e.src), half, &mut s.set_b));
        Hit {
            kind: DetectorKind::GatherScatter,
            value: v as f64,
            detail: format!("source gathered from {din} accounts and dispersed to {dout} accounts"),
            evidence: ev,
        }
    });
    (v as f64, hit)
}

/// Result of a bounded temporal cycle search.
#[derive(Debug, Clone, PartialEq)]
pub struct CycleFound {
    /// Edges on the cycle, in time order, ending with the closing edge.
    pub edges: Vec<u64>,
    /// min/max of amounts on the cycle (1.0 = perfectly conserved).
    pub amount_ratio: f64,
}

/// Does the new edge `src -> dst` close a temporal cycle `dst -> ... -> src -> dst` whose
/// earlier edges have non-decreasing timestamps (minute resolution) inside the window?
///
/// Backward depth-first search from `src` over incoming edges with decreasing timestamps,
/// depth-limited (`cycle_max_len`), branch-limited (`cycle_branch` most recent in-edges per node)
/// and budget-limited (`cycle_budget` adjacency entries), so the worst case is bounded even on
/// hub accounts. A node is re-expanded only when reached with a later time bound than before.
pub fn cycle(
    g: &TemporalGraph,
    e: &EdgeCtx,
    cfg: &DetectorConfig,
    s: &mut Scratch,
) -> Option<CycleFound> {
    if e.src == e.dst || cfg.cycle_max_len < 2 {
        return None;
    }
    // dst must have sent something inside the window, otherwise no cycle can pass through it.
    let horizon = g.horizon();
    let first_out = g.out_live(e.dst).next()?;
    if first_out.ts > e.ts {
        return None;
    }
    s.best_bound.clear();
    s.path.clear();
    let mut budget = cfg.cycle_budget;
    let max_depth = cfg.cycle_max_len - 1;
    let found = dfs(
        g,
        e,
        e.src,
        e.ts,
        1,
        max_depth,
        horizon,
        cfg.cycle_branch,
        &mut budget,
        s,
    );
    if !found {
        return None;
    }
    let mut edges: Vec<u64> = s.path.iter().rev().copied().collect();
    edges.push(e.seq);
    let mut lo = e.amount_usd;
    let mut hi = e.amount_usd;
    for &q in &edges {
        if let Some(r) = g.edge(q) {
            lo = lo.min(f64::from(r.amount_usd));
            hi = hi.max(f64::from(r.amount_usd));
        }
    }
    let amount_ratio = if hi > 0.0 { lo / hi } else { 0.0 };
    Some(CycleFound {
        edges,
        amount_ratio,
    })
}

#[allow(clippy::too_many_arguments)]
fn dfs(
    g: &TemporalGraph,
    e: &EdgeCtx,
    node: u32,
    bound: Minutes,
    depth: usize,
    max_depth: usize,
    horizon: Minutes,
    branch: usize,
    budget: &mut usize,
    s: &mut Scratch,
) -> bool {
    match s.best_bound.get(&node) {
        Some(&b) if b >= bound => return false,
        _ => {
            s.best_bound.insert(node, bound);
        }
    }
    let mut expanded = 0usize;
    // Most recent first; strictly earlier than the bound (or equal minute but earlier sequence).
    for a in g.in_live(node).rev() {
        if *budget == 0 {
            return false;
        }
        *budget -= 1;
        if a.seq >= e.seq || a.ts > bound || a.ts < horizon {
            continue;
        }
        if a.peer == e.dst {
            s.path.push(a.seq);
            return true;
        }
        if depth < max_depth && a.peer != e.src {
            expanded += 1;
            if expanded > branch {
                break;
            }
            s.path.push(a.seq);
            if dfs(
                g,
                e,
                a.peer,
                a.ts,
                depth + 1,
                max_depth,
                horizon,
                branch,
                budget,
                s,
            ) {
                return true;
            }
            s.path.pop();
        }
    }
    false
}

/// Pass-through: `src` received a similar amount shortly before sending it on.
/// Returns (minutes since matching inbound, outbound/inbound ratio, hit).
pub fn pass_through(
    g: &TemporalGraph,
    e: &EdgeCtx,
    cfg: &DetectorConfig,
) -> (Option<(Minutes, f64)>, Option<Hit>) {
    let lo_ts = e.ts.saturating_sub(cfg.pass_lookback);
    let mut found = None;
    for a in g.in_live(e.src).rev() {
        if a.ts < lo_ts {
            break;
        }
        if a.peer == e.dst {
            continue;
        }
        let inb = f64::from(a.amount_usd);
        if inb <= 0.0 {
            continue;
        }
        let ratio = e.amount_usd / inb;
        if ratio <= 1.0 + 1e-6 && ratio >= 1.0 - cfg.pass_max_skim {
            found = Some((e.ts - a.ts, ratio, a.seq));
            break;
        }
    }
    let hit = found.and_then(|(gap, ratio, seq)| {
        (gap <= cfg.pass_window).then(|| Hit {
            kind: DetectorKind::PassThrough,
            value: f64::from(gap),
            detail: format!(
                "source forwarded {:.1}% of an inbound transfer after {gap} minutes",
                ratio * 100.0
            ),
            evidence: vec![seq, e.seq],
        })
    });
    (found.map(|(g, r, _)| (g, r)), hit)
}

/// Velocity: outbound transfers of `src` in the velocity window versus its long-run rate.
/// Returns (count in window, ratio to baseline daily rate, hit).
pub fn velocity(g: &TemporalGraph, e: &EdgeCtx, cfg: &DetectorConfig) -> (f64, f64, Option<Hit>) {
    let lo = e.ts.saturating_sub(cfg.velocity_window);
    let cnt = g.out_live(e.src).rev().take_while(|a| a.ts >= lo).count();
    let acc = g.account(e.src);
    let days = (f64::from(e.ts.saturating_sub(acc.first_seen)) / 1440.0).max(1.0);
    let per_window = f64::from(acc.total_out) / days * (f64::from(cfg.velocity_window) / 1440.0);
    let ratio = if per_window > 0.0 {
        cnt as f64 / per_window
    } else {
        1.0
    };
    let hit = (cnt >= cfg.velocity_min && ratio >= cfg.velocity_ratio).then(|| Hit {
        kind: DetectorKind::Velocity,
        value: cnt as f64,
        detail: format!(
            "{cnt} outbound transfers in {}h, {ratio:.1}x the account's usual rate",
            cfg.velocity_window / 60
        ),
        evidence: g
            .out_live(e.src)
            .rev()
            .take_while(|a| a.ts >= lo)
            .take(cfg.evidence_cap)
            .map(|a| a.seq)
            .collect(),
    });
    (cnt as f64, ratio, hit)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::graph::GraphConfig;

    struct World {
        g: TemporalGraph,
        cfg: DetectorConfig,
        s: Scratch,
    }

    impl World {
        fn new() -> Self {
            Self {
                g: TemporalGraph::new(GraphConfig::default()),
                cfg: DetectorConfig::default(),
                s: Scratch::default(),
            }
        }
        fn id(&mut self, n: &str) -> u32 {
            self.g.intern(n, 0)
        }
        fn tx(&mut self, a: &str, b: &str, ts: Minutes, amt: f64) -> EdgeCtx {
            let (src, dst) = (self.id(a), self.id(b));
            self.g.advance(ts);
            let seq = self.g.insert(src, dst, amt as f32, 0);
            EdgeCtx {
                src,
                dst,
                ts,
                amount_usd: amt,
                seq,
            }
        }
    }

    #[test]
    fn detects_fan_out_and_fan_in() {
        let mut w = World::new();
        let mut last = None;
        for i in 0..6 {
            last = Some(w.tx("hub", &format!("m{i}"), 10 + i, 100.0));
        }
        let e = last.unwrap();
        let (v, hit) = fan_out(&w.g, &e, &w.cfg, &mut w.s);
        assert_eq!(v, 6.0);
        assert_eq!(hit.unwrap().evidence.len(), 6);
        for i in 0..5 {
            last = Some(w.tx(&format!("s{i}"), "sink", 100 + i, 100.0));
        }
        let (v, hit) = fan_in(&w.g, &last.unwrap(), &w.cfg, &mut w.s);
        assert_eq!(v, 5.0);
        assert!(hit.is_some());
    }

    #[test]
    fn detects_temporal_cycle_and_ignores_time_reversed_path() {
        let mut w = World::new();
        w.tx("a", "b", 1, 1000.0);
        w.tx("b", "c", 2, 990.0);
        w.tx("c", "d", 3, 980.0);
        let e = w.tx("d", "a", 4, 970.0);
        let c = cycle(&w.g, &e, &w.cfg, &mut w.s).expect("cycle a->b->c->d->a");
        assert_eq!(c.edges.len(), 4);
        assert!(c.amount_ratio > 0.95);

        // Same topology but the middle hop happens *before* the first: not a temporal cycle.
        let mut w = World::new();
        w.tx("y", "z", 1, 10.0);
        w.tx("x", "y", 2, 10.0);
        let e = w.tx("z", "x", 3, 10.0);
        assert!(cycle(&w.g, &e, &w.cfg, &mut w.s).is_none());
    }

    #[test]
    fn cycle_respects_max_length() {
        let mut w = World::new();
        w.cfg.cycle_max_len = 3;
        w.tx("a", "b", 1, 1.0);
        w.tx("b", "c", 2, 1.0);
        w.tx("c", "d", 3, 1.0);
        let e = w.tx("d", "a", 4, 1.0);
        assert!(cycle(&w.g, &e, &w.cfg, &mut w.s).is_none());
    }

    #[test]
    fn detects_scatter_gather() {
        let mut w = World::new();
        for m in ["m1", "m2", "m3"] {
            w.tx("origin", m, 1, 500.0);
        }
        w.tx("m2", "sink", 5, 480.0);
        w.tx("m3", "sink", 6, 480.0);
        let e = w.tx("m1", "sink", 7, 480.0);
        let (v, hit) = scatter_gather(&w.g, &e, &w.cfg, &mut w.s);
        assert_eq!(v, 3.0);
        let hit = hit.unwrap();
        assert!(hit.evidence.contains(&e.seq));
        assert!(hit.evidence.len() >= 5);
    }

    #[test]
    fn detects_gather_scatter() {
        let mut w = World::new();
        for i in 0..3 {
            w.tx(&format!("in{i}"), "hub", i, 100.0);
        }
        w.tx("hub", "o0", 10, 90.0);
        w.tx("hub", "o1", 11, 90.0);
        let e = w.tx("hub", "o2", 12, 90.0);
        let (v, hit) = gather_scatter(&w.g, &e, &w.cfg, &mut w.s);
        assert_eq!(v, 3.0);
        assert!(hit.is_some());
    }

    #[test]
    fn detects_pass_through_with_skim() {
        let mut w = World::new();
        w.tx("victim", "mule", 100, 1000.0);
        let e = w.tx("mule", "boss", 130, 950.0);
        let (m, hit) = pass_through(&w.g, &e, &w.cfg);
        assert_eq!(m.unwrap().0, 30);
        assert!(hit.is_some());
        // Forwarding more than was received is not a pass-through match.
        let e = w.tx("mule", "boss2", 140, 1500.0);
        assert!(pass_through(&w.g, &e, &w.cfg).1.is_none());
    }

    #[test]
    fn velocity_spike_against_baseline() {
        let mut w = World::new();
        w.tx("acct", "x", 0, 1.0);
        let mut last = None;
        for i in 0..12 {
            last = Some(w.tx("acct", &format!("p{i}"), 10 * 1440 + i, 1.0));
        }
        let (cnt, ratio, hit) = velocity(&w.g, &last.unwrap(), &w.cfg);
        assert_eq!(cnt, 12.0);
        assert!(ratio > 3.0);
        assert!(hit.is_some());
    }
}
