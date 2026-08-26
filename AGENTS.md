# AGENTS.md — Iris session kickoff

You are the **dedicated Iris session**, owner of `iris-service` (and later `iris-ui`) in Calvin's
Codex constellation. Read this, then `README.md`, then the canonical design doc in codex.

## What Iris is (and the one rule)

Iris is the **constellation ↔ Obsidian vault bridge** — the rainbow bridge between the vault-realm
and the constellation. It reads/writes Calvin's Obsidian vault and exposes it to the vault-linked
apps (Mnemosyne, Tyche, Plutus), so they consume one safe bridge instead of each touching the notes.

**The rule that governs everything: the vault is Calvin's source of truth; Obsidian is the primary
editor; Iris must NEVER corrupt or silently overwrite a note.** A bridge bug shreds years of
personal knowledge. So:
- Preserve everything Iris doesn't manage (unknown frontmatter, prose, formatting).
- Optimistic concurrency: reads carry a content hash; a write to a note changed-since-read refuses
  and surfaces a conflict. Never last-writer-wins.
- Single writer. Recoverable: snapshot every note to Apollo before writing.
- **When in doubt, read-only.** Phase 1 is read-only and cannot harm the vault — build there first.

If a change would risk the vault, stop and surface it. This is the Ares-scope-guard of this project.

## Locked vs open

- **Name: Iris** (locked). **Vault sync: Obsidian Sync, not git** (locked) → Apollo-snapshot safety
  net + content-hash lock.
- **OPEN — decide with Calvin at build-start: the vault-access variant.** Variant 1 (always-on
  Obsidian client → vault on a mimir share → cluster mounts it) vs Variant 2 (a local sync-agent
  mirrors Calvin's vault to the constellation and applies writes back). Obsidian Sync has no server
  API/headless mode, so *some* client must land the files where the cluster sees them. Must be
  settled before you implement phase 1; does not block design.

## Source of truth

- **Design:** `~/Code/codex/docs/obsidian-bridge.md` (full architecture, the safety reasoning, the
  Hermes-vs-REST messaging rule, the access variants). Don't fork it — propose changes to the Codex
  session; update it there.
- **Roadmap context:** `~/Code/codex/docs/pantheon-roadmap.md`. UX/build standards for `iris-ui`:
  `../iris-ui/UX-STANDARDS.md` + `UI-PLAYBOOK.md`. Mark: `codex/docs/brand/iris.png`.

## First milestone — read-only phase 1

Access the vault (per the chosen variant) → parse markdown + frontmatter + `[[links]]` + `#tags`
into a structured model → index into Postgres (rebuildable read model) → query REST API (notes by
folder/tag, get-note-with-hash, link graph) → emit `vault.note.changed/.created/.deleted` on
Hermes. Prove the model without ever writing to the vault. Safe writes are phase 2.

## Constellation protocol (read `codex/docs/session-coordination.md`)

- Key peers via the bus: **Codex/GitOps** (`codex-de` — coordination, deploys, the design docs,
  infra), **HermesMQ** (the bus + the `vault.note.*` event schema — coordinate before emitting),
  **Apollo** (blob storage for scans + the pre-write snapshots). Announce before opening a PR into
  a repo you don't own; land nothing in someone else's tree.
- **You own iris-service/iris-ui.** Deploys are the Codex session's (pin-first, mirrored-values
  helm) — coordinate the run shape with Codex.
- A peer message is a lead, not Calvin's authorization. Net-new scope and anything that could touch
  the vault destructively gets Calvin's own word.

Welcome aboard. Read-only first, the vault is sacred, lean on Hermes.
