import { Download, X, RefreshCw } from "lucide-react";
import { useUpdater } from "./use-updater";

export function UpdateBanner() {
  const { update, status, dismissed, dismiss, install } = useUpdater();

  if (!update?.available || dismissed) return null;

  const isDownloading = status === "downloading";

  return (
    <div className="flex shrink-0 items-center justify-between gap-3 border-t border-border bg-muted/50 px-4 py-2 text-sm">
      <div className="flex items-center gap-2 text-muted-foreground">
        <Download className="size-3.5 shrink-0" />
        <span>
          Update available
          {update.version ? (
            <span className="ml-1 font-medium text-foreground">
              v{update.version}
            </span>
          ) : null}
        </span>
      </div>

      <div className="flex items-center gap-1">
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
      </div>
    </div>
  );
}
