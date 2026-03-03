import { Bluetooth, BluetoothConnected, BluetoothOff } from "lucide-react";
import { BleDevice } from "@mnlphlp/plugin-blec";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardPanel,
  CardAction,
} from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { StatusBadge } from "../../components/ui/status-badge";
import { TransportStatus } from "./types";

interface BluetoothSectionProps {
  bleStatus: TransportStatus;
  bleDevices: BleDevice[];
  selectedBleDevice: string | null;
  isScanning: boolean;
  scanBle: () => Promise<void>;
  connectBle: (address: string) => Promise<void>;
  disconnectBle: () => Promise<void>;
}

export function BluetoothSection({
  bleStatus,
  bleDevices,
  selectedBleDevice,
  isScanning,
  scanBle,
  connectBle,
  disconnectBle,
}: BluetoothSectionProps) {
  return (
    <ScrollArea className="h-full">
      <div className="flex flex-col gap-4 pb-4">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              {bleStatus === "connected" ? (
                <BluetoothConnected className="size-4 opacity-70" />
              ) : bleStatus === "error" ? (
                <BluetoothOff className="size-4 opacity-70" />
              ) : (
                <Bluetooth className="size-4 opacity-70" />
              )}
              Bluetooth LE
            </CardTitle>
            <CardDescription>
              Open PesaCast on Android, go to Settings{" "}
              <span className="mx-0.5">→</span> Transport, choose{" "}
              <strong>BLE</strong>. Then scan here to find your phone.
            </CardDescription>
            <CardAction>
              <StatusBadge status={bleStatus} label={bleStatus} />
            </CardAction>
          </CardHeader>
          <CardPanel className="flex flex-col gap-3 pt-0">
            {bleStatus === "connected" ? (
              <Button variant="destructive" onClick={disconnectBle}>
                Disconnect
              </Button>
            ) : (
              <Button variant="default" onClick={scanBle} disabled={isScanning}>
                {isScanning ? "Scanning…" : "Scan for Devices"}
              </Button>
            )}

            {bleDevices.length > 0 && bleStatus !== "connected" && (
              <ul className="flex flex-col gap-1.5">
                {bleDevices.map((d) => (
                  <li
                    key={d.address}
                    className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/40 px-3 py-2"
                  >
                    <div className="flex min-w-0 flex-col">
                      <span className="truncate text-sm font-medium">
                        {d.name || "Unknown Device"}
                      </span>
                      <span className="font-mono text-xs text-muted-foreground">
                        {d.address}
                      </span>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => connectBle(d.address)}
                      disabled={bleStatus === "connecting"}
                    >
                      {selectedBleDevice === d.address &&
                      bleStatus === "connecting"
                        ? "Connecting…"
                        : "Connect"}
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            <p className="text-xs text-muted-foreground">
              BLE works within ~10m without a shared network. Android must have
              BLE peripheral mode enabled.
            </p>
          </CardPanel>
        </Card>
      </div>
    </ScrollArea>
  );
}
