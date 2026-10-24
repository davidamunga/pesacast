export type TransportStatus = "disconnected" | "connecting" | "connected" | "error";

export interface DiscoveredDevice {
  name: string;
  ip: string;
  port: number;
}
