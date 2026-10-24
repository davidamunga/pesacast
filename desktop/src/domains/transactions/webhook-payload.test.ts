// @vitest-environment happy-dom
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  DEVICE_ID_STORAGE_KEY,
  WEBHOOK_SCHEMA_VERSION,
  buildWebhookBody,
  buildWebhookHeaders,
  getDeviceId,
  idempotencyKey,
} from "./webhook-payload";
import type { MpesaTransaction } from "./types";

// Shared with the Android app and the published docs.
const schema = JSON.parse(
  readFileSync(resolve(__dirname, "../../../../docs/webhook-payload.schema.json"), "utf8"),
) as {
  required: string[];
  properties: Record<string, { const?: number; enum?: string[]; maxLength?: number }>;
  $defs: { transaction: { required: string[]; properties: Record<string, unknown> } };
};

const txn: MpesaTransaction = {
  type: "mpesa_txn",
  direction: "received",
  amount: 500,
  currency: "KES",
  from: "JANE DOE",
  ref: "ABC123XYZ",
  time: "2026-10-24T10:00:00+03:00",
  balance: 1500,
};

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  };
}

describe("device id", () => {
  it("is created once and then reused", () => {
    const storage = memoryStorage();
    const first = getDeviceId(storage);
    expect(first).toMatch(/^[0-9a-f-]{36}$/);
    expect(storage.getItem(DEVICE_ID_STORAGE_KEY)).toBe(first);
    expect(getDeviceId(storage)).toBe(first);
  });

  it("keeps an existing id", () => {
    expect(getDeviceId(memoryStorage({ [DEVICE_ID_STORAGE_KEY]: "abc" }))).toBe("abc");
  });
});

describe("webhook body", () => {
  it("conforms to the published schema", () => {
    const body = buildWebhookBody([txn], "dev-1", new Date("2026-10-24T10:00:01.234Z"));
    for (const key of schema.required) expect(body).toHaveProperty(key);
    for (const key of Object.keys(body)) expect(schema.properties).toHaveProperty(key);
    expect(body.schema_version).toBe(schema.properties.schema_version.const);
    expect(schema.properties.source.enum).toContain(body.source);
    expect(body.device_id.length).toBeLessThanOrEqual(schema.properties.device_id.maxLength!);
    expect(body.count).toBe(body.transactions.length);
    for (const key of schema.$defs.transaction.required) expect(body.transactions[0]).toHaveProperty(key);
  });

  it("identifies itself as the desktop and carries the schema version and device", () => {
    const body = buildWebhookBody([txn], "dev-1", new Date("2026-10-24T10:00:01.234Z"));
    expect(body).toMatchObject({
      schema_version: WEBHOOK_SCHEMA_VERSION,
      source: "desktop",
      device_id: "dev-1",
      exported_at: "2026-10-24T10:00:01.234Z",
      count: 1,
    });
  });

  it("counts a batch", () => {
    expect(buildWebhookBody([txn, { ...txn, ref: "B" }], "d").count).toBe(2);
    expect(buildWebhookBody([], "d").count).toBe(0);
  });
});

describe("webhook headers", () => {
  it("uses the same idempotency key format as the phone, for a single transaction", () => {
    expect(idempotencyKey(txn)).toBe("ABC123XYZ@2026-10-24T10:00:00+03:00");
    const headers = buildWebhookHeaders("tok", "dev-1", [txn]);
    expect(headers["Idempotency-Key"]).toBe("ABC123XYZ@2026-10-24T10:00:00+03:00");
    expect(headers["X-PesaCast-Device-Id"]).toBe("dev-1");
    expect(headers["Authorization"]).toBe("Bearer tok");
    expect(headers["Content-Type"]).toBe("application/json");
  });

  it("sends no idempotency key for a batch, and no authorization without a token", () => {
    const batch = buildWebhookHeaders("  ", "dev-1", [txn, { ...txn, ref: "B" }]);
    expect(batch).not.toHaveProperty("Idempotency-Key");
    expect(batch).not.toHaveProperty("Authorization");
    expect(buildWebhookHeaders(undefined, "d", [])).not.toHaveProperty("Idempotency-Key");
  });
});
