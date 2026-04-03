use tauri::{command, AppHandle, Emitter, State};
use tokio::sync::broadcast;

use crate::model::MpesaTransaction;
use crate::notifications;

/// Called from the frontend whenever a BLE notification arrives from the Android device.
/// The frontend handles scan/connect/subscribe lifecycle; this command bridges BLE data
/// into the Rust notification system, re-emits for other listeners, and broadcasts to
/// any WebSocket clients connected on ws://127.0.0.1:7878.
#[command]
pub fn process_ble_transaction(
    app: AppHandle,
    ws_tx: State<'_, broadcast::Sender<String>>,
    txn_json: String,
) -> Result<(), String> {
    let txn: MpesaTransaction = serde_json::from_str(&txn_json)
        .map_err(|e| format!("Invalid transaction JSON: {e}"))?;

    notifications::show_notification(&app, &txn);
    app.emit("mpesa-transaction", &txn)
        .map_err(|e| e.to_string())?;

    // Broadcast to WebSocket clients; silently ignore if nobody is connected.
    let serialized = serde_json::to_string(&txn).map_err(|e| e.to_string())?;
    let _ = ws_tx.send(serialized);

    Ok(())
}
