# ADR-0007: Vendor and patch `btleplug`

- **Status:** Accepted — **rationale to confirm by the maintainer**
- **Date:** 2026-10-24 (records the state since the initial commit)

## Context

`desktop/src-tauri/Cargo.toml` replaces the crates.io `btleplug` with `vendor/btleplug` through `[patch.crates-io]`, used by `tauri-plugin-blec`. The original reason was not written down at the time.

## Decision

Keep the vendored copy and patch it in place. Do not modify it without recording why here.

## Alternatives considered

- **Upstream crates.io version** — not used today.
- **Fork as a git dependency** — would make the patch visible and updatable.

## Consequences

Upstream fixes do not arrive automatically, and the vendored tree adds noise to diffs and searches. `TODO(maintainer)`: replace this section with the real reason, list the local patches, and consider moving to a git fork.
