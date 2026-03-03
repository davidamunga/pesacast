use tauri::{command, AppHandle, Emitter};

use crate::model::MpesaTransaction;
use crate::notifications;

/// Called from the frontend whenever a BLE notification arrives from the Android device.
/// The frontend handles scan/connect/subscribe lifecycle; this command bridges BLE data
/// into the Rust notification system and re-emits for other listeners.
#[command]
pub fn process_ble_transaction(app: AppHandle, txn_json: String) -> Result<(), String> {
    let txn: MpesaTransaction = serde_json::from_str(&txn_json)
        .map_err(|e| format!("Invalid transaction JSON: {e}"))?;

    notifications::show_notification(&app, &txn);
    app.emit("mpesa-transaction", &txn)
        .map_err(|e| e.to_string())?;

    Ok(())
}
