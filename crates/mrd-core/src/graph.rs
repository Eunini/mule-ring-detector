//! Incremental temporal transaction graph over a sliding event-time window.
//!
//! Every account keeps two time-ordered adjacency deques (outgoing and incoming edges). Edges
//! older than `window` minutes are evicted lazily whenever an account is touched, and a periodic
//! sweep evicts edges of accounts that went quiet. Each deque is additionally capped at
//! `max_adj` entries so that very high-degree "hub" accounts (payroll, merchants, exchanges)
//! cannot blow up memory or per-transaction latency. A global edge log, also window-bounded,
//! maps edge sequence numbers back to full edge records for evidence output.

use crate::types::Minutes;
use rustc_hash::FxHashMap;
use std::collections::VecDeque;

/// Adjacency entry stored on both endpoints of an edge.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Adj {
    pub ts: Minutes,
    pub peer: u32,
    pub amount_usd: f32,
    pub seq: u64,
    /// True if the transfer used the ACH rail.
    pub ach: bool,
    /// First-stage risk score of the edge (0 until annotated).
    pub score: f32,
}

/// Full edge record, retrievable by sequence number while it is inside the window.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EdgeRec {
    pub seq: u64,
    pub txn_id: u64,
    pub ts: Minutes,
    pub src: u32,
    pub dst: u32,
    pub amount_usd: f32,
}

#[derive(Debug, Default, Clone)]
pub struct AccountState {
    pub out: VecDeque<Adj>,
    pub inn: VecDeque<Adj>,
    pub first_seen: Minutes,
    pub total_out: u32,
    pub total_in: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct GraphConfig {
    /// Sliding window length in minutes.
    pub window: Minutes,
    /// Maximum adjacency entries retained per account and direction.
    pub max_adj: usize,
    /// Event-time interval between global eviction sweeps.
    pub sweep_every: Minutes,
}

impl Default for GraphConfig {
    fn default() -> Self {
        Self {
            window: 96 * 60,
            max_adj: 256,
            sweep_every: 60,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, serde::Serialize)]
pub struct GraphStats {
    pub accounts: usize,
    pub live_edges: usize,
    pub adjacency_entries: usize,
    pub approx_bytes: usize,
}

pub struct TemporalGraph {
    pub cfg: GraphConfig,
    names: Vec<String>,
    index: FxHashMap<String, u32>,
    accts: Vec<AccountState>,
    log: VecDeque<EdgeRec>,
    next_seq: u64,
    now: Minutes,
    last_sweep: Minutes,
    adj_entries: usize,
}

impl TemporalGraph {
    pub fn new(cfg: GraphConfig) -> Self {
        Self {
            cfg,
            names: Vec::new(),
            index: FxHashMap::default(),
            accts: Vec::new(),
            log: VecDeque::new(),
            next_seq: 0,
            now: 0,
            last_sweep: 0,
            adj_entries: 0,
        }
    }

    /// Current event time (max timestamp seen).
    pub fn now(&self) -> Minutes {
        self.now
    }

    /// Lower bound of the live window.
    pub fn horizon(&self) -> Minutes {
        self.now.saturating_sub(self.cfg.window)
    }

    pub fn intern(&mut self, key: &str, ts: Minutes) -> u32 {
        if let Some(&id) = self.index.get(key) {
            return id;
        }
        let id = u32::try_from(self.names.len()).expect("more than u32::MAX accounts");
        self.names.push(key.to_string());
        self.index.insert(key.to_string(), id);
        self.accts.push(AccountState {
            first_seen: ts,
            ..AccountState::default()
        });
        id
    }

    pub fn lookup(&self, key: &str) -> Option<u32> {
        self.index.get(key).copied()
    }

    pub fn name(&self, id: u32) -> &str {
        &self.names[id as usize]
    }

    pub fn account(&self, id: u32) -> &AccountState {
        &self.accts[id as usize]
    }

    pub fn num_accounts(&self) -> usize {
        self.accts.len()
    }

    /// Advance event time. Out-of-order arrivals are clamped to the current time.
    pub fn advance(&mut self, ts: Minutes) -> Minutes {
        if ts > self.now {
            self.now = ts;
        }
        let horizon = self.horizon();
        while self.log.front().is_some_and(|e| e.ts < horizon) {
            self.log.pop_front();
        }
        if self.now.saturating_sub(self.last_sweep) >= self.cfg.sweep_every {
            self.sweep();
            self.last_sweep = self.now;
        }
        self.now
    }

    /// Insert an edge at the current event time; returns its sequence number.
    pub fn insert(&mut self, src: u32, dst: u32, amount_usd: f32, txn_id: u64) -> u64 {
        let ts = self.now;
        let seq = self.next_seq;
        self.next_seq += 1;
        self.log.push_back(EdgeRec {
            seq,
            txn_id,
            ts,
            src,
            dst,
            amount_usd,
        });
        let cap = self.cfg.max_adj;
        let horizon = self.horizon();
        let mut removed = 0usize;
        {
            let a = &mut self.accts[src as usize];
            removed += evict(&mut a.out, horizon);
            a.out.push_back(Adj {
                ts,
                peer: dst,
                amount_usd,
                seq,
                ach: false,
                score: 0.0,
            });
            if a.out.len() > cap {
                a.out.pop_front();
                removed += 1;
            }
            a.total_out = a.total_out.saturating_add(1);
        }
        {
            let b = &mut self.accts[dst as usize];
            removed += evict(&mut b.inn, horizon);
            b.inn.push_back(Adj {
                ts,
                peer: src,
                amount_usd,
                seq,
                ach: false,
                score: 0.0,
            });
            if b.inn.len() > cap {
                b.inn.pop_front();
                removed += 1;
            }
            b.total_in = b.total_in.saturating_add(1);
        }
        self.adj_entries = self.adj_entries + 2 - removed;
        seq
    }

    /// Update the attributes of the edge `src -> dst` inserted last (both adjacency copies).
    pub fn annotate_last(&mut self, src: u32, dst: u32, f: impl Fn(&mut Adj)) {
        if let Some(a) = self.accts[src as usize].out.back_mut() {
            f(a);
        }
        if let Some(b) = self.accts[dst as usize].inn.back_mut() {
            f(b);
        }
    }

    /// Live outgoing edges of `id` (oldest first), filtered to the window.
    pub fn out_live(&self, id: u32) -> impl DoubleEndedIterator<Item = &Adj> + '_ {
        live(&self.accts[id as usize].out, self.horizon())
    }

    /// Live incoming edges of `id` (oldest first), filtered to the window.
    pub fn in_live(&self, id: u32) -> impl DoubleEndedIterator<Item = &Adj> + '_ {
        live(&self.accts[id as usize].inn, self.horizon())
    }

    /// Edge record for a sequence number, if still inside the window.
    pub fn edge(&self, seq: u64) -> Option<&EdgeRec> {
        let first = self.log.front()?.seq;
        if seq < first {
            return None;
        }
        self.log.get(usize::try_from(seq - first).ok()?)
    }

    pub fn stats(&self) -> GraphStats {
        let adj = std::mem::size_of::<Adj>();
        let per_acct = std::mem::size_of::<AccountState>() + 48;
        let name_bytes: usize = self.names.iter().map(|n| n.len() + 24).sum::<usize>() * 2;
        GraphStats {
            accounts: self.accts.len(),
            live_edges: self.log.len(),
            adjacency_entries: self.adj_entries,
            approx_bytes: self.adj_entries * adj
                + self.log.len() * std::mem::size_of::<EdgeRec>()
                + self.accts.len() * per_acct
                + name_bytes,
        }
    }

    /// Evict stale edges from every account and release memory of idle deques.
    pub fn sweep(&mut self) {
        let horizon = self.horizon();
        let mut removed = 0usize;
        for a in &mut self.accts {
            removed += evict(&mut a.out, horizon) + evict(&mut a.inn, horizon);
            if a.out.is_empty() && a.out.capacity() > 0 {
                a.out = VecDeque::new();
            }
            if a.inn.is_empty() && a.inn.capacity() > 0 {
                a.inn = VecDeque::new();
            }
        }
        self.adj_entries -= removed;
    }
}

fn evict(d: &mut VecDeque<Adj>, horizon: Minutes) -> usize {
    let mut n = 0;
    while d.front().is_some_and(|e| e.ts < horizon) {
        d.pop_front();
        n += 1;
    }
    n
}

fn live(d: &VecDeque<Adj>, horizon: Minutes) -> impl DoubleEndedIterator<Item = &Adj> + '_ {
    let start = d.partition_point(|e| e.ts < horizon);
    d.range(start..)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn window_eviction() {
        let mut g = TemporalGraph::new(GraphConfig {
            window: 10,
            max_adj: 100,
            sweep_every: 1000,
        });
        let a = g.intern("a", 0);
        let b = g.intern("b", 0);
        g.advance(0);
        let s0 = g.insert(a, b, 1.0, 0);
        g.advance(5);
        g.insert(a, b, 2.0, 1);
        assert_eq!(g.out_live(a).count(), 2);
        g.advance(12);
        assert_eq!(g.out_live(a).count(), 1, "edge at t=0 left the window");
        assert!(g.edge(s0).is_none());
        g.sweep();
        assert_eq!(g.stats().adjacency_entries, 2);
    }

    proptest! {
        /// Memory stays bounded: per-account deques never exceed the cap and no live edge is
        /// older than the window, whatever the arrival pattern.
        #[test]
        fn bounded_state(edges in proptest::collection::vec((0u32..8, 0u32..8, 0u32..30), 1..400)) {
            let cfg = GraphConfig { window: 50, max_adj: 7, sweep_every: 20 };
            let mut g = TemporalGraph::new(cfg);
            let ids: Vec<u32> = (0..8).map(|i| g.intern(&format!("acct{i}"), 0)).collect();
            let mut t = 0;
            for (i, (s, d, dt)) in edges.iter().enumerate() {
                t += dt;
                g.advance(t);
                g.insert(ids[*s as usize], ids[*d as usize], 1.0, i as u64);
                for &id in &ids {
                    let acc = g.account(id);
                    prop_assert!(acc.out.len() <= cfg.max_adj && acc.inn.len() <= cfg.max_adj);
                    prop_assert!(g.out_live(id).all(|e| e.ts + cfg.window >= g.now()));
                }
                let total: usize = ids.iter().map(|&id| g.account(id).out.len() + g.account(id).inn.len()).sum();
                prop_assert_eq!(total, g.stats().adjacency_entries);
            }
        }
    }
}
