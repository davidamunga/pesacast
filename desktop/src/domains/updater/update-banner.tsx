import { Download, X, RefreshCw, Check, AlertCircle } from "lucide-react";
import { useUpdater } from "./use-updater";

export function UpdateBanner() {
  const { update, status, error, dismissed, dismiss, checkForUpdates, install } =
    useUpdater();


  // Hide when an update was found but user dismissed it
  if (status === "available" && dismissed) return null;

  const isDownloading = status === "downloading";

  return (
    <div className="flex items-center ml-2 justify-center gap-3">
      {/* Left: status label */}
      <div className="flex items-center gap-2 text-muted-foreground">
        {status === "available" && (
          <>
            <Download className="size-3.5 shrink-0" />
            <span>
              Update available
              {update?.version && (
                <span className="ml-1 font-medium text-foreground">
                  v{update.version}
                </span>
              )}
            </span>
          </>
        )}

        {status === "checking" && (
          <>
            <RefreshCw className="size-3.5 animate-spin shrink-0" />
            <span>Checking for updates…</span>
          </>
        )}

        {status === "up-to-date" && (
          <>
            <Check className="size-3.5 shrink-0 text-green-500" />
            <span>You're up to date</span>
          </>
        )}

        {status === "error" && (
          <>
            <AlertCircle className="size-3.5 shrink-0 text-destructive" />
            <span
              className="text-destructive max-w-[200px] truncate"
              title={error?.message}
            >
              {error?.message ?? "Update check failed"}
            </span>
          </>
        )}

        {status === "idle" && (
          <span className="opacity-0 select-none">·</span>
        )}
      </div>

      {/* Right: actions */}
      <div className="flex items-center gap-1">
        {status === "available" && (
          <>
            <button
              onClick={install}
              disabled={isDownloading}
              className="flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium transition-colors hover:bg-accent disabled:opacity-50"
            >
              {isDownloading ? (
                <>
                  <RefreshCw className="size-3 animate-spin" />
                  Installing…
                </>
              ) : (
                "Install & Restart"
              )}
            </button>
            {!isDownloading && (
              <button
                onClick={dismiss}
                className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                aria-label="Dismiss"
              >
                <X className="size-3.5" />
              </button>
            )}
          </>
        )}

        {(status === "idle" ||
          status === "up-to-date" ||
          status === "error") && (
          <button
            onClick={checkForUpdates}
            className="flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <RefreshCw className="size-3" />
            Check for updates
          </button>
        )}
      </div>
    </div>
  );
}
