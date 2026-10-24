import { useState, useCallback, useEffect } from "react";
import { listen } from "@tauri-apps/api/event";
import { fetch } from "@tauri-apps/plugin-http";
import { MpesaTransaction } from "./types";
import { usePersistence } from "@/domains/persistence/usePersistence";
import {
  WEBHOOK_STORAGE_KEY_URL,
  WEBHOOK_STORAGE_KEY_TOKEN,
  WEBHOOK_STORAGE_KEY_AUTO_PUSH,
} from "./webhook-push";
import { buildWebhookBody, buildWebhookHeaders, getDeviceId } from "./webhook-payload";

const MAX_TRANSACTIONS = 100;

function autoPushTransaction(txn: MpesaTransaction) {
  const enabled = localStorage.getItem(WEBHOOK_STORAGE_KEY_AUTO_PUSH) === "true";
  const url = localStorage.getItem(WEBHOOK_STORAGE_KEY_URL)?.trim();
  if (!enabled || !url) return;

  const token = localStorage.getItem(WEBHOOK_STORAGE_KEY_TOKEN);
  const deviceId = getDeviceId();

  fetch(url, {
    method: "POST",
    headers: buildWebhookHeaders(token, deviceId, [txn]),
    body: JSON.stringify(buildWebhookBody([txn], deviceId)),
  }).catch((err) => {
    console.warn("[pesacast] auto-push failed:", err);
  });
}

export function useMpesaTransactions(onNewTransaction?: (txn: MpesaTransaction) => void) {
  const [transactions, setTransactions] = useState<MpesaTransaction[]>([]);
  const { loadTransactions, saveTransaction } = usePersistence();

  useEffect(() => {
    loadTransactions().then((saved) => {
      if (saved.length > 0) setTransactions(saved);
    });
  }, [loadTransactions]);

  const addTransaction = useCallback((txn: MpesaTransaction) => {
    setTransactions((prev) => [txn, ...prev].slice(0, MAX_TRANSACTIONS));
    saveTransaction(txn);
    autoPushTransaction(txn);
    onNewTransaction?.(txn);
  }, [onNewTransaction, saveTransaction]);

  useEffect(() => {
    let cancelled = false;
    let unlisten: (() => void) | undefined;

    listen<MpesaTransaction>("mpesa-transaction", (event) => {
      addTransaction(event.payload);
    }).then((fn) => {
      if (cancelled) {
        fn();
      } else {
        unlisten = fn;
      }
    });

    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, [addTransaction]);

  return { transactions };
}
