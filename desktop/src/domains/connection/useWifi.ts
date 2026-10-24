import { useState, useCallback, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { TransportStatus } from "./types";

const STORAGE_KEY_IP    = "wifi_ip";
const STORAGE_KEY_PORT  = "wifi_port";
const STORAGE_KEY_TOKEN = "wifi_token";
const AUTH_TIMEOUT_MS   = 6000;
const RECONNECT_BASE_MS = 2000;
const RECONNECT_MAX_MS  = 30000;

export function useWifi() {
  const [wifiStatus, setWifiStatus]   = useState<TransportStatus>("disconnected");
  const [wifiError, setWifiError]     = useState<string | null>(null);
  const [wifiIp, setWifiIp]           = useState(() => localStorage.getItem(STORAGE_KEY_IP) ?? "");
  const [wifiPort, setWifiPort]       = useState(() => parseInt(localStorage.getItem(STORAGE_KEY_PORT) ?? "7877", 10));
  const [wifiToken, setWifiToken]     = useState(() => localStorage.getItem(STORAGE_KEY_TOKEN) ?? "");

  const wsRef                   = useRef<WebSocket | null>(null);
  const intentionalCloseRef     = useRef(false);
  const authTimeoutRef          = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectTimerRef       = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectAttemptRef     = useRef(0);
  const everConnectedRef        = useRef(false);

  const persistSettings = useCallback((ip: string, port: number, token: string) => {
    localStorage.setItem(STORAGE_KEY_IP,    ip);
    localStorage.setItem(STORAGE_KEY_PORT,  String(port));
    localStorage.setItem(STORAGE_KEY_TOKEN, token);
  }, []);

  const clearReconnectTimer = () => {
    if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
    reconnectTimerRef.current = null;
  };

  const openSocket = useCallback((ip: string, port: number, token: string) => {
    const ws = new WebSocket(`ws://${ip}:${port}`);
    wsRef.current = ws;

    let authenticated = false;

    // Abort if Android doesn't confirm auth within the timeout window
    authTimeoutRef.current = setTimeout(() => {
      if (!authenticated) {
        ws.close();
        setWifiStatus("error");
        setWifiError("Connection timed out. Is PesaCast running on Android with WiFi enabled?");
      }
    }, AUTH_TIMEOUT_MS);

    ws.onopen = () => {
      ws.send(JSON.stringify({ auth: token }));
    };

    ws.onmessage = async (event: MessageEvent<string>) => {
      try {
        if (!authenticated) {
          const msg = JSON.parse(event.data) as { status?: string };
          if (msg.status === "ok") {
            authenticated = true;
            everConnectedRef.current = true;
            reconnectAttemptRef.current = 0;
            if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
            setWifiStatus("connected");
            setWifiError(null);
          } else {
            intentionalCloseRef.current = true; // bad token: retrying won't help
            ws.close();
            setWifiStatus("error");
            setWifiError("Authentication failed. Check your pairing token.");
          }
          return;
        }
        // Subsequent messages are transaction JSON
        await invoke("process_wifi_transaction", { txnJson: event.data });
      } catch (e) {
        console.error("WiFi transaction error:", e);
      }
    };

    ws.onerror = () => {
      if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
      setWifiStatus("error");
      setWifiError("Could not reach Android. Check the IP address and that WiFi transport is enabled.");
    };

    ws.onclose = () => {
      if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
      if (wsRef.current === ws) wsRef.current = null;
      if (intentionalCloseRef.current || wsRef.current) return;

      // Unexpected drop: retry with exponential backoff, but only for sessions that had
      // connected successfully (a never-working IP/port shouldn't loop silently).
      if (everConnectedRef.current) {
        const delay = Math.min(
          RECONNECT_BASE_MS * 2 ** reconnectAttemptRef.current,
          RECONNECT_MAX_MS,
        );
        reconnectAttemptRef.current += 1;
        setWifiStatus("connecting");
        reconnectTimerRef.current = setTimeout(() => openSocket(ip, port, token), delay);
      } else {
        setWifiStatus((s) => (s === "error" ? s : "disconnected"));
      }
    };
  }, []);

  const connectWifi = useCallback(async (rawIp: string, rawPort: number, rawToken: string) => {
    const ip = rawIp.trim();
    const token = rawToken.trim();
    const port = Number.isInteger(rawPort) && rawPort >= 1 && rawPort <= 65535 ? rawPort : 7877;

    clearReconnectTimer();
    if (wsRef.current) {
      intentionalCloseRef.current = true;
      wsRef.current.close();
      wsRef.current = null;
    }

    persistSettings(ip, port, token);
    setWifiIp(ip);
    setWifiPort(port);
    setWifiToken(token);
    setWifiStatus("connecting");
    setWifiError(null);
    intentionalCloseRef.current = false;
    everConnectedRef.current = false;
    reconnectAttemptRef.current = 0;

    openSocket(ip, port, token);
  }, [persistSettings, openSocket]);

  const disconnectWifi = useCallback(() => {
    intentionalCloseRef.current = true;
    clearReconnectTimer();
    if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
    wsRef.current?.close();
    wsRef.current = null;
    setWifiStatus("disconnected");
    setWifiError(null);
  }, []);

  useEffect(() => () => {
    intentionalCloseRef.current = true;
    clearReconnectTimer();
    if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
    wsRef.current?.close();
  }, []);

  return {
    wifiStatus,
    wifiError,
    wifiIp,
    wifiPort,
    wifiToken,
    setWifiIp,
    setWifiPort,
    setWifiToken,
    connectWifi,
    disconnectWifi,
  };
}
