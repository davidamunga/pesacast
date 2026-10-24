use tauri::{command, AppHandle, Emitter, State};
use tokio::sync::broadcast;

use crate::model::MpesaTransaction;
use crate::notifications;

/// Called from the frontend whenever a JSON transaction arrives over the WiFi WebSocket.
/// Behaviour is identical to [crate::ble::process_ble_transaction]: fire a desktop
/// notification, re-emit the event for other frontend listeners, and broadcast to any
/// WebSocket clients connected on ws://127.0.0.1:<port>.
#[command]
pub fn process_wifi_transaction(
    app: AppHandle,
    ws_tx: State<'_, broadcast::Sender<String>>,
    txn_json: String,
) -> Result<(), String> {
    let txn: MpesaTransaction = serde_json::from_str(&txn_json)
        .map_err(|e| format!("Invalid transaction JSON: {e}"))?;

    notifications::show_notification(&app, &txn);
    app.emit("mpesa-transaction", &txn)
        .map_err(|e| e.to_string())?;

    let serialized = serde_json::to_string(&txn).map_err(|e| e.to_string())?;
    let _ = ws_tx.send(serialized);

    Ok(())
}
