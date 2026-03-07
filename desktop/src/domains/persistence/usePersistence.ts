import { useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { MpesaTransaction } from "@/domains/transactions/types";

export const PERSIST_STORAGE_KEY_ENABLED = "pesacast:persist-enabled";
export const PERSIST_STORAGE_KEY_PATH = "pesacast:persist-db-path";

export function getPersistenceSettings() {
  return {
    enabled: localStorage.getItem(PERSIST_STORAGE_KEY_ENABLED) === "true",
    dbPath: localStorage.getItem(PERSIST_STORAGE_KEY_PATH) ?? "",
  };
}

export function usePersistence() {
  const initialized = useRef(false);

  const ensureInit = useCallback(async (dbPath: string) => {
    if (initialized.current) return;
    await invoke("db_init", { path: dbPath });
    initialized.current = true;
  }, []);

  const loadTransactions = useCallback(async (): Promise<MpesaTransaction[]> => {
    const { enabled, dbPath } = getPersistenceSettings();
    if (!enabled || !dbPath) return [];
    try {
      await ensureInit(dbPath);
      return await invoke<MpesaTransaction[]>("db_load_transactions", { path: dbPath });
    } catch (err) {
      console.warn("[pesacast] failed to load transactions from db:", err);
      return [];
    }
  }, [ensureInit]);

  const saveTransaction = useCallback(async (txn: MpesaTransaction) => {
    const { enabled, dbPath } = getPersistenceSettings();
    if (!enabled || !dbPath) return;
    try {
      await ensureInit(dbPath);
      await invoke("db_save_transaction", { path: dbPath, txn });
    } catch (err) {
      console.warn("[pesacast] failed to save transaction to db:", err);
    }
  }, [ensureInit]);

  return { loadTransactions, saveTransaction };
}
