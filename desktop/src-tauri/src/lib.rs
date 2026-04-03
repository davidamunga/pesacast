mod ble;
mod db;
mod model;
mod notifications;
mod ws_server;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let (ws_tx, _) = tokio::sync::broadcast::channel::<String>(64);
    let (config_tx, config_rx) =
        tokio::sync::watch::channel(ws_server::WsConfig::default()); // disabled by default

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_blec::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_http::init())
        .manage(ws_tx.clone())
        .manage(config_tx)
        .setup(move |_app| {
            tauri::async_runtime::spawn(ws_server::run(ws_tx, config_rx));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            ble::process_ble_transaction,
            db::db_init,
            db::db_save_transaction,
            db::db_load_transactions,
            ws_server::get_ws_config,
            ws_server::set_ws_enabled,
            ws_server::set_ws_port,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
