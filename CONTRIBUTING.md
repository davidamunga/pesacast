# Contributing

## Setup

See [README.md](README.md#prerequisites) for prerequisites, then:

```bash
pnpm install
```

Run the checks listed in [AGENTS.md](AGENTS.md#commands) for every part you changed. The same rules apply to humans and agents.

## Workflow

1. Branch from `main` (`feat/…`, `fix/…`, `docs/…`).
2. Make small, focused commits using Conventional Commits.
3. If the change is user-visible, run `pnpm changeset` and commit the generated file.
4. If you made or reversed a design decision, add an ADR (below).
5. Open a PR. Describe what changed, what you verified, and what you did not.

## Architecture decision records

ADRs record *why* the code is the way it is. Copy [docs/adr/0000-template.md](docs/adr/0000-template.md) to the next free number and fill it in. Keep it to one page. Statuses: `Proposed`, `Accepted`, `Superseded by ADR-NNNN`, `Rejected`. Never rewrite an accepted ADR's decision; supersede it.

## Wire-format changes

The Android and desktop apps must stay compatible with each other across releases, because users update them separately. Update [docs/protocol.md](docs/protocol.md) and prefer additive changes (new optional fields).
