# ADR-0009: Enforce Conventional Commits and lint gates

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Changesets, the release workflow and readers of the history all benefit from consistent commit messages. Lint rules existed only as habit; the frontend had no ESLint, and the `tsc` step in `pnpm build` checks nothing because `tsconfig.json` only holds project references.

## Decision

- commitlint with `@commitlint/config-conventional` and a fixed scope list, run by a Husky `commit-msg` hook and by CI on every PR commit and the PR title.
- ESLint (typescript-eslint, react-hooks) and `tsc --noEmit -p tsconfig.app.json` for the desktop frontend; `cargo fmt --check` and `cargo clippy -D warnings` for Rust; Android `lintDebug` plus a compile.
- One CI workflow (`.github/workflows/ci.yml`) runs all of the above.

## Alternatives considered

- **Local hooks only** — bypassable with `--no-verify`.
- **Pre-commit lint hook** — slow on a three-language repo; CI is the gate instead.
- **ktlint/detekt** — not added yet; Android lint is enough for now.

## Consequences

Contributors must use the scope list (extend it in `commitlint.config.mjs` when a new area appears). The Android lint check `PermissionImpliesUnsupportedChromeOsHardware` is disabled on purpose because PesaCast requires telephony. `src/components/ui` (generated shadcn files) is excluded from ESLint.
