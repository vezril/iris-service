# Vault access model — the one-folder rule

**Status: BINDING (Calvin, 2026-08-26).** Applies to every constellation service that reaches the
vault through Iris. Phase 1 is read-only, so nothing enforces this yet; it is the design law that
**phase 2 (safe writes) must implement before a single write ships**.

## The rule

> **Every service writes to exactly one folder: `Atlas/Olympus/<Service>/`. Reads are vault-wide.**

`Dionysus` writes only under `Atlas/Olympus/Dionysus/`. `Mnemosyne` only under
`Atlas/Olympus/Mnemosyne/`. No service may create, modify, or delete anything outside its own
folder — including other services' folders.

Iris creates `Atlas/Olympus/<Service>/` on that service's first write if it does not exist.

## Why writes only, and not reads

The stated goals are **cross-contamination** and **accidental deletes**. Both are exclusively write
concerns: a read cannot corrupt a note or lose data. Confining reads as well would defeat the
bridge's founding purpose — Mnemosyne exists to read Calvin's real person notes under
`Atlas/Notes/People`, and Tyche/Plutus to read his existing strategy and financial notes. A
read-scoped Iris would be shared scratch storage for services, not a window onto the vault.

So: **reads broad, writes narrow.** The blast radius of any service bug is its own folder.

## What this does and does not protect

**Protects:** Calvin's own notes from every service. One service's data from another service.
A buggy delete loop can, at worst, empty `Atlas/Olympus/<ThatService>/`.

**Does not protect:** a service from corrupting *its own* folder. That is accepted — the blast
radius is that service's own derived data, which it can rebuild.

## Enforcement requirements (phase 2)

Non-negotiable properties, each of which must **fail closed**:

1. **Canonical-path check.** Enforce on the NFC-normalized, `..`-rejected `VaultPath` — the same
   type reads already use. `VaultPath.parse` rejects traversal and absolute paths today, so the
   confinement check is a prefix test on an already-canonical value, never on raw caller input.
2. **Resolve before writing.** A symlink inside a service folder pointing elsewhere would escape
   confinement. Resolve the real path and re-check it is still under the service's folder, or
   refuse to write through symlinks at all (simpler, and nothing needs them).
3. **Prefix must be a path-segment boundary.** `Atlas/Olympus/Dionysus2/` must NOT match the
   prefix `Atlas/Olympus/Dionysus` — compare segments, not strings. (A naive `startsWith` is the
   textbook version of this bug.)
4. **Deny by default.** An unknown service, an absent identity, or an unparseable path refuses the
   write. Never fall back to "unscoped".
5. **Enforced in `core/`, not at the HTTP edge.** The check belongs beside the domain types so no
   future call path — a Hermes consumer, a batch job — can reach a writer that skipped it.

## Service identity (Calvin, 2026-08-26: per-service API key)

Each service gets a token, delivered as a k8s Secret and sent as a request header. Iris maps
**token → service name → its one writable folder**. Requirements:

- Tokens live in Secrets, never in chart values or config files.
- The mapping is explicit configuration, not derived from a caller-supplied name — a service must
  not be able to *claim* an identity, only prove one.
- An unrecognized or missing token is a `401`, and for writes that is the deny-by-default path.
- Reads may remain unauthenticated in phase 1 (the API is in-cluster only); **writes may not**.

## Consequences to settle at phase-2 design time

- **Note moves.** Moving a note into or out of a service folder is a write on both sides. Simplest
  safe answer: a service may only move within its own folder.
- **Frontmatter on Calvin's notes.** The original design imagined apps writing "their" frontmatter
  keys into existing notes (e.g. Mnemosyne annotating a person note). **The one-folder rule
  forbids that.** If that capability is still wanted, it needs an explicit, separately-designed
  exception with its own review — it is exactly the cross-contamination this rule exists to stop.
  Until then, services express relationships from inside their own folder (e.g. a note in
  `Atlas/Olympus/Mnemosyne/` linking out to `[[Alcvin Ramos]]`), which costs nothing and keeps
  Calvin's notes untouched.
