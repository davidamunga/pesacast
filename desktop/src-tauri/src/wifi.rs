use tauri::{command, AppHandle, State};
use tokio::sync::broadcast;

use crate::ingest::{self, RecentTransactions};

/// Called from the frontend whenever a JSON transaction arrives over the WiFi WebSocket.
/// Behaviour is identical to [crate::ble::process_ble_transaction].
#[command]
pub fn process_wifi_transaction(
    app: AppHandle,
    ws_tx: State<'_, broadcast::Sender<String>>,
    recent: State<'_, RecentTransactions>,
    txn_json: String,
) -> Result<(), String> {
    ingest::ingest(&app, &ws_tx, &recent, &txn_json)
}
