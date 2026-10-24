## What and why
<!-- One or two sentences. Link the issue or ADR if there is one. -->

## Type
<!-- The PR title must be a Conventional Commit, e.g. `feat(wifi): add reconnect backoff`. -->
- [ ] feat
- [ ] fix
- [ ] refactor
- [ ] docs
- [ ] ci / chore

## Areas touched
- [ ] Android
- [ ] Desktop (frontend)
- [ ] Desktop (Rust)
- [ ] Docs / CI

## Verification
<!-- Tick what you ran. Say plainly what you did not run. -->
- [ ] `pnpm lint`
- [ ] `cargo test` (desktop/src-tauri)
- [ ] `./gradlew :app:compileDebugKotlin :app:lintDebug` (android)
- [ ] Tried on a real device / desktop build: <!-- describe -->

## Checklist
- [ ] Changeset added if user-visible (`pnpm changeset`)
- [ ] ADR added or superseded if this changes a decision or trust boundary
- [ ] `docs/protocol.md` updated if the wire format changed (both apps updated together)
- [ ] No tokens, SMS bodies or secrets in logs or screenshots

## Risks / follow-ups
<!-- Known gaps, anything not done, rollout or compatibility concerns. -->
