import { check, type Update } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";
import { useState, useEffect, useCallback } from "react";

type Status = "idle" | "available" | "downloading" | "ready" | "error";

export function useUpdater() {
  const [update, setUpdate] = useState<Update | null>(null);
  const [status, setStatus] = useState<Status>("idle");
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    // Delay the check slightly so it doesn't block app startup render
    const t = setTimeout(() => {
      check()
        .then((u) => {
          if (u?.available) {
            setUpdate(u);
            setStatus("available");
          }
        })
        .catch(() => {
          // Silently ignore — no internet, endpoint not yet configured, etc.
        });
    }, 3000);
    return () => clearTimeout(t);
  }, []);

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
    install,
  };
}
