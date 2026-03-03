export function formatAmount(currency: string, amount: number) {
  return `${currency} ${amount.toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

export function formatTime(iso: string) {
  try {
    return new Intl.DateTimeFormat(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

export function directionLabel(d: string) {
  switch (d) {
    case "received":  return "Received";
    case "sent":      return "Sent";
    case "paid":      return "Paid";
    case "withdrawn": return "Withdrawn";
    case "airtime":   return "Airtime";
    default:          return d;
  }
}
