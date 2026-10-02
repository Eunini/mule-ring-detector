//! Gradient-boosted shallow regression trees for binary log-loss (histogram split finding).
//!
//! Deliberately small: quantile binning to at most 64 bins per feature, depth-wise growth,
//! L2 leaf regularisation, row subsampling, and optional early stopping on a caller-provided
//! validation metric.

use super::{sigmoid, Dataset};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Node {
    /// Feature index for internal nodes; `u32::MAX` for leaves.
    pub feature: u32,
    /// Go left when `x[feature] <= threshold`.
    pub threshold: f32,
    pub left: u32,
    pub right: u32,
    /// Node output (leaf value; for internal nodes the value it would have as a leaf).
    pub value: f64,
    #[serde(skip)]
    split_bin: u8,
}

impl Node {
    fn is_leaf(&self) -> bool {
        self.feature == u32::MAX
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Tree {
    pub nodes: Vec<Node>,
}

impl Tree {
    fn leaf_value(&self, x: &[f32]) -> f64 {
        let mut i = 0usize;
        loop {
            let n = &self.nodes[i];
            if n.is_leaf() {
                return n.value;
            }
            i = if x[n.feature as usize] <= n.threshold {
                n.left as usize
            } else {
                n.right as usize
            };
        }
    }

    fn leaf_value_binned(&self, bins: &[u8]) -> f64 {
        let mut i = 0usize;
        loop {
            let n = &self.nodes[i];
            if n.is_leaf() {
                return n.value;
            }
            i = if bins[n.feature as usize] <= n.split_bin {
                n.left as usize
            } else {
                n.right as usize
            };
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Gbdt {
    pub base: f64,
    pub trees: Vec<Tree>,
}

#[derive(Debug, Clone, Copy)]
pub struct GbdtParams {
    pub n_trees: usize,
    pub max_depth: usize,
    pub eta: f64,
    pub lambda: f64,
    pub min_child_weight: f64,
    pub n_bins: usize,
    pub subsample: f64,
    pub seed: u64,
}

impl Default for GbdtParams {
    fn default() -> Self {
        Self {
            n_trees: 300,
            max_depth: 4,
            eta: 0.1,
            lambda: 1.0,
            min_child_weight: 1.0,
            n_bins: 64,
            subsample: 0.8,
            seed: 7,
        }
    }
}

impl Gbdt {
    pub fn margin(&self, x: &[f32]) -> f64 {
        self.base + self.trees.iter().map(|t| t.leaf_value(x)).sum::<f64>()
    }

    pub fn predict(&self, x: &[f32]) -> f64 {
        sigmoid(self.margin(x))
    }

    /// Saabas path attribution: each split credits its feature with the change in node value.
    pub fn contributions(&self, x: &[f32]) -> Vec<f64> {
        let nf = x.len();
        let mut c = vec![0.0; nf];
        for t in &self.trees {
            let mut i = 0usize;
            loop {
                let n = &t.nodes[i];
                if n.is_leaf() {
                    break;
                }
                let next = if x[n.feature as usize] <= n.threshold {
                    n.left
                } else {
                    n.right
                } as usize;
                c[n.feature as usize] += t.nodes[next].value - n.value;
                i = next;
            }
        }
        c
    }

    pub fn train(d: &Dataset, p: GbdtParams) -> Gbdt {
        Self::train_with_eval(d, p, 0, &mut |_| 0.0)
    }

    /// Train, calling `eval` every `every` trees (if `every > 0`) and truncating to the best.
    pub fn train_with_eval(
        d: &Dataset,
        p: GbdtParams,
        every: usize,
        eval: &mut dyn FnMut(&Gbdt) -> f64,
    ) -> Gbdt {
        let n = d.len();
        let f = d.n_features;
        let nb = p.n_bins.clamp(2, 256);
        let cuts: Vec<Vec<f32>> = (0..f).map(|j| quantile_cuts(d, j, nb)).collect();
        let mut bins = vec![0u8; n * f];
        for r in 0..n {
            for j in 0..f {
                bins[r * f + j] = cuts[j].partition_point(|c| *c < d.row(r)[j]) as u8;
            }
        }
        let wsum: f64 = d.w.iter().map(|&w| f64::from(w)).sum::<f64>().max(1e-12);
        let pos: f64 = (0..n)
            .filter(|&r| d.y[r] == 1)
            .map(|r| f64::from(d.w[r]))
            .sum();
        let prior = (pos / wsum).clamp(1e-6, 1.0 - 1e-6);
        let mut model = Gbdt {
            base: (prior / (1.0 - prior)).ln(),
            trees: Vec::new(),
        };
        let mut margin = vec![model.base; n];
        let mut g = vec![0.0f64; n];
        let mut h = vec![0.0f64; n];
        let mut best = (f64::NEG_INFINITY, 0usize);
        let mut rng = p.seed.max(1);
        for t in 0..p.n_trees {
            for r in 0..n {
                let pr = sigmoid(margin[r]);
                let w = f64::from(d.w[r]);
                g[r] = (pr - f64::from(d.y[r])) * w;
                h[r] = (pr * (1.0 - pr)).max(1e-12) * w;
            }
            let rows: Vec<u32> = (0..n as u32)
                .filter(|_| {
                    rng ^= rng << 13;
                    rng ^= rng >> 7;
                    rng ^= rng << 17;
                    (rng >> 11) as f64 / (1u64 << 53) as f64 <= p.subsample
                })
                .collect();
            let mut tree = Tree::default();
            let ctx = BuildCtx {
                bins: &bins,
                cuts: &cuts,
                g: &g,
                h: &h,
                f,
                nb,
                p: &p,
            };
            build(&ctx, &mut tree, rows, 0);
            for r in 0..n {
                margin[r] += tree.leaf_value_binned(&bins[r * f..(r + 1) * f]);
            }
            model.trees.push(tree);
            if every > 0 && ((t + 1) % every == 0 || t + 1 == p.n_trees) {
                let s = eval(&model);
                if s > best.0 {
                    best = (s, model.trees.len());
                }
            }
        }
        if every > 0 && best.1 > 0 {
            model.trees.truncate(best.1);
        }
        model
    }
}

struct BuildCtx<'a> {
    bins: &'a [u8],
    cuts: &'a [Vec<f32>],
    g: &'a [f64],
    h: &'a [f64],
    f: usize,
    nb: usize,
    p: &'a GbdtParams,
}

fn build(c: &BuildCtx, tree: &mut Tree, rows: Vec<u32>, depth: usize) -> u32 {
    let (gs, hs) = rows.iter().fold((0.0, 0.0), |(a, b), &r| {
        (a + c.g[r as usize], b + c.h[r as usize])
    });
    let lambda = c.p.lambda;
    let value = -gs / (hs + lambda) * c.p.eta;
    let id = tree.nodes.len() as u32;
    tree.nodes.push(Node {
        feature: u32::MAX,
        threshold: 0.0,
        left: 0,
        right: 0,
        value,
        split_bin: 0,
    });
    if depth >= c.p.max_depth || hs < 2.0 * c.p.min_child_weight || rows.len() < 2 {
        return id;
    }
    let mut hist = vec![(0.0f64, 0.0f64); c.f * c.nb];
    for &r in &rows {
        let r = r as usize;
        let (gr, hr) = (c.g[r], c.h[r]);
        let b = &c.bins[r * c.f..(r + 1) * c.f];
        for j in 0..c.f {
            let e = &mut hist[j * c.nb + b[j] as usize];
            e.0 += gr;
            e.1 += hr;
        }
    }
    let parent = gs * gs / (hs + lambda);
    let mut best: Option<(f64, usize, usize)> = None;
    for j in 0..c.f {
        let nb_j = c.cuts[j].len();
        let (mut gl, mut hl) = (0.0, 0.0);
        for b in 0..nb_j {
            let e = hist[j * c.nb + b];
            gl += e.0;
            hl += e.1;
            let (gr, hr) = (gs - gl, hs - hl);
            if hl < c.p.min_child_weight || hr < c.p.min_child_weight {
                continue;
            }
            let gain = gl * gl / (hl + lambda) + gr * gr / (hr + lambda) - parent;
            if gain > 1e-9 && best.is_none_or(|(bg, _, _)| gain > bg) {
                best = Some((gain, j, b));
            }
        }
    }
    let Some((_, j, b)) = best else {
        return id;
    };
    let (lrows, rrows): (Vec<u32>, Vec<u32>) = rows
        .into_iter()
        .partition(|&r| c.bins[r as usize * c.f + j] as usize <= b);
    let l = build(c, tree, lrows, depth + 1);
    let r = build(c, tree, rrows, depth + 1);
    let n = &mut tree.nodes[id as usize];
    n.feature = j as u32;
    n.threshold = c.cuts[j][b];
    n.split_bin = b as u8;
    n.left = l;
    n.right = r;
    id
}

/// Up to `nb - 1` distinct quantile cut points of feature `j`.
fn quantile_cuts(d: &Dataset, j: usize, nb: usize) -> Vec<f32> {
    let n = d.len();
    let step = (n / 200_000).max(1);
    let mut v: Vec<f32> = (0..n)
        .step_by(step)
        .map(|r| d.row(r)[j])
        .filter(|x| x.is_finite())
        .collect();
    v.sort_by(f32::total_cmp);
    v.dedup();
    if v.len() <= 1 {
        return Vec::new();
    }
    if v.len() < nb {
        // Few distinct values: cut between consecutive values (exclude the max).
        return v[..v.len() - 1].to_vec();
    }
    let mut cuts: Vec<f32> = (1..nb).map(|k| v[k * (v.len() - 1) / nb]).collect();
    cuts.dedup();
    cuts
}

#[cfg(test)]
mod tests {
    use super::*;

    fn xor_data() -> Dataset {
        let mut d = Dataset::new(3);
        let mut s = 1u64;
        for _ in 0..4000 {
            s = s
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            let a = (s >> 33) as f32 / (1u64 << 31) as f32;
            s = s
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            let b = (s >> 33) as f32 / (1u64 << 31) as f32;
            d.push(&[a, b, 0.5], (a > 0.5) ^ (b > 0.5), 1.0);
        }
        d
    }

    #[test]
    fn learns_xor_which_lr_cannot() {
        let d = xor_data();
        let m = Gbdt::train(
            &d,
            GbdtParams {
                n_trees: 60,
                max_depth: 3,
                ..GbdtParams::default()
            },
        );
        assert!(m.predict(&[0.9, 0.1, 0.5]) > 0.8);
        assert!(m.predict(&[0.9, 0.9, 0.5]) < 0.2);
        assert!(m.predict(&[0.1, 0.1, 0.5]) < 0.2);
    }

    #[test]
    fn contributions_sum_to_margin() {
        let d = xor_data();
        let m = Gbdt::train(
            &d,
            GbdtParams {
                n_trees: 20,
                ..GbdtParams::default()
            },
        );
        let x = [0.7f32, 0.2, 0.5];
        let root_sum: f64 = m.trees.iter().map(|t| t.nodes[0].value).sum();
        let total: f64 = m.contributions(&x).iter().sum::<f64>() + m.base + root_sum;
        assert!((total - m.margin(&x)).abs() < 1e-9);
        assert_eq!(
            m.contributions(&x)[2],
            0.0,
            "constant feature is never split on"
        );
    }

    #[test]
    fn early_stopping_truncates() {
        let d = xor_data();
        let mut calls = 0;
        let m = Gbdt::train_with_eval(
            &d,
            GbdtParams {
                n_trees: 40,
                ..GbdtParams::default()
            },
            10,
            &mut |g| {
                calls += 1;
                if g.trees.len() == 20 {
                    1.0
                } else {
                    0.0
                }
            },
        );
        assert_eq!(calls, 4);
        assert_eq!(m.trees.len(), 20);
    }
}
