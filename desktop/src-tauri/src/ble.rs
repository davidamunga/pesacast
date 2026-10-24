use tauri::{command, AppHandle, State};
use tokio::sync::broadcast;

use crate::ingest::{self, RecentTransactions};

/// Called from the frontend whenever a BLE notification arrives from the Android device.
/// The frontend handles scan/connect/subscribe lifecycle; the shared ingest pipeline handles
/// dedupe, notification, re-emit and WebSocket broadcast.
#[command]
pub fn process_ble_transaction(
    app: AppHandle,
    ws_tx: State<'_, broadcast::Sender<String>>,
    recent: State<'_, RecentTransactions>,
    txn_json: String,
) -> Result<(), String> {
    ingest::ingest(&app, &ws_tx, &recent, &txn_json)
}
