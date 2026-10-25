# AGENTS.md

PesaCast mirrors M-PESA SMS transactions from an Android phone to a desktop app.
Read [CONTEXT.md](CONTEXT.md) for vocabulary and [docs/architecture.md](docs/architecture.md) for the data flow.

## Layout

| Path | What | Stack |
|---|---|---|
| `android/` | Intercepts SMS, parses, serves transactions to the desktop | Kotlin, BLE GATT server |
| `desktop/` | Receives, notifies, stores, re-broadcasts | Tauri 2 (Rust) + React/TypeScript |
| `docs/adr/` | Architecture decision records | Markdown |
| `site/` | The public website (Vite multi-page, no scripts in the output; build, then `python3 site/check.py`) | HTML, CSS, Vite |
| `.changeset/` | Pending version bumps | Changesets |

## Commands

```bash
pnpm install                                   # repo root
cd desktop && pnpm exec tsc --noEmit           # type-check frontend
cd desktop && pnpm test                        # frontend unit tests (vitest)
cd desktop/src-tauri && cargo test             # Rust tests
cd desktop/src-tauri && cargo check            # fast compile check
cd android && ./gradlew :app:compileDebugKotlin   # compile Android
pnpm lint                                      # eslint + tsc + rustfmt + clippy
cd android && ./gradlew :app:lintDebug         # Android lint
cd android && ./gradlew :app:testDebugUnitTest # Android unit tests (parser, BLE framing, WiFi auth)
cd desktop && pnpm tauri dev                   # run desktop app
pnpm test:scripts                              # tests for scripts/setup-phone.sh (fake adb and curl, no phone)
```

Run the commands for every side you touched before saying work is done. Say plainly what you did not run.

## Conventions

- Branch off `main`; never commit to `main` directly. One concern per commit.
- [Conventional Commits](https://www.conventionalcommits.org/), enforced by commitlint (local `commit-msg` hook and CI, which also checks the PR title): `feat(android): …`, `fix(desktop): …`, `docs: …`. Allowed scopes: `android`, `desktop`, `ble`, `wifi`, `docs`, `ci`, `deps`, `release`. Subject in lower case, header ≤ 100 chars.
- User-visible change → add a changeset (`pnpm changeset`). Versions are synced to Android and Cargo by `scripts/sync-android-version.mjs`; do not edit version numbers by hand.
- Parser changes: add the real SMS body (with names/numbers anonymised) to `MpesaParserTest` first.
- User-facing text on Android goes in `strings.xml`, colors in `colors.xml`. No hardcoded literals in Kotlin.
- A change that alters a decision, a trust boundary, or the wire format needs an ADR (see below) and an update to [docs/protocol.md](docs/protocol.md) when the format changes.

## Rules that are easy to break

- **All transports feed one pipeline.** Desktop transactions go through `desktop/src-tauri/src/ingest.rs` (parse → dedupe → notify → emit → broadcast). Do not add a second path that skips it.
- **The wire format is a contract between two codebases.** Change `MpesaTransaction` on both sides together (`android/.../model/`, `desktop/src-tauri/src/model.rs`, `desktop/src/domains/transactions/types.ts`).
- **Never log or display pairing tokens or SMS bodies** beyond what the user explicitly copies/shares.
- **Do not edit `desktop/src-tauri/vendor/`** without an ADR update; see [ADR-0007](docs/adr/0007-vendor-btleplug.md).
- **Android storage and permissions** ([ADR-0014](docs/adr/0014-android-hardening.md)): keep `allowBackup="false"`; store secrets only through `KeystoreSecretCipher`; log sizes, never SMS text, tokens, secrets or payloads (a test enforces it); declare only permissions that are used, and never make an optional permission (Bluetooth, notifications) block the service.
- Security-sensitive defaults stay conservative: local broadcast server is off by default and refuses browser origins.

## Pull requests

Fill in every section of `.github/pull_request_template.md`; do not replace it with a free-form body. Tick only checks you actually ran, and list what you did not verify under Risks / follow-ups.

## ADRs

Decisions live in `docs/adr/NNNN-title.md` using [the template](docs/adr/0000-template.md). ADRs are immutable once accepted: to change one, add a new ADR that supersedes it and update the old one's status. Add an ADR when you pick between real alternatives, accept a trade-off a future reader would question, or reverse an earlier ADR.
