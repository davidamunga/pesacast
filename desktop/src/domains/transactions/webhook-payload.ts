import { MpesaTransaction } from "./types";

/** Matches docs/webhook-payload.schema.json. Incremented only for incompatible changes. */
export const WEBHOOK_SCHEMA_VERSION = 1;

export const DEVICE_ID_STORAGE_KEY = "pesacast:device-id";

/**
 * A random per-install identifier, created on first use. Not a secret, not derived from the
 * machine or the user; it changes if the app's storage is cleared.
 */
export function getDeviceId(storage: Pick<Storage, "getItem" | "setItem"> = localStorage): string {
  const existing = storage.getItem(DEVICE_ID_STORAGE_KEY);
  if (existing) return existing;
  const fresh = globalThis.crypto.randomUUID();
  storage.setItem(DEVICE_ID_STORAGE_KEY, fresh);
  return fresh;
}

/** The request body for a push: same envelope the phone sends, with `source: "desktop"`. */
export function buildWebhookBody(
  transactions: MpesaTransaction[],
  deviceId: string,
  now: Date = new Date(),
) {
  return {
    schema_version: WEBHOOK_SCHEMA_VERSION,
    source: "desktop" as const,
    device_id: deviceId,
    exported_at: now.toISOString(),
    transactions,
    count: transactions.length,
  };
}

/** `<ref>@<time>`, identical to the phone's key so a receiver can de-duplicate across both. */
export function idempotencyKey(txn: Pick<MpesaTransaction, "ref" | "time">): string {
  return `${txn.ref}@${txn.time}`;
}

/**
 * Headers for a push. A key is only added for a single transaction; a batch has no single
 * identity, and the receiver de-duplicates on each transaction's `ref` and `time`.
 */
export function buildWebhookHeaders(
  token: string | null | undefined,
  deviceId: string,
  transactions: MpesaTransaction[],
): Record<string, string> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-PesaCast-Device-Id": deviceId,
  };
  const trimmed = token?.trim();
  if (trimmed) headers["Authorization"] = `Bearer ${trimmed}`;
  if (transactions.length === 1) headers["Idempotency-Key"] = idempotencyKey(transactions[0]);
  return headers;
}
