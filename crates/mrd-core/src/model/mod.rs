//! Scoring models: logistic regression and gradient-boosted shallow trees, both trained in Rust.
//!
//! Both models expose per-feature contributions for every score so that each alert carries the
//! reasons behind it: `w_i * z_i` for logistic regression and path attribution (Saabas) for
//! the trees.

pub mod gbdt;
pub mod lr;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Model {
    Lr(lr::Lr),
    Gbdt(gbdt::Gbdt),
}

impl Model {
    /// Probability of laundering.
    pub fn predict(&self, x: &[f32]) -> f64 {
        match self {
            Model::Lr(m) => m.predict(x),
            Model::Gbdt(m) => m.predict(x),
        }
    }

    /// Per-feature contributions to the log-odds (sum + bias = log-odds).
    pub fn contributions(&self, x: &[f32]) -> Vec<f64> {
        match self {
            Model::Lr(m) => m.contributions(x),
            Model::Gbdt(m) => m.contributions(x),
        }
    }

    pub fn kind(&self) -> &'static str {
        match self {
            Model::Lr(_) => "lr",
            Model::Gbdt(_) => "gbdt",
        }
    }
}

/// A trained model plus the operating threshold chosen on the validation split.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelFile {
    pub version: String,
    pub feature_names: Vec<String>,
    /// Indices of features the model was allowed to use (others are ignored).
    pub feature_mask: Vec<usize>,
    pub threshold: f64,
    pub model: Model,
    #[serde(default)]
    pub notes: serde_json::Value,
    /// First-stage models whose per-edge scores feed the neighbour features (may be empty).
    #[serde(default)]
    pub stage1: Vec<ModelFile>,
}

impl ModelFile {
    pub fn load(path: &std::path::Path) -> anyhow::Result<Self> {
        let s = std::fs::read_to_string(path)?;
        Ok(serde_json::from_str(&s)?)
    }

    pub fn save(&self, path: &std::path::Path) -> anyhow::Result<()> {
        if let Some(p) = path.parent() {
            std::fs::create_dir_all(p)?;
        }
        std::fs::write(path, serde_json::to_string(self)?)?;
        Ok(())
    }

    /// Project a full feature vector onto the model's feature mask.
    pub fn project(&self, full: &[f32], out: &mut Vec<f32>) {
        out.clear();
        out.extend(self.feature_mask.iter().map(|&i| full[i]));
    }

    pub fn score(&self, full: &[f32], buf: &mut Vec<f32>) -> f64 {
        self.project(full, buf);
        self.model.predict(buf)
    }

    /// Top-k features by absolute contribution, as (name, contribution).
    pub fn explain(&self, full: &[f32], k: usize) -> Vec<(String, f64)> {
        let mut buf = Vec::new();
        self.project(full, &mut buf);
        let c = self.model.contributions(&buf);
        let mut idx: Vec<usize> = (0..c.len()).collect();
        idx.sort_by(|&a, &b| c[b].abs().total_cmp(&c[a].abs()));
        idx.into_iter()
            .take(k)
            .filter(|&i| c[i] != 0.0)
            .map(|i| (self.feature_names[self.feature_mask[i]].clone(), c[i]))
            .collect()
    }
}

pub fn sigmoid(z: f64) -> f64 {
    1.0 / (1.0 + (-z).exp())
}

/// Training matrix: row-major features with labels and sample weights.
pub struct Dataset {
    pub n_features: usize,
    pub x: Vec<f32>,
    pub y: Vec<u8>,
    pub w: Vec<f32>,
}

impl Dataset {
    pub fn new(n_features: usize) -> Self {
        Self {
            n_features,
            x: Vec::new(),
            y: Vec::new(),
            w: Vec::new(),
        }
    }
    pub fn push(&mut self, x: &[f32], y: bool, w: f32) {
        debug_assert_eq!(x.len(), self.n_features);
        self.x.extend_from_slice(x);
        self.y.push(u8::from(y));
        self.w.push(w);
    }
    pub fn len(&self) -> usize {
        self.y.len()
    }
    pub fn is_empty(&self) -> bool {
        self.y.is_empty()
    }
    pub fn row(&self, i: usize) -> &[f32] {
        &self.x[i * self.n_features..(i + 1) * self.n_features]
    }
}
