import { check, type Update } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";
import { useState, useEffect, useCallback, useRef } from "react";

export type UpdaterStatus =
  | "idle"
  | "checking"
  | "available"
  | "up-to-date"
  | "downloading"
  | "ready"
  | "error";

export function useUpdater() {
  const [update, setUpdate] = useState<Update | null>(null);
  const [status, setStatus] = useState<UpdaterStatus>("idle");
  const [dismissed, setDismissed] = useState(false);
  const upToDateTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const runCheck = useCallback(async () => {
    setStatus("checking");
    try {
      const u = await check();
      if (u?.available) {
        setUpdate(u);
        setStatus("available");
        setDismissed(false);
      } else {
        setStatus("up-to-date");
        upToDateTimer.current = setTimeout(() => setStatus("idle"), 4000);
      }
    } catch {
      setStatus("idle");
    }
  }, []);

  useEffect(() => {
    const t = setTimeout(runCheck, 3000);
    return () => {
      clearTimeout(t);
      if (upToDateTimer.current) clearTimeout(upToDateTimer.current);
    };
  }, [runCheck]);

  const install = useCallback(async () => {
    if (!update) return;
    setStatus("downloading");
    try {
      await update.downloadAndInstall();
      setStatus("ready");
      await relaunch();
    } catch {
      setStatus("error");
    }
  }, [update]);

  return {
    update,
    status,
    dismissed,
    dismiss: () => setDismissed(true),
    checkForUpdates: runCheck,
    install,
  };
}
