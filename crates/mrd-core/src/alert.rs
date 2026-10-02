//! Alert payload pushed to the case-management service (camelCase JSON contract).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceEdge {
    pub tx_id: u64,
    pub from: String,
    pub to: String,
    pub amount_usd: f64,
    pub timestamp: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlertDetector {
    pub name: String,
    pub value: f64,
    pub detail: String,
    pub edges: Vec<EvidenceEdge>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FeatureContribution {
    pub name: String,
    pub contribution: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Alert {
    pub alert_id: String,
    pub tx_id: u64,
    pub timestamp: String,
    pub from_account: String,
    pub to_account: String,
    pub amount_usd: f64,
    pub currency: String,
    pub payment_format: String,
    pub score: f64,
    pub threshold: f64,
    pub model_version: String,
    pub ring_id: Option<String>,
    pub ring_accounts: Vec<String>,
    pub detectors: Vec<AlertDetector>,
    pub top_features: Vec<FeatureContribution>,
}
