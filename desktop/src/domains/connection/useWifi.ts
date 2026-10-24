import { useState, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { TransportStatus } from "./types";

const STORAGE_KEY_IP    = "wifi_ip";
const STORAGE_KEY_PORT  = "wifi_port";
const STORAGE_KEY_TOKEN = "wifi_token";
const AUTH_TIMEOUT_MS   = 6000;

export function useWifi() {
  const [wifiStatus, setWifiStatus]   = useState<TransportStatus>("disconnected");
  const [wifiError, setWifiError]     = useState<string | null>(null);
  const [wifiIp, setWifiIp]           = useState(() => localStorage.getItem(STORAGE_KEY_IP) ?? "");
  const [wifiPort, setWifiPort]       = useState(() => parseInt(localStorage.getItem(STORAGE_KEY_PORT) ?? "7877", 10));
  const [wifiToken, setWifiToken]     = useState(() => localStorage.getItem(STORAGE_KEY_TOKEN) ?? "");

  const wsRef                   = useRef<WebSocket | null>(null);
  const intentionalCloseRef     = useRef(false);
  const authTimeoutRef          = useRef<ReturnType<typeof setTimeout> | null>(null);

  const persistSettings = useCallback((ip: string, port: number, token: string) => {
    localStorage.setItem(STORAGE_KEY_IP,    ip);
    localStorage.setItem(STORAGE_KEY_PORT,  String(port));
    localStorage.setItem(STORAGE_KEY_TOKEN, token);
  }, []);

  const connectWifi = useCallback(async (ip: string, port: number, token: string) => {
    if (wsRef.current) {
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
            if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
            setWifiStatus("connected");
          } else {
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
      wsRef.current = null;
      if (!intentionalCloseRef.current) {
        setWifiStatus("disconnected");
        setWifiError(null);
      }
    };
  }, [persistSettings]);

  const disconnectWifi = useCallback(() => {
    intentionalCloseRef.current = true;
    if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
    wsRef.current?.close();
    wsRef.current = null;
    setWifiStatus("disconnected");
    setWifiError(null);
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
