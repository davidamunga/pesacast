import { Badge } from "@/components/ui/badge";
import { TransportStatus } from "@/domains/connection/types";

interface StatusBadgeProps {
  status: TransportStatus;
  label: string;
}

export function StatusBadge({ status, label }: StatusBadgeProps) {
  const variant =
    status === "connected"
      ? "success"
      : status === "connecting"
        ? "warning"
        : status === "error"
          ? "error"
          : "secondary";
  return <Badge variant={variant}>{label}</Badge>;
}
