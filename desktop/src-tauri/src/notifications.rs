use tauri::AppHandle;

use crate::model::MpesaTransaction;

pub fn show_notification(app: &AppHandle, txn: &MpesaTransaction) {
    let title = match txn.direction.as_str() {
        "received" => format!("M-PESA Received {} {:.2}", txn.currency, txn.amount),
        "sent"     => format!("M-PESA Sent {} {:.2}", txn.currency, txn.amount),
        "paid"     => format!("M-PESA Payment {} {:.2}", txn.currency, txn.amount),
        "withdrawn"=> format!("M-PESA Withdrawal {} {:.2}", txn.currency, txn.amount),
        "airtime"  => format!("Airtime {} {:.2}", txn.currency, txn.amount),
        _          => format!("M-PESA Transaction {} {:.2}", txn.currency, txn.amount),
    };

    let body = format!(
        "{} • Ref: {} • Bal: {} {:.2}",
        txn.from, txn.reference, txn.currency, txn.balance
    );

    let identifier = app.config().identifier.clone();

    // tauri-plugin-notification hard-codes "com.apple.Terminal" as the macOS
    // application sender when tauri::is_dev() is true, forcing the Terminal icon
    // on every notification in dev builds.  Using notify-rust directly and always
    // passing the real bundle identifier fixes the icon in both dev and production.
    tauri::async_runtime::spawn(async move {
        #[cfg(target_os = "macos")]
        let _ = notify_rust::set_application(&identifier);

        let mut n = notify_rust::Notification::new();
        n.summary(&title).body(&body);

        // On non-macOS platforms the icon is an XDG icon name or app identifier.
        #[cfg(not(target_os = "macos"))]
        n.icon(&identifier);

        if let Err(e) = n.show() {
            eprintln!("Failed to show notification: {e}");
        }
    });
}
