use std::collections::VecDeque;
use std::sync::Mutex;

use tauri::{AppHandle, Emitter};
use tokio::sync::broadcast;

use crate::model::MpesaTransaction;
use crate::notifications;

const RECENT_CAPACITY: usize = 200;

/// Remembers recently seen `(ref, time)` pairs so the same transaction arriving over
/// both BLE and WiFi is only notified, emitted and broadcast once.
#[derive(Default)]
pub struct RecentTransactions(Mutex<VecDeque<(String, String)>>);

impl RecentTransactions {
    /// Returns true if the key was new (and records it), false if already seen.
    fn insert(&self, txn: &MpesaTransaction) -> bool {
        let key = (txn.reference.clone(), txn.time.clone());
        let mut seen = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if seen.contains(&key) {
            return false;
        }
        if seen.len() >= RECENT_CAPACITY {
            seen.pop_front();
        }
        seen.push_back(key);
        true
    }
}

/// Shared pipeline for every transport: parse, dedupe, notify, re-emit to the frontend,
/// and broadcast to local WebSocket clients.
pub fn ingest(
    app: &AppHandle,
    ws_tx: &broadcast::Sender<String>,
    recent: &RecentTransactions,
    txn_json: &str,
) -> Result<(), String> {
    let txn: MpesaTransaction =
        serde_json::from_str(txn_json).map_err(|e| format!("Invalid transaction JSON: {e}"))?;

    if !recent.insert(&txn) {
        return Ok(());
    }

    notifications::show_notification(app, &txn);
    app.emit("mpesa-transaction", &txn)
        .map_err(|e| e.to_string())?;

    let serialized = serde_json::to_string(&txn).map_err(|e| e.to_string())?;
    // Silently ignore if nobody is connected.
    let _ = ws_tx.send(serialized);

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn txn(reference: &str, time: &str) -> MpesaTransaction {
        MpesaTransaction {
            txn_type: "received".into(),
            direction: "in".into(),
            amount: 1.0,
            currency: "KES".into(),
            from: "X".into(),
            reference: reference.into(),
            time: time.into(),
            balance: 1.0,
            transaction_cost: None,
        }
    }

    #[test]
    fn duplicate_is_rejected() {
        let recent = RecentTransactions::default();
        assert!(recent.insert(&txn("ABC", "t1")));
        assert!(!recent.insert(&txn("ABC", "t1")));
        assert!(recent.insert(&txn("ABC", "t2")));
    }

    #[test]
    fn oldest_entries_are_evicted() {
        let recent = RecentTransactions::default();
        for i in 0..=RECENT_CAPACITY {
            assert!(recent.insert(&txn(&i.to_string(), "t")));
        }
        assert!(recent.insert(&txn("0", "t")));
    }
}
