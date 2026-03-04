import { useState, useCallback, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import {
  startScan,
  connect,
  disconnect,
  subscribeString,
  getConnectionUpdates,
  checkPermissions,
  BleDevice,
} from "@mnlphlp/plugin-blec";
import { SERVICE_UUID, CHAR_UUID } from "./constants";
import { TransportStatus } from "./types";

export function useBle() {
  const [bleStatus, setBleStatus] = useState<TransportStatus>("disconnected");
  const [bleDevices, setBleDevices] = useState<BleDevice[]>([]);
  const [selectedBleDevice, setSelectedBleDevice] = useState<string | null>(null);
  const [isScanning, setIsScanning] = useState(false);

  const bleConnectedRef = useRef(false);
  // Reassembly buffer for multi-chunk BLE frames ("NNNN:" length-prefix framing)
  const bleBufferRef = useRef<{ expected: number; data: string } | null>(null);

  useEffect(() => {
    getConnectionUpdates((connected) => {
      bleConnectedRef.current = connected;
      setBleStatus(connected ? "connected" : "disconnected");
      if (!connected) setSelectedBleDevice(null);
    }).catch(console.error);
  }, []);

  const scanBle = useCallback(async () => {
    setIsScanning(true);
    setBleDevices([]);
    try {
      const hasPerms = await checkPermissions(true);
      if (!hasPerms) {
        setBleStatus("error");
        return;
      }
      await startScan((devices) => {
        const filtered = devices.filter(
          (d) =>
            d.services.some((s) => s.toLowerCase() === SERVICE_UUID) ||
            d.name?.toLowerCase().includes("pesacast"),
        );
        setBleDevices(filtered.length > 0 ? filtered : devices);
      }, 6000);
    } catch (e) {
      console.error("BLE scan error:", e);
    } finally {
      setIsScanning(false);
    }
  }, []);

  const connectBle = useCallback(async (address: string) => {
    setBleStatus("connecting");
    setSelectedBleDevice(address);
    try {
      await connect(address, () => {
        setBleStatus("disconnected");
        setSelectedBleDevice(null);
        bleConnectedRef.current = false;
      });

      bleBufferRef.current = null;
      await subscribeString(CHAR_UUID, async (chunk) => {
        try {
          let json: string | null = null;

          const colonIdx = chunk.indexOf(":");
          const maybeLen = colonIdx > 0 ? parseInt(chunk.slice(0, colonIdx), 10) : NaN;

          if (!isNaN(maybeLen)) {
            const payload = chunk.slice(colonIdx + 1);
            if (payload.length >= maybeLen) {
              json = payload.slice(0, maybeLen);
              bleBufferRef.current = null;
            } else {
              bleBufferRef.current = { expected: maybeLen, data: payload };
            }
          } else if (bleBufferRef.current) {
            bleBufferRef.current.data += chunk;
            if (bleBufferRef.current.data.length >= bleBufferRef.current.expected) {
              json = bleBufferRef.current.data.slice(0, bleBufferRef.current.expected);
              bleBufferRef.current = null;
            }
          } else {
            json = chunk;
          }

          if (json === null) return;

          await invoke("process_ble_transaction", { txnJson: json });
        } catch (e) {
          console.error("BLE transaction parse error:", e);
        }
      });

      setBleStatus("connected");
      bleConnectedRef.current = true;
    } catch (e) {
      console.error("BLE connect error:", e);
      setBleStatus("error");
      setSelectedBleDevice(null);
    }
  }, []);

  const disconnectBle = useCallback(async () => {
    setBleStatus("disconnected");
    setSelectedBleDevice(null);
    bleConnectedRef.current = false;
    try {
      await disconnect();
    } catch (e) {
      console.error("BLE disconnect error:", e);
    }
  }, []);

  
  return {
    bleStatus,
    bleDevices,
    selectedBleDevice,
    isScanning,
    scanBle,
    connectBle,
    disconnectBle,
  };
}
