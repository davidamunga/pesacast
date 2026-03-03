mod ble;
mod model;
mod notifications;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_blec::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .setup(|_app| {
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![ble::process_ble_transaction,])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
