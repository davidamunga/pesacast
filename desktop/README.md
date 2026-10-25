# PesaCast desktop

The Mac and Windows app that receives M-PESA transactions from the phone, shows a notification and a live list, stores them in SQLite, and can export to Excel or push to a webhook. Built with Tauri 2 (Rust) and React 19.

The project overview is in the [root README](../README.md); how the pieces fit is in [docs/architecture.md](../docs/architecture.md).

## Run it

From the repository root:

```bash
pnpm install
cd desktop
pnpm tauri dev
```

You need Node 20+, pnpm and a stable Rust toolchain, plus the [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/) for your OS.

## Checks

```bash
pnpm exec tsc --noEmit      # type-check the frontend
pnpm test                   # frontend unit tests (vitest)
cd src-tauri && cargo test  # Rust tests
```

`pnpm lint` at the repository root runs eslint, the type check, rustfmt and clippy.

## Where things are

| Path | What |
|---|---|
| `src/domains/connection` | Bluetooth and WiFi connection logic; hands raw JSON to Rust |
| `src/domains/transactions` | Live list, export, webhook push |
| `src-tauri/src/ingest.rs` | The one pipeline every transaction goes through: parse, dedupe, notify, emit, broadcast |
| `src-tauri/src/db.rs` | SQLite storage, keyed on `(ref, time)` |
| `src-tauri/src/ws_server.rs` | Optional local broadcast server, off by default, refuses browser origins |
| `src-tauri/vendor/` | Vendored and patched `btleplug`; do not edit without an ADR update ([ADR-0007](../docs/adr/0007-vendor-btleplug.md)) |
