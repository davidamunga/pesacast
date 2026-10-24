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
        Self {
            enabled: false,
            port: DEFAULT_WS_PORT,
        }
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
    config_tx.send_modify(|c| c.enabled = enabled);
    Ok(())
}

#[command]
pub fn set_ws_port(config_tx: State<'_, watch::Sender<WsConfig>>, port: u16) -> Result<(), String> {
    if port < 1024 {
        return Err("Port must be 1024 or higher".to_string());
    }
    config_tx.send_modify(|c| c.port = port);
    Ok(())
}

async fn handle_client(stream: TcpStream, mut rx: broadcast::Receiver<String>, peer: String) {
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
// Signature is dictated by tungstenite's handshake `Callback` trait.
#[allow(clippy::result_large_err)]
fn reject_browser_origins(req: &Request, resp: Response) -> Result<Response, ErrorResponse> {
    if req.headers().contains_key("origin") {
        let mut err = ErrorResponse::new(Some("Browser origins are not allowed".to_string()));
        *err.status_mut() = StatusCode::FORBIDDEN;
        return Err(err);
    }
    Ok(resp)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    use tokio::time::timeout;
    use tokio_tungstenite::connect_async;
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;
    use tokio_tungstenite::tungstenite::Error as WsError;

    fn free_port() -> u16 {
        std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    }

    struct Harness {
        port: u16,
        tx: broadcast::Sender<String>,
        config: watch::Sender<WsConfig>,
    }

    async fn start(enabled: bool) -> Harness {
        let port = free_port();
        let (tx, _) = broadcast::channel(16);
        let (config, config_rx) = watch::channel(WsConfig { enabled, port });
        tokio::spawn(run(tx.clone(), config_rx));
        let h = Harness { port, tx, config };
        if enabled {
            h.wait_until_listening().await;
        }
        h
    }

    impl Harness {
        async fn wait_until_listening(&self) {
            for _ in 0..100 {
                if tokio::net::TcpStream::connect(("127.0.0.1", self.port))
                    .await
                    .is_ok()
                {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            panic!("server did not start listening on {}", self.port);
        }

        fn request(
            &self,
            origin: Option<&str>,
        ) -> tokio_tungstenite::tungstenite::http::Request<()> {
            let mut req = format!("ws://127.0.0.1:{}", self.port)
                .into_client_request()
                .unwrap();
            if let Some(o) = origin {
                req.headers_mut().insert("origin", o.parse().unwrap());
            }
            req
        }
    }

    #[test]
    fn handshake_without_origin_is_accepted() {
        let req = Request::builder().uri("/").body(()).unwrap();
        let resp = Response::new(());
        assert!(reject_browser_origins(&req, resp).is_ok());
    }

    #[test]
    fn handshake_with_any_origin_gets_403() {
        for origin in ["https://evil.example", "http://localhost:1420", "null"] {
            let req = Request::builder()
                .uri("/")
                .header("origin", origin)
                .body(())
                .unwrap();
            let err = reject_browser_origins(&req, Response::new(())).unwrap_err();
            assert_eq!(err.status(), StatusCode::FORBIDDEN, "origin {origin}");
        }
    }

    #[tokio::test]
    async fn native_client_receives_broadcasts() {
        let h = start(true).await;
        let (mut ws, _) = connect_async(h.request(None)).await.expect("connect");
        // Give the server a moment to subscribe before sending.
        tokio::time::sleep(Duration::from_millis(100)).await;
        h.tx.send(r#"{"ref":"ABC"}"#.to_string()).unwrap();

        let msg = timeout(Duration::from_secs(3), ws.next())
            .await
            .expect("timed out")
            .expect("stream ended")
            .expect("ws error");
        assert_eq!(msg, Message::Text(r#"{"ref":"ABC"}"#.into()));
    }

    #[tokio::test]
    async fn browser_origin_is_refused_with_403() {
        let h = start(true).await;
        let err = connect_async(h.request(Some("https://evil.example")))
            .await
            .expect_err("handshake should fail");
        match err {
            WsError::Http(resp) => assert_eq!(resp.status(), StatusCode::FORBIDDEN),
            other => panic!("expected an HTTP 403, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn server_is_off_until_enabled() {
        let h = start(false).await;
        assert!(
            tokio::net::TcpStream::connect(("127.0.0.1", h.port))
                .await
                .is_err(),
            "disabled server must not listen"
        );

        h.config.send_modify(|c| c.enabled = true);
        h.wait_until_listening().await;
        assert!(connect_async(h.request(None)).await.is_ok());
    }

    #[tokio::test]
    async fn disabling_stops_listening() {
        let h = start(true).await;
        h.config.send_modify(|c| c.enabled = false);
        for _ in 0..100 {
            if tokio::net::TcpStream::connect(("127.0.0.1", h.port))
                .await
                .is_err()
            {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        panic!("server still listening after being disabled");
    }
}
