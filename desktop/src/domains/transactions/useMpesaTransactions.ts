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

const MAX_TRANSACTIONS = 100;

function autoPushTransaction(txn: MpesaTransaction) {
  const enabled = localStorage.getItem(WEBHOOK_STORAGE_KEY_AUTO_PUSH) === "true";
  const url = localStorage.getItem(WEBHOOK_STORAGE_KEY_URL)?.trim();
  if (!enabled || !url) return;

  const token = localStorage.getItem(WEBHOOK_STORAGE_KEY_TOKEN)?.trim();
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;

  fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify({
      transactions: [txn],
      count: 1,
      exported_at: new Date().toISOString(),
    }),
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
