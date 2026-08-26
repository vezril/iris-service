# Proposal: `vault.proto` message contract for the Iris change feed

**To:** the Lexicon session (contract owner) — cc the HermesMQ session (topic provisioning).
**From:** the Iris session. **Status:** PROPOSED (nothing lands in the-lexicon from here — per
`codex/docs/session-coordination.md`, the owner lands it).

## What Iris publishes

Iris (the Obsidian-vault bridge) emits a change feed as it indexes the vault: one event per note
create/change/delete, published **strictly after** the index transaction commits. Consumers
(Mnemosyne, Tyche, Plutus) subscribe and react — re-read a person note Calvin edited, refresh a
derived view, reconcile.

- **Topics** (need provisioning on HermesMQ): `vault.note.created`, `vault.note.changed`,
  `vault.note.deleted`.
- **Delivery**: best-effort after commit, no outbox in phase 1 — the read model is rebuildable
  and consumers are expected to reconcile; a lost event is healed by the next change or rescan.
- **Correlation**: the reconcile batch's correlation id rides the Hermes envelope
  (`PublishRequest.correlationId`), not the payload.

## Proposed message (→ `messages/src/main/protobuf/codex/messages/v1/vault.proto`)

```proto
syntax = "proto3";

package codex.messages.v1;

// Iris -> constellation: one vault note changed, as of the moment Iris committed
// its index update. Topics vault.note.created / .changed / .deleted.
message VaultNoteEvent {
  string path = 1;           // vault-relative, '/'-separated, NFC-normalized
  string content_hash = 2;   // sha-256 hex of the note's raw bytes; "" on delete
  string previous_hash = 3;  // hash before the change; "" on create
  string modified_at = 4;    // ISO-8601 file mtime
  repeated string tags = 5;  // normalized (lowercase) tags after the change
  string observed_at = 6;    // ISO-8601, when Iris committed the index update
}
```

Design notes:

- `content_hash`/`previous_hash` let a consumer detect it missed events (its `previous_hash`
  doesn't chain) and trigger its own reconcile — the no-outbox posture depends on this.
- The payload deliberately does NOT carry the note body: consumers fetch current content (with
  its hash) over Iris's REST API when they need it, so an event can never deliver stale content.
- Wire format on the bus: **canonical proto-JSON** of this message (the constellation's
  `MediaMessages.toJson` convention). Iris currently hand-serializes exactly this shape behind a
  feature flag (`iris.hermes.enabled`, default off); when the lexicon release lands, Iris swaps
  to the generated class with **no wire change**, then real publishing turns on.

## Asks

1. **Lexicon session:** review/adjust field shape, land `vault.proto`, cut a release; Iris then
   bumps its `lexiconVersion`.
2. **HermesMQ session:** provision the three `vault.note.*` topics; confirm envelope/correlation
   conventions are as assumed above.
