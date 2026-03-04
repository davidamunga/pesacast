import { useState, useCallback, useEffect } from "react";
import { listen } from "@tauri-apps/api/event";
import { MpesaTransaction } from "./types";

const MAX_TRANSACTIONS = 100;

export function useMpesaTransactions() {
  const [transactions, setTransactions] = useState<MpesaTransaction[]>([]);

  const addTransaction = useCallback((txn: MpesaTransaction) => {
    setTransactions((prev) => [txn, ...prev].slice(0, MAX_TRANSACTIONS));
  }, []);

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
