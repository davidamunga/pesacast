# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

PesaCast spans two codebases (Android, Tauri desktop) that must stay compatible, and several design choices (transports, trust boundaries, vendored code) are not obvious from the code. Contributors and coding agents repeatedly need the reasoning, not just the result.

## Decision

Record significant decisions as short, immutable ADRs in `docs/adr/`, using [the template](0000-template.md). Supersede instead of editing. Agents are pointed at them from `AGENTS.md`.

## Alternatives considered

- **Long-form wiki or design docs** — drift from the code and are not versioned with it.
- **Commit messages and PR descriptions only** — hard to find later, no status.

## Consequences

Small ongoing cost: a PR that changes a decision must add or supersede an ADR. In return, decisions are reviewable and discoverable.
