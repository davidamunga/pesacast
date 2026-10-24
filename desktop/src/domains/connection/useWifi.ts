import { useState, useCallback, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { DiscoveredDevice, TransportStatus } from "./types";
import {
  ClientHello,
  SecureChannel,
  createClientHello,
  deriveKeys,
  helloMessage,
  parseServerHello,
} from "./wifi-crypto";

const STORAGE_KEY_IP    = "wifi_ip";
const STORAGE_KEY_PORT  = "wifi_port";
const STORAGE_KEY_TOKEN = "wifi_token";
const AUTH_TIMEOUT_MS   = 6000;
const RECONNECT_BASE_MS = 2000;
const RECONNECT_MAX_MS  = 30000;

type Phase = "hello" | "ready" | "authenticated";

export function useWifi() {
  const [wifiStatus, setWifiStatus]   = useState<TransportStatus>("disconnected");
  const [wifiError, setWifiError]     = useState<string | null>(null);
  const [wifiIp, setWifiIp]           = useState(() => localStorage.getItem(STORAGE_KEY_IP) ?? "");
  const [wifiPort, setWifiPort]       = useState(() => parseInt(localStorage.getItem(STORAGE_KEY_PORT) ?? "7877", 10));
  const [wifiToken, setWifiToken]     = useState(() => localStorage.getItem(STORAGE_KEY_TOKEN) ?? "");

  const [devices, setDevices]   = useState<DiscoveredDevice[]>([]);
  const [isScanning, setIsScanning] = useState(false);
  const [scanError, setScanError]   = useState<string | null>(null);

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

  const openSocket = useCallback(function open(ip: string, port: number, token: string) {
    const ws = new WebSocket(`ws://${ip}:${port}`);
    ws.binaryType = "arraybuffer";
    wsRef.current = ws;

    let opened = false;
    let phase: Phase = "hello";
    let hello: ClientHello | null = null;
    let channel: SecureChannel | null = null;
    // Messages are handled one at a time, in arrival order: record counters must be sequential
    // and the crypto calls are asynchronous.
    let chain: Promise<void> = Promise.resolve();

    const failAuth = (message: string) => {
      intentionalCloseRef.current = true; // retrying would not help
      ws.close();
      setWifiStatus("error");
      setWifiError(message);
    };

    const markConnected = () => {
      phase = "authenticated";
      everConnectedRef.current = true;
      reconnectAttemptRef.current = 0;
      if (authTimeoutRef.current) clearTimeout(authTimeoutRef.current);
      setWifiStatus("connected");
      setWifiError(null);
    };

    // Abort if Android doesn't confirm auth within the timeout window
    authTimeoutRef.current = setTimeout(() => {
      if (phase !== "authenticated") {
        ws.close();
        setWifiStatus("error");
        setWifiError("Connection timed out. Is PesaCast running on Android with WiFi enabled?");
      }
    }, AUTH_TIMEOUT_MS);

    ws.onopen = () => {
      opened = true;
      createClientHello().then(
        (h) => {
          hello = h;
          ws.send(helloMessage(h));
        },
        () => failAuth("Encryption is not available in this webview."),
      );
    };

    const handleMessage = async (data: string | ArrayBuffer) => {
      try {
        if (phase === "hello") {
          const serverHello = typeof data === "string" ? parseServerHello(data) : null;
          if (!serverHello || !hello) {
            failAuth("The phone sent an unexpected reply. Update PesaCast on both devices.");
            return;
          }
          const keys = await deriveKeys(
            hello.privateKey, hello.publicKey, hello.nonce, serverHello.publicKey, serverHello.nonce, token,
          );
          channel = new SecureChannel(keys.clientToServer, keys.serverToClient);
          phase = "ready";
          ws.send(await channel.seal('{"ready":true}'));
          return;
        }

        if (typeof data === "string" || !channel) {
          failAuth("The phone sent an unexpected reply. Update PesaCast on both devices.");
          return;
        }
        const plain = await channel.open(new Uint8Array(data));
        if (plain === null) {
          // Authentication failure on the first reply means the keys differ: wrong token.
          if (phase === "ready") failAuth("Authentication failed. Check your pairing token.");
          else ws.close(); // corrupted/reordered record mid-session: reconnect with fresh keys
          return;
        }
        if (phase === "ready") {
          if ((JSON.parse(plain) as { status?: string }).status === "ok") markConnected();
          else failAuth("Authentication failed. Check your pairing token.");
          return;
        }
        await invoke("process_wifi_transaction", { txnJson: plain });
      } catch (e) {
        console.error("WiFi transaction error:", e);
      }
    };

    ws.onmessage = (event: MessageEvent<string | ArrayBuffer>) => {
      chain = chain.then(() => handleMessage(event.data));
      return chain;
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

      if (opened && phase === "hello" && !everConnectedRef.current) {
        // Connected, then dropped before answering our hello: the phone does not speak the
        // encrypted protocol (an older PesaCast version), or refused us.
        setWifiStatus("error");
        setWifiError(
          "The phone didn't accept the encrypted connection. Update PesaCast on the phone to the latest version.",
        );
        return;
      }

      if (opened && phase === "ready") {
        // We sent our proof and the phone hung up: it could not verify the token (for example it
        // was regenerated while we were connected), so retrying with the same token cannot work.
        setWifiStatus("error");
        setWifiError("Authentication failed. Check your pairing token.");
        return;
      }

      // Unexpected drop: retry with exponential backoff, but only for sessions that had
      // connected successfully (a never-working IP/port shouldn't loop silently).
      if (everConnectedRef.current) {
        const delay = Math.min(
          RECONNECT_BASE_MS * 2 ** reconnectAttemptRef.current,
          RECONNECT_MAX_MS,
        );
        reconnectAttemptRef.current += 1;
        setWifiStatus("connecting");
        reconnectTimerRef.current = setTimeout(() => open(ip, port, token), delay);
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

  const scanForDevices = useCallback(async () => {
    setIsScanning(true);
    setScanError(null);
    try {
      const found = await invoke<DiscoveredDevice[]>("discover_wifi_devices");
      setDevices(found);
      if (found.length === 0) {
        setScanError("No phones found. Is WiFi enabled in PesaCast on the same network?");
      }
    } catch (e) {
      setScanError(String(e));
    } finally {
      setIsScanning(false);
    }
  }, []);

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
    devices,
    isScanning,
    scanError,
    scanForDevices,
  };
}
