# iris-service

**Iris — the constellation ↔ Obsidian vault bridge.** The rainbow bridge between realms: a single
safe-writer service that reads/writes Calvin's Obsidian vault and exposes it to the constellation,
so the vault-linked apps (Mnemosyne genealogy, Tyche SWGOH, Plutus budgeting) consume one bridge
instead of each reaching into the notes.

> **Canonical design lives in the codex repo: `~/Code/codex/docs/obsidian-bridge.md`.** Read it
> first — this README is the project seed; that doc is the source of truth.

## The one rule that governs everything

**The vault is Calvin's source of truth; Obsidian is the primary editor; the bridge must never
corrupt or silently overwrite a note.** A budgeting bug loses a number; a bridge bug shreds years
of personal knowledge. So Iris is conservative by construction:

- **Preserve everything it doesn't manage** — unknown frontmatter, body prose, formatting survive
  a write untouched. Apps write only *their* frontmatter keys / a fenced managed section, never a
  wholesale note rewrite.
- **Optimistic concurrency** — every read carries a content hash; a write that finds the note
  changed since read (Calvin edited it in Obsidian) **refuses and surfaces a conflict**. No
  last-writer-wins.
- **Single writer** — one Iris process serializes all writes.
- **Recoverable** — before any write, snapshot the note to **Apollo** (content-addressed,
  path+hash+timestamp). A vault time machine; replaces git history (we don't use git — see below).

## Locked decisions (Calvin 2026-08-26)

- **Name: Iris.** (Notifications, once floated as Iris, → Hera's alerting.)
- **Vault sync: Obsidian Sync, NOT git.** So the safety net is Apollo snapshots + the content-hash
  lock + Sync's own `.sync-conflict` copies as last resort.
- **Vault-access variant: DEFERRED to build-start.** Obsidian Sync has no server API / headless
  mode, so a client must land the files where the cluster sees them — either **Variant 1** (an
  always-on Obsidian client keeps the vault on a mimir share the cluster mounts) or **Variant 2**
  (a local sync-agent mirrors the vault to the constellation and applies writes back). Settle
  before phase-1 implementation; doesn't block seeding.

## Architecture (see the design doc)

- **Single-writer service.** Parses vault markdown + frontmatter + `[[links]]` + `#tags` →
  structured model. Indexes into **Postgres** (a rebuildable read model; vault stays truth).
- **Messaging — lean Hermes.** `vault.note.changed/.created/.deleted` on **Hermes** for async
  fan-out (consumers subscribe + react); **REST** only for synchronous reads/writes a caller
  awaits (a UI rendering a note). Blocked-and-waiting → REST; reaction/pipeline/fan-out → Hermes.
- **Blobs → Apollo** (document scans referenced from notes; also the pre-write snapshots).

## Phases

1. **Read-only** — access + parse + Postgres index + query API + `vault.note.*` on Hermes.
   Immediately useful; *cannot* harm the vault. Proves the access shape.
2. **Safe writes** — managed-section + frontmatter-key patches, optimistic-lock, Apollo snapshot
   per write, conflict surfacing.
3. **First consumer** — Mnemosyne builds on the proven bridge.

## Constellation conventions

Owned/coordinated per `codex/docs/session-coordination.md`. Deploys are the Codex session's
(pin-first, mirrored-values helm). Greek name = software (Norse = physical devices). Mark:
`codex/docs/brand/iris.png` (prismatic — pending Calvin's visual sign-off on the keyed marks).
