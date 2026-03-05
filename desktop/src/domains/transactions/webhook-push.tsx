import { useState, useEffect } from "react";
import { Send, CheckCircle2, AlertCircle, Loader2, Globe } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverTrigger,
  PopoverPopup,
  PopoverTitle,
  PopoverDescription,
} from "@/components/ui/popover";
import { MpesaTransaction } from "./types";

const STORAGE_KEY_URL = "pesacast:webhook-push-url";
const STORAGE_KEY_TOKEN = "pesacast:webhook-push-token";

type PushStatus = "idle" | "loading" | "success" | "error";

interface WebhookPushProps {
  transactions: MpesaTransaction[];
  filteredTransactions: MpesaTransaction[];
}

export function WebhookPush({
  transactions,
  filteredTransactions,
}: WebhookPushProps) {
  const [url, setUrl] = useState(
    () => localStorage.getItem(STORAGE_KEY_URL) ?? "",
  );
  const [token, setToken] = useState(
    () => localStorage.getItem(STORAGE_KEY_TOKEN) ?? "",
  );
  const [scope, setScope] = useState<"filtered" | "all">("filtered");
  const [status, setStatus] = useState<PushStatus>("idle");
  const [errorMessage, setErrorMessage] = useState("");
  const [responseInfo, setResponseInfo] = useState("");

  const isFiltered = filteredTransactions.length !== transactions.length;
  const targetTransactions =
    scope === "all" ? transactions : filteredTransactions;

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY_URL, url);
  }, [url]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY_TOKEN, token);
  }, [token]);

  async function handlePush() {
    const trimmedUrl = url.trim();
    if (!trimmedUrl) return;

    setStatus("loading");
    setErrorMessage("");
    setResponseInfo("");

    const headers: Record<string, string> = {
      "Content-Type": "application/json",
    };
    if (token.trim()) {
      headers["Authorization"] = `Bearer ${token.trim()}`;
    }

    try {
      const res = await fetch(trimmedUrl, {
        method: "POST",
        headers,
        body: JSON.stringify({
          transactions: targetTransactions,
          count: targetTransactions.length,
          exported_at: new Date().toISOString(),
        }),
      });

      if (res.ok) {
        setStatus("success");
        setResponseInfo(`${res.status} ${res.statusText}`);
        setTimeout(() => {
          setStatus("idle");
          setResponseInfo("");
        }, 3000);
      } else {
        setStatus("error");
        setErrorMessage(`${res.status} ${res.statusText}`);
      }
    } catch (err) {
      setStatus("error");
      setErrorMessage(err instanceof Error ? err.message : "Request failed");
    }
  }

  return (
    <Popover>
      <PopoverTrigger
        render={
          <Button variant="outline" size="sm" className="gap-1.5">
            <Globe className="size-3.5" />
            Push to API
          </Button>
        }
      />
      <PopoverPopup side="bottom" align="end" className="w-72">
        <div className="flex flex-col gap-3">
          <div>
            <PopoverTitle>Push to API</PopoverTitle>
            <PopoverDescription className="mt-0.5">
              POST transactions as JSON to an external endpoint
            </PopoverDescription>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="api-url" className="text-xs">
              Endpoint URL
            </Label>
            <Input
              id="api-url"
              placeholder="https://your-api.example.com/transactions"
              value={url}
              onChange={(e) => setUrl((e.target as HTMLInputElement).value)}
              className="text-xs"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="api-token" className="text-xs">
              Bearer token
              <span className="ml-1 font-normal text-muted-foreground">
                (optional)
              </span>
            </Label>
            <Input
              id="api-token"
              type="password"
              placeholder="••••••••••••"
              value={token}
              onChange={(e) => setToken((e.target as HTMLInputElement).value)}
              className="text-xs"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-foreground">
              Push scope
            </span>
            <div className="flex flex-col gap-1">
              <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent/50">
                <input
                  type="radio"
                  name="push-scope"
                  value="filtered"
                  checked={scope === "filtered"}
                  onChange={() => setScope("filtered")}
                  className="accent-primary"
                />
                <span>
                  {isFiltered ? "Filtered" : "All"} transactions
                  <span className="ml-1.5 text-xs text-muted-foreground">
                    ({filteredTransactions.length})
                  </span>
                </span>
              </label>
              {isFiltered && (
                <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-accent/50">
                  <input
                    type="radio"
                    name="push-scope"
                    value="all"
                    checked={scope === "all"}
                    onChange={() => setScope("all")}
                    className="accent-primary"
                  />
                  <span>
                    All transactions
                    <span className="ml-1.5 text-xs text-muted-foreground">
                      ({transactions.length})
                    </span>
                  </span>
                </label>
              )}
            </div>
          </div>

          {status === "error" && (
            <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/8 px-3 py-2">
              <AlertCircle className="mt-0.5 size-3.5 shrink-0 text-destructive" />
              <span className="text-xs text-destructive">{errorMessage}</span>
            </div>
          )}

          {status === "success" && (
            <div className="flex items-center gap-2 rounded-md border border-success/30 bg-success/8 px-3 py-2">
              <CheckCircle2 className="size-3.5 shrink-0 text-success-foreground" />
              <span className="text-xs text-success-foreground">
                Sent — {responseInfo}
              </span>
            </div>
          )}

          <Button
            variant="default"
            size="sm"
            onClick={handlePush}
            disabled={
              !url.trim() ||
              targetTransactions.length === 0 ||
              status === "loading"
            }
          >
            {status === "loading" ? (
              <>
                <Loader2 className="size-3.5 animate-spin" />
                Sending…
              </>
            ) : (
              <>
                <Send className="size-3.5" />
                Push {targetTransactions.length} transaction
                {targetTransactions.length !== 1 ? "s" : ""}
              </>
            )}
          </Button>
        </div>
      </PopoverPopup>
    </Popover>
  );
}
