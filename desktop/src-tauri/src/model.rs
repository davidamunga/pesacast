use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpesaTransaction {
    #[serde(rename = "type")]
    pub txn_type: String,
    pub direction: String,
    pub amount: f64,
    pub currency: String,
    pub from: String,
    #[serde(rename = "ref")]
    pub reference: String,
    pub time: String,
    pub balance: f64,
    #[serde(rename = "transaction_cost", skip_serializing_if = "Option::is_none")]
    pub transaction_cost: Option<f64>,
}
