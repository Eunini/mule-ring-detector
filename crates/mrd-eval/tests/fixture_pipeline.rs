//! End-to-end pipeline on the committed synthetic fixture: prepare -> train -> evaluate.

use mrd_core::io::prepare_sorted;
use mrd_core::model::gbdt::GbdtParams;
use mrd_core::model::lr::LrParams;
use mrd_core::EngineConfig;
use mrd_eval::pipeline::{evaluate, timestamps, train, EvalOptions, TrainOptions};
use mrd_eval::split::Splits;
use std::path::PathBuf;

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../tests/fixtures")
        .join(name)
}

#[test]
fn pipeline_runs_and_graph_model_beats_naive_baseline() {
    let dir = std::env::temp_dir().join(format!("mrd-fixture-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let sorted = dir.join("sorted.csv");
    let n = prepare_sorted(&fixture("synth_trans.csv"), &sorted).unwrap();
    assert!(n > 1000);
    let splits = Splits::from_fractions(&timestamps(&sorted).unwrap(), 0.6, 0.8);
    let opt = TrainOptions {
        splits,
        neg_rate: 1.0,
        gbdt: GbdtParams {
            n_trees: 80,
            max_depth: 3,
            ..GbdtParams::default()
        },
        lr: LrParams::default(),
        engine: EngineConfig::standard(),
        early_stop_every: 10,
    };
    let out = train(&sorted, &opt).unwrap();
    assert_eq!(out.models.len(), 3);
    let reports = evaluate(
        &sorted,
        &out.models,
        &out.baselines,
        Some(&fixture("synth_patterns.txt")),
        &EvalOptions {
            splits,
            engine: EngineConfig::standard(),
            test_end: None,
        },
    )
    .unwrap();
    let r = &reports[0];
    assert!(r.positives > 0, "test split has laundering");
    assert!(r.patterns_in_split > 0);
    assert!(
        r.pattern_txns_matched > 0,
        "pattern rows matched to transactions"
    );
    let gbdt = &r.methods[0];
    let naive = r
        .methods
        .iter()
        .find(|m| m.method == r.naive_baseline)
        .unwrap();
    assert!(
        gbdt.average_precision > naive.average_precision,
        "gbdt AP {} vs naive {}",
        gbdt.average_precision,
        naive.average_precision
    );
    let md = mrd_eval::pipeline::to_markdown(&reports);
    assert!(md.contains("| gbdt |"));
    std::fs::remove_dir_all(&dir).unwrap();
}
