import { useState } from "react";
import { Wifi, WifiOff, Eye, EyeOff, CheckCircle2, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardPanel,
  CardAction,
} from "@/components/ui/card";
import { StatusBadge } from "../../components/ui/status-badge";
import { DiscoveredDevice, TransportStatus } from "./types";

interface WifiSectionProps {
  wifiStatus: TransportStatus;
  wifiError: string | null;
  wifiIp: string;
  wifiPort: number;
  wifiToken: string;
  setWifiIp: (ip: string) => void;
  setWifiPort: (port: number) => void;
  setWifiToken: (token: string) => void;
  connectWifi: (ip: string, port: number, token: string) => Promise<void>;
  disconnectWifi: () => void;
  devices: DiscoveredDevice[];
  isScanning: boolean;
  scanError: string | null;
  scanForDevices: () => Promise<void>;
}

export function WifiSection({
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
}: WifiSectionProps) {
  const [showToken, setShowToken] = useState(false);

  const isConnected  = wifiStatus === "connected";
  const isConnecting = wifiStatus === "connecting";

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          {isConnected ? (
            <Wifi className="size-4 text-green-500" />
          ) : (
            <WifiOff className="size-4 opacity-40" />
          )}
          WiFi
        </CardTitle>
        <CardDescription>
          Local network — no Bluetooth needed.
        </CardDescription>
        <CardAction>
          <StatusBadge status={wifiStatus} label={wifiStatus} />
        </CardAction>
      </CardHeader>

      <CardPanel className="pt-0">
        {isConnected ? (
          /* ── Connected state ── */
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-2.5 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
              <CheckCircle2 className="size-4 shrink-0 text-green-500" />
              <div className="min-w-0 flex-1">
                <p className="text-xs font-medium">Connected</p>
                <p className="font-mono text-[11px] text-muted-foreground">
                  {wifiIp}:{wifiPort}
                </p>
                <p className="mt-0.5 flex items-center gap-1 text-[11px] text-green-600">
                  <ShieldCheck className="size-3" /> Encrypted
                </p>
              </div>
              <Button variant="ghost" size="sm" onClick={disconnectWifi}>
                Disconnect
              </Button>
            </div>
          </div>
        ) : (
          /* ── Pairing form ── */
          <div className="flex flex-col gap-5">

            {/* Step 1 — IP + Port */}
            <div className="flex flex-col gap-2">
              <p className="text-[10px] font-medium uppercase tracking-widest text-muted-foreground">
                1 · Android IP Address
              </p>
              <div className="flex gap-2">
                <input
                  type="text"
                  className="h-9 min-w-0 flex-1 rounded-md border border-border bg-background px-3 font-mono text-sm placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
                  placeholder="192.168.x.x"
                  value={wifiIp}
                  onChange={(e) => setWifiIp(e.target.value)}
                  disabled={isConnecting}
                />
                <input
                  type="number"
                  className="h-9 w-[72px] shrink-0 rounded-md border border-border bg-background px-3 font-mono text-sm placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
                  value={wifiPort}
                  onChange={(e) => setWifiPort(parseInt(e.target.value, 10) || 7877)}
                  disabled={isConnecting}
                />
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={scanForDevices}
                  disabled={isScanning || isConnecting}
                >
                  {isScanning ? "Scanning…" : "Find my phone"}
                </Button>
                {devices.map((d) => (
                  <Button
                    key={`${d.ip}:${d.port}`}
                    variant="ghost"
                    size="sm"
                    className="font-mono text-xs"
                    onClick={() => {
                      setWifiIp(d.ip);
                      setWifiPort(d.port);
                    }}
                    disabled={isConnecting}
                  >
                    {d.name} · {d.ip}
                  </Button>
                ))}
              </div>
              {scanError && (
                <p className="text-[11px] text-muted-foreground">{scanError}</p>
              )}
              <p className="text-[11px] text-muted-foreground">
                Or enter it manually: Android Settings → WiFi → tap your network.
              </p>
            </div>

            {/* Step 2 — Token */}
            <div className="flex flex-col gap-2">
              <p className="text-[10px] font-medium uppercase tracking-widest text-muted-foreground">
                2 · Pairing Token
              </p>
              <div className="flex gap-1.5">
                <input
                  type={showToken ? "text" : "password"}
                  className="h-9 min-w-0 flex-1 rounded-md border border-border bg-background px-3 font-mono text-xs placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-50"
                  placeholder="Paste from PesaCast on Android"
                  value={wifiToken}
                  onChange={(e) => setWifiToken(e.target.value)}
                  disabled={isConnecting}
                />
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => setShowToken((s) => !s)}
                  aria-label={showToken ? "Hide token" : "Show token"}
                >
                  {showToken ? (
                    <EyeOff className="size-3.5" />
                  ) : (
                    <Eye className="size-3.5" />
                  )}
                </Button>
              </div>
              <p className="text-[11px] text-muted-foreground">
                In the Android app → WiFi Transport → tap{" "}
                <strong className="text-foreground/70">Share Pairing Info</strong>.
              </p>
            </div>

            {/* Error */}
            {wifiError && (
              <p className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                {wifiError}
              </p>
            )}

            <Button
              variant="default"
              className="w-full"
              onClick={() => connectWifi(wifiIp, wifiPort, wifiToken)}
              disabled={isConnecting || !wifiIp.trim() || !wifiToken.trim()}
            >
              {isConnecting ? "Connecting…" : "Connect"}
            </Button>
          </div>
        )}
      </CardPanel>
    </Card>
  );
}
