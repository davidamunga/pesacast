# ADR-0007: Vendor and patch `btleplug`

- **Status:** Accepted
- **Date:** 2026-10-24 (records the state since the initial commit; rationale reconstructed the same day)

## Context

`desktop/src-tauri/Cargo.toml` replaces the crates.io `btleplug` with `vendor/btleplug` (version 0.11.8, used by `tauri-plugin-blec` 0.8) through `[patch.crates-io]`. The commit that introduced it did not say why. Diffing the vendored tree against the published 0.11.8 crate shows one functional change:

- `src/corebluetooth/internal.rs`: where upstream does `panic!("We should still have a future at this point!")` when CoreBluetooth reports service or characteristic discovery for a peripheral whose connection future was already resolved, the vendored copy returns early. The in-code comment says CoreBluetooth can deliver those delegate callbacks more than once. On macOS, upstream 0.11.8 would therefore crash the whole desktop app on a duplicate callback.

Two other differences are compiler-lint fixes only (an elided lifetime in `common/adapter_manager.rs`, an unnecessary parenthesis in `corebluetooth/central_delegate.rs`) and change no behaviour.

## Decision

Keep the vendored copy so the macOS panic fix stays in place, and record every local change here. Do not edit it for other reasons without adding to this list.

## Alternatives considered

- **Upstream 0.11.8 unpatched** — crashes on the duplicate callback.
- **Upgrade `tauri-plugin-blec` and `btleplug`** — `btleplug` 0.13.5 and `tauri-plugin-blec` 0.18.0 are current on crates.io, and the published 0.13.5 source no longer contains the panic string (checked by searching the source, not by running it). This is the preferred long-term fix, but it is a multi-version jump across APIs we use and needs testing on all three desktop platforms.
- **Fork as a git dependency** — makes the patch visible and rebase-able, but still carries a fork.

## Consequences

Upstream fixes do not arrive automatically, and the vendored tree adds noise to diffs and searches. Follow-up: try upgrading `tauri-plugin-blec`/`btleplug`, confirm the duplicate-callback crash cannot be reproduced, and delete `vendor/btleplug` and the `[patch.crates-io]` entry. Until then the two lint-only edits can be dropped if the vendored copy is ever refreshed from upstream.
