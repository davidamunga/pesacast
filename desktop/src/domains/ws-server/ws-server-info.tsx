import { useState, useEffect } from "react";
import { Plug, Copy, Check, RefreshCw } from "lucide-react";
import { invoke } from "@tauri-apps/api/core";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverTrigger,
  PopoverPopup,
} from "@/components/ui/popover";

const WS_ENABLED_KEY = "pesacast:ws-enabled";
const WS_PORT_KEY = "pesacast:ws-port";
const DEFAULT_PORT = 7878;

type Lang = "python" | "node" | "csharp";

const LANGS: { id: Lang; label: string }[] = [
  { id: "python", label: "Python" },
  { id: "node", label: "Node.js" },
  { id: "csharp", label: "C#" },
];

function buildCode(lang: Lang, url: string): string {
  switch (lang) {
    case "python":
      return `import asyncio, json, websockets

async def main():
    async with websockets.connect("${url}") as ws:
        async for msg in ws:
            txn = json.loads(msg)
            print(txn["direction"], txn["amount"])

asyncio.run(main())`;
    case "node":
      return `const WebSocket = require("ws");

const ws = new WebSocket("${url}");
ws.on("message", (data) => {
  const txn = JSON.parse(data.toString());
  console.log(txn.direction, txn.amount, txn.currency);
});`;
    case "csharp":
      return `using var ws = new ClientWebSocket();
await ws.ConnectAsync(new Uri("${url}"), default);
var buf = new byte[4096];
while (ws.State == WebSocketState.Open) {
    var r = await ws.ReceiveAsync(buf, default);
    Console.WriteLine(Encoding.UTF8.GetString(buf, 0, r.Count));
}`;
  }
}

export function WsServerInfo() {
  const [enabled, setEnabled] = useState(
    () => localStorage.getItem(WS_ENABLED_KEY) === "true",
  );
  const [port, setPort] = useState<number>(() => {
    const stored = localStorage.getItem(WS_PORT_KEY);
    return stored ? parseInt(stored, 10) : DEFAULT_PORT;
  });
  const [draftPort, setDraftPort] = useState<string>(() => {
    const stored = localStorage.getItem(WS_PORT_KEY);
    return stored ?? String(DEFAULT_PORT);
  });
  const [urlCopied, setUrlCopied] = useState(false);
  const [codeCopied, setCodeCopied] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState<"idle" | "ok" | "err">("idle");
  const [toggling, setToggling] = useState(false);
  const [lang, setLang] = useState<Lang>("python");

  const wsUrl = `ws://localhost:${port}`;
  const code = buildCode(lang, wsUrl);
  const draftNum = parseInt(draftPort, 10);
  const portValid = !isNaN(draftNum) && draftNum >= 1024 && draftNum <= 65535;
  const portDirty = draftNum !== port;

  // Restore persisted preferences into Rust on first mount
  useEffect(() => {
    const storedEnabled = localStorage.getItem(WS_ENABLED_KEY) === "true";
    const storedPort = parseInt(localStorage.getItem(WS_PORT_KEY) ?? "", 10);
    const hasCustomPort = !isNaN(storedPort) && storedPort !== DEFAULT_PORT;

    const actions: Promise<unknown>[] = [];
    if (hasCustomPort) actions.push(invoke("set_ws_port", { port: storedPort }));
    if (storedEnabled) actions.push(invoke("set_ws_enabled", { enabled: true }));
    Promise.all(actions).catch(() => {});
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleToggle() {
    const next = !enabled;
    setToggling(true);
    try {
      await invoke("set_ws_enabled", { enabled: next });
      setEnabled(next);
      localStorage.setItem(WS_ENABLED_KEY, String(next));
    } catch {
      // revert — Rust rejected (e.g. port in use)
    } finally {
      setToggling(false);
    }
  }

  function handleCopyUrl() {
    navigator.clipboard.writeText(wsUrl).then(() => {
      setUrlCopied(true);
      setTimeout(() => setUrlCopied(false), 2000);
    });
  }

  function handleCopyCode() {
    navigator.clipboard.writeText(code).then(() => {
      setCodeCopied(true);
      setTimeout(() => setCodeCopied(false), 2000);
    });
  }

  async function handleApplyPort() {
    if (!portValid || !portDirty) return;
    setSaving(true);
    setSaveStatus("idle");
    try {
      await invoke("set_ws_port", { port: draftNum });
      setPort(draftNum);
      localStorage.setItem(WS_PORT_KEY, String(draftNum));
      setSaveStatus("ok");
    } catch {
      setSaveStatus("err");
    } finally {
      setSaving(false);
      setTimeout(() => setSaveStatus("idle"), 2500);
    }
  }

  return (
    <Popover>
      <PopoverTrigger
        render={
          <Button variant="ghost" size="icon-sm" aria-label="WebSocket server">
            <Plug className="size-4" />
          </Button>
        }
      />
      <PopoverPopup side="bottom" align="end" className="w-[352px]">
        <div className="flex flex-col gap-4">

          {/* Header — status dot + title + toggle */}
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <span className="relative flex size-2 shrink-0 mt-0.5">
                {enabled ? (
                  <>
                    <span className="animate-ping absolute inline-flex size-full rounded-full bg-green-400 opacity-75" />
                    <span className="relative inline-flex size-2 rounded-full bg-green-500" />
                  </>
                ) : (
                  <span className="relative inline-flex size-2 rounded-full bg-muted-foreground/30" />
                )}
              </span>
              <div>
                <p className="text-sm font-semibold leading-none">WebSocket Server</p>
                <p className="mt-1 text-[11px] text-muted-foreground">
                  {enabled ? "Broadcasting live transactions" : "Server is off — enable to start"}
                </p>
              </div>
            </div>

            {/* Toggle switch */}
            <button
              role="switch"
              aria-checked={enabled}
              onClick={handleToggle}
              disabled={toggling}
              className={[
                "relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50",
                enabled ? "bg-green-500" : "bg-input",
              ].join(" ")}
            >
              <span
                className={[
                  "pointer-events-none block size-4 rounded-full bg-white shadow-sm ring-0 transition-transform duration-200",
                  enabled ? "translate-x-4" : "translate-x-0",
                ].join(" ")}
              />
            </button>
          </div>

          {/* Dimmed when disabled */}
          <div
            className={[
              "flex flex-col gap-4 transition-opacity duration-200",
              enabled ? "opacity-100" : "opacity-40 pointer-events-none select-none",
            ].join(" ")}
          >
            {/* WS URL row */}
            <div className="flex items-center gap-1 rounded-md border border-border bg-muted/40 pl-3 pr-1 py-1.5">
              <span className="flex-1 truncate font-mono text-xs text-foreground">
                {wsUrl}
              </span>
              <button
                onClick={handleCopyUrl}
                className="flex size-6 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                aria-label="Copy URL"
              >
                {urlCopied
                  ? <Check className="size-3 text-green-500" />
                  : <Copy className="size-3" />}
              </button>
            </div>

            {/* Code examples */}
            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between">
                <p className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
                  Quick connect
                </p>
                <div className="flex rounded-md border border-border overflow-hidden">
                  {LANGS.map(({ id, label }) => (
                    <button
                      key={id}
                      onClick={() => setLang(id)}
                      className={[
                        "px-2.5 py-0.5 text-[11px] font-medium transition-colors",
                        lang === id
                          ? "bg-primary text-primary-foreground"
                          : "bg-transparent text-muted-foreground hover:text-foreground hover:bg-muted/60",
                      ].join(" ")}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div className="group relative rounded-md border border-border bg-muted/50 overflow-hidden">
                <pre className="overflow-x-auto px-3.5 py-3 text-[10.5px] leading-[1.7] text-foreground/80 font-mono">
                  <code>{code}</code>
                </pre>
                <button
                  onClick={handleCopyCode}
                  className="absolute right-2 top-2 flex size-6 items-center justify-center rounded text-muted-foreground opacity-0 transition-all group-hover:opacity-100 hover:bg-muted hover:text-foreground"
                  aria-label="Copy code"
                >
                  {codeCopied
                    ? <Check className="size-3 text-green-500" />
                    : <Copy className="size-3" />}
                </button>
              </div>
            </div>
          </div>

          {/* Port config — always accessible */}
          <div className="flex flex-col gap-2 border-t border-border pt-3">
            <p className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
              Port
            </p>
            <div className="flex items-center gap-2">
              <input
                type="number"
                min={1024}
                max={65535}
                value={draftPort}
                onChange={(e) => setDraftPort((e.target as HTMLInputElement).value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && portValid && portDirty) handleApplyPort();
                }}
                className="h-8 w-[88px] rounded-md border border-input bg-background px-3 font-mono text-xs focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <Button
                size="sm"
                variant={portDirty && portValid ? "default" : "outline"}
                onClick={handleApplyPort}
                disabled={!portDirty || !portValid || saving}
                className="gap-1.5"
              >
                {saving && <RefreshCw className="size-3 animate-spin" />}
                {saveStatus === "ok" && !saving && <Check className="size-3" />}
                {saving ? "Applying…" : saveStatus === "ok" ? "Applied" : "Apply"}
              </Button>
              {saveStatus === "err" && (
                <span className="text-[11px] text-destructive">Port in use?</span>
              )}
            </div>
            {!portValid && draftPort !== "" && (
              <p className="text-[11px] text-destructive">Enter a port between 1024 – 65535</p>
            )}
          </div>

        </div>
      </PopoverPopup>
    </Popover>
  );
}
