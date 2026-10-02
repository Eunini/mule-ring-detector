//! Training and evaluation pipelines over a time-sorted replay file.
//!
//! * `train` replays the stream up to the end of the validation period with no model, collects
//!   feature vectors (all positives and a deterministic sample of negatives from the training
//!   split, every row of the validation split), trains the models, early-stops the trees and
//!   chooses every operating threshold (models and baselines) on the validation split only.
//! * `evaluate` replays the whole stream with the primary model online (exactly as production
//!   would), scores the comparison models and baselines on the same features, and reports
//!   metrics on the test split with the thresholds frozen from validation.

use crate::metrics::{average_precision, best_f1, pr_sweep, thin, Confusion, PrPoint};
use crate::patterns::{parse_patterns, Pattern};
use crate::split::{Part, Splits};
use anyhow::{Context, Result};
use mrd_core::engine::fold_of;
use mrd_core::features::{stage1_mask, FEATURE_NAMES, NEIGHBOUR, N_FEATURES, TXN_ONLY};
use mrd_core::io::parse_record;
use mrd_core::model::gbdt::{Gbdt, GbdtParams};
use mrd_core::model::lr::{Lr, LrParams};
use mrd_core::model::{Dataset, Model, ModelFile};
use mrd_core::types::{format_ts, Minutes};
use mrd_core::{Engine, EngineConfig, Txn};
use rustc_hash::FxHashMap;
use serde::{Deserialize, Serialize};
use std::path::Path;

/// Rows of a sorted replay file with their raw pattern-matching key.
pub fn for_each_row(
    path: &Path,
    mut f: impl FnMut(Txn, &csv::StringRecord) -> Result<bool>,
) -> Result<()> {
    let mut rdr = csv::ReaderBuilder::new()
        .has_headers(true)
        .buffer_capacity(1 << 20)
        .from_path(path)
        .with_context(|| format!("open {}", path.display()))?;
    let mut rec = csv::StringRecord::new();
    let mut row = 0u64;
    while rdr.read_record(&mut rec)? {
        let t = parse_record(&rec, row)?;
        row += 1;
        if !f(t, &rec)? {
            break;
        }
    }
    Ok(())
}

/// Read timestamps only (to derive splits).
pub fn timestamps(path: &Path) -> Result<Vec<Minutes>> {
    let mut v = Vec::new();
    for_each_row(path, |t, _| {
        v.push(t.ts);
        Ok(true)
    })?;
    v.sort_unstable();
    Ok(v)
}

fn keep_negative(id: u64, rate: f64) -> bool {
    let mut z = id.wrapping_add(0x9E37_79B9_7F4A_7C15);
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^= z >> 31;
    ((z >> 11) as f64 / (1u64 << 53) as f64) < rate
}

/// Thresholds for the comparison baselines, all chosen on validation.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Baselines {
    /// Single rule: flag if log1p(amount_usd) >= threshold.
    pub amount_log_threshold: f64,
    /// Single rule: flag if log1p(outbound transfers of the sender in 24h) >= threshold.
    pub velocity_log_threshold: f64,
    /// Rules engine: flag if at least this many distinct detectors fired.
    pub rules_min_detectors: f64,
    /// Which single rule (0 = amount, 1 = velocity) had the higher validation F1; it is the
    /// "naive baseline" in reports.
    pub naive: usize,
}

/// Baseline scores of a row: [amount, velocity, number of fired detectors].
pub fn baseline_scores(f: &[f32], n_detectors: usize) -> [f64; 3] {
    [f64::from(f[7]), f64::from(f[21]), n_detectors as f64]
}

pub const BASELINE_NAMES: [&str; 3] = [
    "baseline_amount_rule",
    "baseline_velocity_rule",
    "rules_only",
];

#[derive(Debug, Clone)]
pub struct TrainOptions {
    pub splits: Splits,
    pub neg_rate: f64,
    pub gbdt: GbdtParams,
    pub lr: LrParams,
    pub engine: EngineConfig,
    pub early_stop_every: usize,
}

pub struct TrainOutput {
    pub models: Vec<(String, ModelFile)>,
    pub baselines: Baselines,
    pub report: serde_json::Value,
}

fn fit_threshold(scores: &[(f64, bool)]) -> (f64, Option<PrPoint>) {
    let b = best_f1(scores);
    (b.map_or(0.5, |p| p.threshold), b)
}

/// Features collected by one training replay.
struct Collected {
    train: Dataset,
    train_fold: Vec<u8>,
    val_x: Vec<f32>,
    val_y: Vec<bool>,
    val_hits: Vec<usize>,
    n_train: u64,
    n_train_pos: u64,
    seconds: f64,
}

fn collect(input: &Path, mut engine: Engine, opt: &TrainOptions) -> Result<Collected> {
    let started = std::time::Instant::now();
    let mut c = Collected {
        train: Dataset::new(N_FEATURES),
        train_fold: Vec::new(),
        val_x: Vec::new(),
        val_y: Vec::new(),
        val_hits: Vec::new(),
        n_train: 0,
        n_train_pos: 0,
        seconds: 0.0,
    };
    let mut in_val = false;
    for_each_row(input, |t, _| {
        let part = opt.splits.of(t.ts);
        if part == Part::Test {
            return Ok(false);
        }
        if part == Part::Val && !in_val {
            // Validation is scored exactly as in deployment.
            engine.set_crossfit(false);
            in_val = true;
        }
        let s = engine.process(&t);
        let y = t.label == Some(true);
        match part {
            Part::Train => {
                c.n_train += 1;
                c.n_train_pos += u64::from(y);
                let w = if y {
                    Some(1.0)
                } else if keep_negative(t.id, opt.neg_rate) {
                    Some((1.0 / opt.neg_rate) as f32)
                } else {
                    None
                };
                if let Some(w) = w {
                    c.train.push(&s.features, y, w);
                    c.train_fold.push(fold_of(&t.from_key()) as u8);
                }
            }
            Part::Val => {
                c.val_x.extend_from_slice(&s.features);
                c.val_y.push(y);
                c.val_hits.push(distinct_kinds(&s));
            }
            Part::Test => unreachable!(),
        }
        Ok(true)
    })?;
    c.seconds = started.elapsed().as_secs_f64();
    Ok(c)
}

/// Train one model on the rows selected by `keep` with features restricted to `mask`, early
/// stopping (trees) on validation average precision. Returns the model and validation scores.
fn fit(
    c: &Collected,
    mask: &[usize],
    keep: impl Fn(usize) -> bool,
    tree: bool,
    opt: &TrainOptions,
) -> (Model, Vec<(f64, bool)>) {
    let n_val = c.val_y.len();
    let mut d = Dataset::new(mask.len());
    for i in (0..c.train.len()).filter(|&i| keep(i)) {
        let r = c.train.row(i);
        let x: Vec<f32> = mask.iter().map(|&j| r[j]).collect();
        d.push(&x, c.train.y[i] == 1, c.train.w[i]);
    }
    let val_proj: Vec<Vec<f32>> = (0..n_val)
        .map(|i| {
            let r = &c.val_x[i * N_FEATURES..(i + 1) * N_FEATURES];
            mask.iter().map(|&j| r[j]).collect()
        })
        .collect();
    let model = if tree {
        // Incremental validation margins so early stopping costs one pass per new tree.
        let mut margin: Vec<f64> = Vec::new();
        let mut done = 0usize;
        let mut eval = |g: &Gbdt| -> f64 {
            if margin.is_empty() {
                margin = vec![g.base; n_val];
            }
            for tree in &g.trees[done..] {
                let one = Gbdt {
                    base: 0.0,
                    trees: vec![tree.clone()],
                };
                for (m, x) in margin.iter_mut().zip(&val_proj) {
                    *m += one.margin(x);
                }
            }
            done = g.trees.len();
            let s: Vec<(f64, bool)> = margin
                .iter()
                .copied()
                .zip(c.val_y.iter().copied())
                .collect();
            average_precision(&s)
        };
        Model::Gbdt(Gbdt::train_with_eval(
            &d,
            opt.gbdt,
            opt.early_stop_every,
            &mut eval,
        ))
    } else {
        Model::Lr(Lr::train(&d, opt.lr))
    };
    let scores = val_proj
        .iter()
        .zip(&c.val_y)
        .map(|(x, &y)| (model.predict(x), y))
        .collect();
    (model, scores)
}

fn model_file(
    name: &str,
    mask: Vec<usize>,
    threshold: f64,
    model: Model,
    opt: &TrainOptions,
) -> ModelFile {
    ModelFile {
        version: format!("{name}-v1"),
        feature_names: FEATURE_NAMES.iter().map(|s| s.to_string()).collect(),
        feature_mask: mask,
        threshold,
        model,
        notes: serde_json::json!({
            "threshold_selection": "max F1 on validation split",
            "splits": opt.splits,
        }),
        stage1: Vec::new(),
    }
}

pub fn train(input: &Path, opt: &TrainOptions) -> Result<TrainOutput> {
    let mut report = serde_json::Map::new();
    report.insert("splits".into(), serde_json::json!(opt.splits.describe()));

    // Pass 1: base features only; train two cross-fitted first-stage models.
    let c1 = collect(input, Engine::new(opt.engine.clone(), None), opt)?;
    report.insert("pass1_replay_seconds".into(), c1.seconds.into());
    let s1_mask = stage1_mask();
    let mut stage1 = Vec::new();
    for fold in 0..2u8 {
        let t0 = std::time::Instant::now();
        let (m, scores) = fit(&c1, &s1_mask, |i| c1.train_fold[i] == fold, true, opt);
        report.insert(
            format!("stage1_fold{fold}"),
            serde_json::json!({"val_average_precision": average_precision(&scores),
                "train_seconds": t0.elapsed().as_secs_f64()}),
        );
        stage1.push(model_file(
            &format!("stage1_fold{fold}"),
            s1_mask.clone(),
            0.5,
            m,
            opt,
        ));
    }

    // Pass 2: neighbour-score features from out-of-fold stage-1 scores.
    let engine = Engine::new(opt.engine.clone(), None).with_stage1(stage1.clone(), true);
    let c = collect(input, engine, opt)?;
    report.insert("pass2_replay_seconds".into(), c.seconds.into());
    report.insert("train_rows".into(), c.n_train.into());
    report.insert("train_positives".into(), c.n_train_pos.into());
    report.insert("train_sampled_rows".into(), c.train.len().into());
    report.insert("val_rows".into(), c.val_y.len().into());
    report.insert(
        "val_positives".into(),
        c.val_y.iter().filter(|&&y| y).count().into(),
    );

    let all: Vec<usize> = (0..N_FEATURES).collect();
    let specs: [(&str, Vec<usize>, bool); 3] = [
        ("gbdt", all.clone(), true),
        ("lr", all, false),
        ("gbdt_txn_only", TXN_ONLY.to_vec(), true),
    ];
    let mut models = Vec::new();
    for (name, mask, is_tree) in specs {
        let t0 = std::time::Instant::now();
        let (model, scores) = fit(&c, &mask, |_| true, is_tree, opt);
        let (threshold, best) = fit_threshold(&scores);
        let trees = match &model {
            Model::Gbdt(g) => g.trees.len(),
            Model::Lr(_) => 0,
        };
        report.insert(
            name.into(),
            serde_json::json!({
                "val_average_precision": average_precision(&scores),
                "val_best_f1": best.map(|b| b.f1),
                "val_precision": best.map(|b| b.precision),
                "val_recall": best.map(|b| b.recall),
                "threshold": threshold,
                "trees": trees,
                "train_seconds": t0.elapsed().as_secs_f64(),
            }),
        );
        let uses_neighbours = mask.iter().any(|i| NEIGHBOUR.contains(i));
        let mut mf = model_file(name, mask, threshold, model, opt);
        if uses_neighbours {
            mf.stage1 = stage1.clone();
        }
        models.push((name.to_string(), mf));
    }

    let n_val = c.val_y.len();
    let val_row = |i: usize| &c.val_x[i * N_FEATURES..(i + 1) * N_FEATURES];
    let mut bl = [0.0f64; 3];
    let mut val_f1 = [0.0f64; 3];
    for (k, b) in bl.iter_mut().enumerate() {
        let scores: Vec<(f64, bool)> = (0..n_val)
            .map(|i| (baseline_scores(val_row(i), c.val_hits[i])[k], c.val_y[i]))
            .collect();
        let (t, best) = fit_threshold(&scores);
        *b = t;
        val_f1[k] = best.map_or(0.0, |p| p.f1);
        report.insert(
            BASELINE_NAMES[k].into(),
            serde_json::json!({"threshold": t, "val_best_f1": best.map(|b| b.f1),
                "val_precision": best.map(|b| b.precision), "val_recall": best.map(|b| b.recall)}),
        );
    }
    Ok(TrainOutput {
        models,
        baselines: Baselines {
            amount_log_threshold: bl[0],
            velocity_log_threshold: bl[1],
            rules_min_detectors: bl[2],
            naive: usize::from(val_f1[1] > val_f1[0]),
        },
        report: serde_json::Value::Object(report),
    })
}

fn distinct_kinds(s: &mrd_core::Scored) -> usize {
    let mut k: Vec<_> = s.hits.iter().map(|h| h.kind).collect();
    k.sort_by_key(|x| *x as u8);
    k.dedup();
    k.len()
}

#[derive(Debug, Clone, Serialize)]
pub struct MethodResult {
    pub method: String,
    pub threshold: f64,
    pub precision: f64,
    pub recall: f64,
    pub f1: f64,
    pub average_precision: f64,
    pub alerts: u64,
    pub alerts_per_10k: f64,
    pub confusion: Confusion,
    pub pattern_recall_any: f64,
    pub pattern_recall_half: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct TypologyResult {
    pub typology: String,
    pub patterns: usize,
    pub pattern_recall_any: f64,
    pub txns: usize,
    pub txn_recall: f64,
    pub baseline_pattern_recall_any: f64,
    pub baseline_txn_recall: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct ClusterResult {
    pub clusters: usize,
    pub clusters_with_laundering: usize,
    pub cluster_precision: f64,
    pub median_cluster_size: usize,
    pub max_cluster_size: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct EvalReport {
    pub split: String,
    pub window: String,
    pub rows: u64,
    pub positives: u64,
    pub methods: Vec<MethodResult>,
    pub naive_baseline: String,
    pub typologies: Vec<TypologyResult>,
    pub clusters: Option<ClusterResult>,
    pub pr_curve: Vec<PrPoint>,
    pub patterns_in_split: usize,
    pub pattern_txns_matched: usize,
    pub laundering_txns_outside_patterns: u64,
}

pub struct EvalOptions {
    pub splits: Splits,
    pub engine: EngineConfig,
    /// Optional exclusive upper bound on test timestamps (second report).
    pub test_end: Option<Minutes>,
}

struct EvalRow {
    ts: Minutes,
    label: bool,
    /// Scores per method (models first, then baselines).
    scores: Vec<f64>,
    patterns: Vec<u32>,
    src: u32,
    dst: u32,
}

/// Full-stream evaluation. `models[0]` is the online model whose threshold drives alerts.
pub fn evaluate(
    input: &Path,
    models: &[(String, ModelFile)],
    baselines: &Baselines,
    patterns_path: Option<&Path>,
    opt: &EvalOptions,
) -> Result<Vec<EvalReport>> {
    let patterns: Vec<Pattern> = match patterns_path {
        Some(p) => parse_patterns(std::io::BufReader::new(
            std::fs::File::open(p).with_context(|| format!("open {}", p.display()))?,
        ))?,
        None => Vec::new(),
    };
    let key_idx = crate::patterns::key_index(&patterns);
    let primary = models.first().context("need at least one model")?;
    let mut engine = Engine::new(opt.engine.clone(), Some(primary.1.clone()));
    let mut rows: Vec<EvalRow> = Vec::new();
    let mut acct_ids: FxHashMap<String, u32> = FxHashMap::default();
    let mut buf = Vec::new();
    let mut matched = 0usize;
    let mut pattern_last_ts = vec![0 as Minutes; patterns.len()];
    for_each_row(input, |t, rec| {
        let s = engine.process(&t);
        let part = opt.splits.of(t.ts);
        let pats: Vec<u32> = if key_idx.is_empty() {
            Vec::new()
        } else {
            let fields: Vec<&str> = rec.iter().take(10).collect();
            match key_idx.get(&crate::patterns::row_key(&fields)) {
                Some(v) => {
                    matched += 1;
                    for &p in v {
                        pattern_last_ts[p] = pattern_last_ts[p].max(t.ts);
                    }
                    v.iter().map(|&p| p as u32).collect()
                }
                None => Vec::new(),
            }
        };
        if part == Part::Test {
            let mut scores = Vec::with_capacity(models.len() + 3);
            scores.push(s.score.unwrap_or(0.0));
            for (_, m) in &models[1..] {
                scores.push(m.score(&s.features, &mut buf));
            }
            scores.extend(baseline_scores(&s.features, distinct_kinds(&s)));
            let n = acct_ids.len() as u32;
            let src = *acct_ids.entry(t.from_key()).or_insert(n);
            let n = acct_ids.len() as u32;
            let dst = *acct_ids.entry(t.to_key()).or_insert(n);
            rows.push(EvalRow {
                ts: t.ts,
                label: t.label == Some(true),
                scores,
                patterns: pats,
                src,
                dst,
            });
        }
        Ok(true)
    })?;

    let mut names: Vec<String> = models.iter().map(|m| m.0.clone()).collect();
    names.extend(BASELINE_NAMES.iter().map(|s| s.to_string()));
    let mut thresholds: Vec<f64> = models.iter().map(|m| m.1.threshold).collect();
    thresholds.extend([
        baselines.amount_log_threshold,
        baselines.velocity_log_threshold,
        baselines.rules_min_detectors,
    ]);

    let mut reports = vec![report_for(
        "test",
        &rows,
        |_| true,
        &names,
        &thresholds,
        &patterns,
        &pattern_last_ts,
        opt.splits.val_end,
        Minutes::MAX,
        matched,
        acct_ids.len(),
        baselines.naive,
    )];
    if let Some(end) = opt.test_end {
        reports.push(report_for(
            "test_until",
            &rows,
            |r| r.ts < end,
            &names,
            &thresholds,
            &patterns,
            &pattern_last_ts,
            opt.splits.val_end,
            end,
            matched,
            acct_ids.len(),
            baselines.naive,
        ));
    }
    Ok(reports)
}

#[allow(clippy::too_many_arguments)]
fn report_for(
    split: &str,
    all_rows: &[EvalRow],
    keep: impl Fn(&EvalRow) -> bool,
    names: &[String],
    thresholds: &[f64],
    patterns: &[Pattern],
    pattern_last_ts: &[Minutes],
    start: Minutes,
    end: Minutes,
    matched: usize,
    n_accounts: usize,
    naive_rule: usize,
) -> EvalReport {
    let rows: Vec<&EvalRow> = all_rows.iter().filter(|r| keep(r)).collect();
    let positives = rows.iter().filter(|r| r.label).count() as u64;
    // Patterns whose last transaction falls in this window.
    let in_split: Vec<usize> = (0..patterns.len())
        .filter(|&p| pattern_last_ts[p] >= start && pattern_last_ts[p] < end)
        .collect();
    let pattern_txns_total: FxHashMap<usize, usize> = {
        let mut m = FxHashMap::default();
        for r in &rows {
            for &p in &r.patterns {
                *m.entry(p as usize).or_insert(0) += 1;
            }
        }
        m
    };
    let detect = |k: usize| -> (Vec<usize>, FxHashMap<usize, usize>) {
        let mut hit: FxHashMap<usize, usize> = FxHashMap::default();
        for r in &rows {
            if r.scores[k] >= thresholds[k] {
                for &p in &r.patterns {
                    *hit.entry(p as usize).or_insert(0) += 1;
                }
            }
        }
        (in_split.clone(), hit)
    };
    let mut methods = Vec::new();
    for k in 0..names.len() {
        let mut c = Confusion::default();
        let mut sc = Vec::with_capacity(rows.len());
        for r in &rows {
            c.add(r.scores[k] >= thresholds[k], r.label);
            sc.push((r.scores[k], r.label));
        }
        let (ps, hit) = detect(k);
        let any = ps.iter().filter(|p| hit.contains_key(p)).count();
        let half = ps
            .iter()
            .filter(|p| {
                let tot = pattern_txns_total.get(p).copied().unwrap_or(0);
                tot > 0 && hit.get(p).copied().unwrap_or(0) * 2 >= tot
            })
            .count();
        methods.push(MethodResult {
            method: names[k].clone(),
            threshold: thresholds[k],
            precision: c.precision(),
            recall: c.recall(),
            f1: c.f1(),
            average_precision: average_precision(&sc),
            alerts: c.tp + c.fp,
            alerts_per_10k: c.alerts_per_10k(),
            confusion: c,
            pattern_recall_any: frac(any, ps.len()),
            pattern_recall_half: frac(half, ps.len()),
        });
    }
    // Naive baseline = the single rule that was stronger on validation (never chosen on test).
    let naive = names.len() - 3 + naive_rule;

    let mut typologies = Vec::new();
    let mut typs: Vec<String> = patterns.iter().map(|p| p.typology.clone()).collect();
    typs.sort();
    typs.dedup();
    for typ in typs {
        let ps: Vec<usize> = in_split
            .iter()
            .copied()
            .filter(|&p| patterns[p].typology == typ)
            .collect();
        let tset: rustc_hash::FxHashSet<usize> = ps.iter().copied().collect();
        let per_method = |k: usize| -> (f64, usize, f64) {
            let (_, hit) = detect(k);
            let any = ps.iter().filter(|p| hit.contains_key(p)).count();
            let (mut tx, mut tx_hit) = (0usize, 0usize);
            for r in &rows {
                if r.patterns.iter().any(|&p| tset.contains(&(p as usize))) {
                    tx += 1;
                    tx_hit += usize::from(r.scores[k] >= thresholds[k]);
                }
            }
            (frac(any, ps.len()), tx, frac(tx_hit, tx))
        };
        let (pr, tx, trc) = per_method(0);
        let (bpr, _, btrc) = per_method(naive);
        typologies.push(TypologyResult {
            typology: typ,
            patterns: ps.len(),
            pattern_recall_any: pr,
            txns: tx,
            txn_recall: trc,
            baseline_pattern_recall_any: bpr,
            baseline_txn_recall: btrc,
        });
    }

    // Alert clusters of the primary model: connected components of alerted transfers.
    let clusters = {
        let mut uf = UnionFind::new(n_accounts);
        let alerted: Vec<&&EvalRow> = rows
            .iter()
            .filter(|r| r.scores[0] >= thresholds[0])
            .collect();
        for r in &alerted {
            uf.union(r.src as usize, r.dst as usize);
        }
        let mut comp: FxHashMap<usize, (usize, bool)> = FxHashMap::default();
        let mut members: FxHashMap<usize, rustc_hash::FxHashSet<u32>> = FxHashMap::default();
        for r in &alerted {
            let root = uf.find(r.src as usize);
            let e = comp.entry(root).or_insert((0, false));
            e.0 += 1;
            e.1 |= r.label;
            let m = members.entry(root).or_default();
            m.insert(r.src);
            m.insert(r.dst);
        }
        let mut sizes: Vec<usize> = members.values().map(|m| m.len()).collect();
        sizes.sort_unstable();
        let with = comp.values().filter(|c| c.1).count();
        (!comp.is_empty()).then(|| ClusterResult {
            clusters: comp.len(),
            clusters_with_laundering: with,
            cluster_precision: frac(with, comp.len()),
            median_cluster_size: sizes[sizes.len() / 2],
            max_cluster_size: *sizes.last().unwrap_or(&0),
        })
    };
    let pr: Vec<(f64, bool)> = rows.iter().map(|r| (r.scores[0], r.label)).collect();
    let outside = rows
        .iter()
        .filter(|r| r.label && r.patterns.is_empty())
        .count() as u64;
    EvalReport {
        split: split.to_string(),
        window: format!(
            "{} .. {}",
            format_ts(start),
            if end == Minutes::MAX {
                "end".to_string()
            } else {
                format_ts(end)
            }
        ),
        rows: rows.len() as u64,
        positives,
        naive_baseline: names[naive].clone(),
        methods,
        typologies,
        clusters,
        pr_curve: thin(&pr_sweep(&pr), 200),
        patterns_in_split: in_split.len(),
        pattern_txns_matched: matched,
        laundering_txns_outside_patterns: outside,
    }
}

fn frac(a: usize, b: usize) -> f64 {
    if b == 0 {
        0.0
    } else {
        a as f64 / b as f64
    }
}

struct UnionFind {
    p: Vec<usize>,
}

impl UnionFind {
    fn new(n: usize) -> Self {
        Self {
            p: (0..n).collect(),
        }
    }
    fn find(&mut self, mut x: usize) -> usize {
        while self.p[x] != x {
            self.p[x] = self.p[self.p[x]];
            x = self.p[x];
        }
        x
    }
    fn union(&mut self, a: usize, b: usize) {
        let (ra, rb) = (self.find(a), self.find(b));
        if ra != rb {
            self.p[ra] = rb;
        }
    }
}

/// Markdown summary of evaluation reports.
pub fn to_markdown(reports: &[EvalReport]) -> String {
    let mut s = String::new();
    for r in reports {
        s += &format!(
            "### Split `{}` ({}) — {} transactions, {} laundering\n\n",
            r.split, r.window, r.rows, r.positives
        );
        s += "| Method | Threshold (from val) | Precision | Recall | F1 | Avg precision | Alerts | Alerts / 10k txns | Pattern recall (>=1 txn) | Pattern recall (>=50% txns) |\n";
        s += "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n";
        for m in &r.methods {
            s += &format!(
                "| {} | {:.4} | {:.3} | {:.3} | {:.3} | {:.3} | {} | {:.1} | {:.3} | {:.3} |\n",
                m.method,
                m.threshold,
                m.precision,
                m.recall,
                m.f1,
                m.average_precision,
                m.alerts,
                m.alerts_per_10k,
                m.pattern_recall_any,
                m.pattern_recall_half
            );
        }
        s += &format!(
            "\nPatterns ending in split: {}. Laundering transactions not listed in any pattern: {}.\n\n",
            r.patterns_in_split, r.laundering_txns_outside_patterns
        );
        if !r.typologies.is_empty() {
            s += &format!(
                "| Typology | Patterns | Pattern recall ({}) | Pattern recall ({}) | Txns | Txn recall ({}) | Txn recall ({}) |\n|---|---:|---:|---:|---:|---:|---:|\n",
                r.methods[0].method, r.naive_baseline, r.methods[0].method, r.naive_baseline
            );
            for t in &r.typologies {
                s += &format!(
                    "| {} | {} | {:.3} | {:.3} | {} | {:.3} | {:.3} |\n",
                    t.typology,
                    t.patterns,
                    t.pattern_recall_any,
                    t.baseline_pattern_recall_any,
                    t.txns,
                    t.txn_recall,
                    t.baseline_txn_recall
                );
            }
            s += "\n";
        }
        if let Some(c) = &r.clusters {
            s += &format!(
                "Alert clusters ({}): {} clusters, {} contain laundering (cluster precision {:.3}); median size {} accounts, max {}.\n\n",
                r.methods[0].method, c.clusters, c.clusters_with_laundering, c.cluster_precision, c.median_cluster_size, c.max_cluster_size
            );
        }
    }
    s
}
