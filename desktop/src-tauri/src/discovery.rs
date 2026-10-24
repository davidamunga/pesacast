use std::collections::HashSet;
use std::net::IpAddr;
use std::time::{Duration, Instant};

use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;
use tauri::command;

/// Must match `NSD_SERVICE_TYPE` in the Android `WifiTransport` (with the `.local.` suffix).
const SERVICE_TYPE: &str = "_pesacast._tcp.local.";
const SCAN_WINDOW: Duration = Duration::from_millis(3000);

#[derive(Debug, Clone, Serialize, PartialEq, Eq, Hash)]
pub struct DiscoveredDevice {
    pub name: String,
    pub ip: String,
    pub port: u16,
}

/// Browses the local network for PesaCast phones for a few seconds and returns what answered.
/// The pairing token is never part of the advertisement; the user still enters it by hand.
#[command]
pub async fn discover_wifi_devices() -> Result<Vec<DiscoveredDevice>, String> {
    tokio::task::spawn_blocking(scan)
        .await
        .map_err(|e| e.to_string())?
}

fn scan() -> Result<Vec<DiscoveredDevice>, String> {
    let daemon = ServiceDaemon::new().map_err(|e| format!("mDNS unavailable: {e}"))?;
    let receiver = daemon
        .browse(SERVICE_TYPE)
        .map_err(|e| format!("mDNS browse failed: {e}"))?;

    let mut found: HashSet<DiscoveredDevice> = HashSet::new();
    let deadline = Instant::now() + SCAN_WINDOW;

    while let Some(remaining) = deadline.checked_duration_since(Instant::now()) {
        match receiver.recv_timeout(remaining) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                let name = info
                    .get_fullname()
                    .trim_end_matches(SERVICE_TYPE)
                    .trim_end_matches('.')
                    .to_string();
                for addr in info.get_addresses() {
                    let ip = addr.to_ip_addr();
                    if is_usable(&ip) {
                        found.insert(DiscoveredDevice {
                            name: name.clone(),
                            ip: ip.to_string(),
                            port: info.get_port(),
                        });
                    }
                }
            }
            Ok(_) => {}
            Err(_) => break, // timed out or channel closed
        }
    }

    let _ = daemon.shutdown();

    let mut devices: Vec<_> = found.into_iter().collect();
    devices.sort_by(|a, b| (&a.name, &a.ip).cmp(&(&b.name, &b.ip)));
    Ok(devices)
}

/// The Android server only accepts private IPv4/loopback peers, so only offer IPv4 addresses.
fn is_usable(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => !v4.is_loopback() && !v4.is_unspecified() && !v4.is_link_local(),
        IpAddr::V6(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_routable_ipv4_is_offered() {
        assert!(is_usable(&"192.168.1.20".parse().unwrap()));
        assert!(!is_usable(&"127.0.0.1".parse().unwrap()));
        assert!(!is_usable(&"169.254.1.1".parse().unwrap()));
        assert!(!is_usable(&"fe80::1".parse().unwrap()));
    }
}
