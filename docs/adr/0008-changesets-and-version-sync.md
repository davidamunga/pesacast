# ADR-0008: Version with Changesets and sync to Android/Cargo

- **Status:** Accepted
- **Date:** 2026-10-24 (records an earlier decision)

## Context

The desktop and Android apps are released together under one version, but have three places that carry it: `desktop/package.json`, `desktop/src-tauri/Cargo.toml` (Tauri reads the app version only from here) and Android `versionName`/`versionCode`.

## Decision

Use Changesets on the `pesacast` package. `pnpm version-packages` runs `changeset version` and then `scripts/sync-android-version.mjs`, which copies the version to Cargo and Android and derives `versionCode = major*10000 + minor*100 + patch`. Releases are tagged `pesacast@x.y.z` and built by `.github/workflows/release.yml`.

## Alternatives considered

- **Manual version bumps** — the three files drift.
- **Independent versions per app** — the apps share a wire format and ship together.

## Consequences

Never edit the version in those files by hand. `versionCode` limits minor and patch to 0–99.
