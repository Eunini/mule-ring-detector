//! Ring (community) tracking over suspicious edges.
//!
//! Union-find cannot delete edges, so the structure is rebuilt from the live suspicious edges
//! every `rebuild_every` minutes of event time; between rebuilds unions are incremental and
//! O(alpha(n)). Components therefore may contain edges up to `window + rebuild_every` old.
//! Members are tracked per root with small-to-large merging so a ring can be listed cheaply.

use crate::types::Minutes;
use rustc_hash::FxHashMap;
use std::collections::VecDeque;

const NONE: u32 = u32::MAX;

#[derive(Debug, Clone, Copy)]
struct SuspEdge {
    ts: Minutes,
    seq: u64,
    a: u32,
    b: u32,
}

#[derive(Debug, Clone, Default)]
struct Comp {
    members: Vec<u32>,
    edges: u32,
    /// Most recent evidence edges (sequence numbers), capped.
    evidence: VecDeque<u64>,
    /// Smallest edge sequence number in the component, used as a ring identifier.
    rid: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RingInfo {
    pub ring_id: u64,
    pub size: usize,
    pub edges: u32,
}

pub struct Rings {
    window: Minutes,
    rebuild_every: Minutes,
    evidence_cap: usize,
    parent: Vec<u32>,
    comps: FxHashMap<u32, Comp>,
    touched: Vec<u32>,
    susp: VecDeque<SuspEdge>,
    last_rebuild: Minutes,
}

impl Rings {
    pub fn new(window: Minutes, rebuild_every: Minutes, evidence_cap: usize) -> Self {
        Self {
            window,
            rebuild_every,
            evidence_cap,
            parent: Vec::new(),
            comps: FxHashMap::default(),
            touched: Vec::new(),
            susp: VecDeque::new(),
            last_rebuild: 0,
        }
    }

    fn ensure(&mut self, x: u32) {
        let need = x as usize + 1;
        if self.parent.len() < need {
            self.parent.resize(need, NONE);
        }
        if self.parent[x as usize] == NONE {
            self.parent[x as usize] = x;
            self.touched.push(x);
            self.comps.insert(
                x,
                Comp {
                    members: vec![x],
                    rid: u64::MAX,
                    ..Comp::default()
                },
            );
        }
    }

    fn find(&mut self, mut x: u32) -> u32 {
        while self.parent[x as usize] != x {
            let p = self.parent[x as usize];
            self.parent[x as usize] = self.parent[p as usize];
            x = p;
        }
        x
    }

    /// Root of `x`, or `None` if `x` has no suspicious edge in the structure.
    pub fn root(&mut self, x: u32) -> Option<u32> {
        if (x as usize) < self.parent.len() && self.parent[x as usize] != NONE {
            Some(self.find(x))
        } else {
            None
        }
    }

    /// Record a suspicious edge between accounts `a` and `b`.
    pub fn add(&mut self, a: u32, b: u32, ts: Minutes, seq: u64) {
        self.susp.push_back(SuspEdge { ts, seq, a, b });
        self.union_edge(a, b, seq);
    }

    fn union_edge(&mut self, a: u32, b: u32, seq: u64) {
        self.ensure(a);
        self.ensure(b);
        let (ra, rb) = (self.find(a), self.find(b));
        let root = if ra == rb {
            ra
        } else {
            let (big, small) = {
                let la = self.comps[&ra].members.len();
                let lb = self.comps[&rb].members.len();
                if la >= lb {
                    (ra, rb)
                } else {
                    (rb, ra)
                }
            };
            let s = self.comps.remove(&small).unwrap_or_default();
            self.parent[small as usize] = big;
            let cap = self.evidence_cap;
            let c = self.comps.get_mut(&big).expect("root has component");
            c.members.extend(s.members);
            c.edges += s.edges;
            c.rid = c.rid.min(s.rid);
            for e in s.evidence {
                c.evidence.push_back(e);
            }
            while c.evidence.len() > cap {
                c.evidence.pop_front();
            }
            big
        };
        let cap = self.evidence_cap;
        let c = self.comps.get_mut(&root).expect("root has component");
        c.edges += 1;
        c.rid = c.rid.min(seq);
        c.evidence.push_back(seq);
        while c.evidence.len() > cap {
            c.evidence.pop_front();
        }
    }

    /// Rebuild from live edges if the rebuild interval elapsed. Returns true if rebuilt.
    pub fn maybe_rebuild(&mut self, now: Minutes) -> bool {
        if now.saturating_sub(self.last_rebuild) < self.rebuild_every {
            return false;
        }
        self.last_rebuild = now;
        let horizon = now.saturating_sub(self.window);
        while self.susp.front().is_some_and(|e| e.ts < horizon) {
            self.susp.pop_front();
        }
        for &x in &self.touched {
            self.parent[x as usize] = NONE;
        }
        self.touched.clear();
        self.comps.clear();
        let edges: Vec<SuspEdge> = self.susp.iter().copied().collect();
        for e in edges {
            self.union_edge(e.a, e.b, e.seq);
        }
        true
    }

    pub fn info(&mut self, x: u32) -> Option<RingInfo> {
        let r = self.root(x)?;
        let c = &self.comps[&r];
        Some(RingInfo {
            ring_id: c.rid,
            size: c.members.len(),
            edges: c.edges,
        })
    }

    /// Members of the ring containing `x` (up to `cap`) and its recent evidence edges.
    pub fn members(&mut self, x: u32, cap: usize) -> (Vec<u32>, Vec<u64>) {
        match self.root(x) {
            Some(r) => {
                let c = &self.comps[&r];
                (
                    c.members.iter().take(cap).copied().collect(),
                    c.evidence.iter().copied().collect(),
                )
            }
            None => (Vec::new(), Vec::new()),
        }
    }

    pub fn live_edges(&self) -> usize {
        self.susp.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn unions_and_rebuild_evicts() {
        let mut r = Rings::new(100, 10, 5);
        r.add(1, 2, 0, 0);
        r.add(2, 3, 5, 1);
        r.add(7, 8, 50, 2);
        assert_eq!(r.info(1).unwrap().size, 3);
        assert_eq!(r.info(3).unwrap().ring_id, 0);
        assert!(r.info(4).is_none());
        // At t=103 the t=0 edge is out of the window; 1 drops out of the ring.
        assert!(r.maybe_rebuild(103));
        assert!(r.info(1).is_none());
        assert_eq!(r.info(2).unwrap().size, 2);
        assert_eq!(r.info(2).unwrap().ring_id, 1);
        assert_eq!(r.live_edges(), 2);
    }

    proptest! {
        /// Component sizes agree with a naive BFS connectivity check.
        #[test]
        fn matches_naive_components(edges in proptest::collection::vec((0u32..20, 0u32..20), 1..60)) {
            let mut r = Rings::new(1000, 1000, 4);
            for (i, (a, b)) in edges.iter().enumerate() {
                r.add(*a, *b, 0, i as u64);
            }
            for x in 0u32..20 {
                let mut seen = std::collections::HashSet::new();
                let mut stack = vec![x];
                let present = edges.iter().any(|(a, b)| *a == x || *b == x);
                while let Some(n) = stack.pop() {
                    if !seen.insert(n) { continue; }
                    for (a, b) in &edges {
                        if *a == n { stack.push(*b); }
                        if *b == n { stack.push(*a); }
                    }
                }
                match r.info(x) {
                    Some(info) => { prop_assert!(present); prop_assert_eq!(info.size, seen.len()); }
                    None => prop_assert!(!present),
                }
            }
        }
    }
}
