# ADR-0004: Deduplicate transactions in the desktop ingest pipeline

- **Status:** Proposed (implemented in PR #12)
- **Date:** 2026-10-24

## Context

BLE and WiFi can run at the same time, so one transaction can arrive twice. The database already ignores duplicates on `(ref, time)`, but notifications, the frontend list, webhook auto-push and the local broadcast did not.

## Decision

All transports call a single Rust function, `ingest::ingest`, which keeps the last 200 `(ref, time)` pairs in memory and drops repeats before doing anything else.

## Alternatives considered

- **Dedupe in the frontend** — the notification and broadcast happen in Rust, before the frontend.
- **Dedupe on the phone** — the phone cannot know which transports the desktop is listening on.

## Consequences

One place to reason about delivery. A repeat after 200 other transactions would be processed again (the database still ignores it). Any new transport must call `ingest`.
