use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use tauri::{command, State};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, watch};
use tokio_tungstenite::{
    accept_hdr_async,
    tungstenite::{
        handshake::server::{ErrorResponse, Request, Response},
        http::StatusCode,
        Message,
    },
};

pub const DEFAULT_WS_PORT: u16 = 7878;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WsConfig {
    pub enabled: bool,
    pub port: u16,
}

impl Default for WsConfig {
    fn default() -> Self {
        Self { enabled: false, port: DEFAULT_WS_PORT }
    }
}

pub async fn run(tx: broadcast::Sender<String>, mut config_rx: watch::Receiver<WsConfig>) {
    loop {
        let port = loop {
            let cfg = config_rx.borrow_and_update().clone();
            if cfg.enabled {
                break cfg.port;
            }
            if config_rx.changed().await.is_err() {
                return; // app shutting down
            }
        };

        let addr = format!("127.0.0.1:{port}");
        let listener = match TcpListener::bind(&addr).await {
            Ok(l) => {
                println!("[pesacast-ws] listening on ws://{addr}");
                l
            }
            Err(e) => {
                eprintln!("[pesacast-ws] failed to bind {addr}: {e}");
                if config_rx.changed().await.is_err() {
                    return;
                }
                continue;
            }
        };

        // Accept connections until disabled or the port changes
        loop {
            tokio::select! {
                accept_result = listener.accept() => {
                    match accept_result {
                        Ok((stream, peer)) => {
                            println!("[pesacast-ws] client connected: {peer}");
                            let rx = tx.subscribe();
                            tokio::spawn(handle_client(stream, rx, peer.to_string()));
                        }
                        Err(e) => eprintln!("[pesacast-ws] accept error: {e}"),
                    }
                }
                _ = config_rx.changed() => {
                    let cfg = config_rx.borrow().clone();
                    if !cfg.enabled || cfg.port != port {
                        println!(
                            "[pesacast-ws] config changed (enabled={}, port={}), restarting",
                            cfg.enabled, cfg.port
                        );
                        break;
                    }
                }
            }
        }
    }
}

#[command]
pub fn get_ws_config(config_tx: State<'_, watch::Sender<WsConfig>>) -> WsConfig {
    config_tx.borrow().clone()
}

#[command]
pub fn set_ws_enabled(
    config_tx: State<'_, watch::Sender<WsConfig>>,
    enabled: bool,
) -> Result<(), String> {
    config_tx
        .send_modify(|c| c.enabled = enabled);
    Ok(())
}

#[command]
pub fn set_ws_port(
    config_tx: State<'_, watch::Sender<WsConfig>>,
    port: u16,
) -> Result<(), String> {
    if port < 1024 {
        return Err("Port must be 1024 or higher".to_string());
    }
    config_tx.send_modify(|c| c.port = port);
    Ok(())
}

async fn handle_client(
    stream: TcpStream,
    mut rx: broadcast::Receiver<String>,
    peer: String,
) {
    let ws = match accept_hdr_async(stream, reject_browser_origins).await {
        Ok(ws) => ws,
        Err(e) => {
            eprintln!("[pesacast-ws] WS handshake failed for {peer}: {e}");
            return;
        }
    };

    let (mut sink, mut source) = ws.split();

    loop {
        tokio::select! {
            msg = rx.recv() => {
                match msg {
                    Ok(json) => {
                        if sink.send(Message::Text(json.into())).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        eprintln!("[pesacast-ws] {peer} lagged, dropped {n} messages");
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
            frame = source.next() => {
                match frame {
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Ok(Message::Ping(data))) => {
                        let _ = sink.send(Message::Pong(data)).await;
                    }
                    _ => {}
                }
            }
        }
    }

    println!("[pesacast-ws] client disconnected: {peer}");
}

/// Browsers always attach an `Origin` header to WebSocket handshakes, and scripts on any web
/// page can reach 127.0.0.1. Native clients (curl, websocat, scripts) send none, so refusing
/// every request that carries one blocks cross-site WebSocket hijacking without affecting them.
fn reject_browser_origins(req: &Request, resp: Response) -> Result<Response, ErrorResponse> {
    if req.headers().contains_key("origin") {
        let mut err = ErrorResponse::new(Some("Browser origins are not allowed".to_string()));
        *err.status_mut() = StatusCode::FORBIDDEN;
        return Err(err);
    }
    Ok(resp)
}
