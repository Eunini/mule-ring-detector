//! Feature vector layout shared by training, evaluation and online scoring.

pub const N_FEATURES: usize = 33;

pub const FEATURE_NAMES: [&str; N_FEATURES] = [
    "fmt_ach",
    "fmt_cash",
    "fmt_cheque",
    "fmt_credit_card",
    "fmt_bitcoin",
    "fmt_wire",
    "fmt_reinvestment",
    "log_amount_usd",
    "cross_currency",
    "same_bank",
    "round_amount",
    "src_out_distinct",
    "dst_in_distinct",
    "src_in_distinct",
    "dst_out_distinct",
    "scatter_gather_paths",
    "gather_scatter_degree",
    "cycle_len",
    "cycle_amount_ratio",
    "pass_gap_log_min",
    "pass_ratio",
    "src_velocity_24h",
    "src_velocity_ratio",
    "dst_in_24h",
    "pair_repeat",
    "rule_ring_size",
    "src_history",
    "dst_history",
    "src_out_ach_share",
    "dst_in_ach_share",
    "nbr_src_in_score",
    "nbr_src_out_score",
    "nbr_dst_score",
];

/// Neighbour-score features, produced by the first-stage model (see `engine`).
pub const NEIGHBOUR: [usize; 3] = [30, 31, 32];

/// Features available to the first-stage model (everything except neighbour scores).
pub fn stage1_mask() -> Vec<usize> {
    (0..N_FEATURES).filter(|i| !NEIGHBOUR.contains(i)).collect()
}

/// Transaction-only features (no graph state): used for the ablation model.
pub const TXN_ONLY: [usize; 11] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

pub fn index_of(name: &str) -> Option<usize> {
    FEATURE_NAMES.iter().position(|n| *n == name)
}

pub type Features = [f32; N_FEATURES];

pub fn ln1p(x: f64) -> f32 {
    x.max(0.0).ln_1p() as f32
}
