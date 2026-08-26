# Proposal: `vault.proto` message contract for the Iris change feed

**To:** the Lexicon session (contract owner) — cc the HermesMQ session (topic provisioning).
**From:** the Iris session. **Status:** PROPOSED (nothing lands in the-lexicon from here — per
`codex/docs/session-coordination.md`, the owner lands it).

## What Iris publishes

Iris (the Obsidian-vault bridge) emits a change feed as it indexes the vault: one event per note
create/change/delete, published **strictly after** the index transaction commits. Consumers
(Mnemosyne, Tyche, Plutus) subscribe and react — re-read a person note Calvin edited, refresh a
derived view, reconcile.

- **Topics**: `vault.note.created`, `vault.note.changed`, `vault.note.deleted` — **Iris creates
  these itself at startup**, idempotently (a duplicate create returns 409, treated as success).
  Per the constellation convention confirmed by the HermesMQ session 2026-08-26: every service
  provisions its own topics (Artemis owns `media.*`, Demeter `demeter-deals`), so a topic is
  owned by its publisher and never becomes untracked cluster state. No ask on HermesMQ here.
- **Delivery**: best-effort after commit, no outbox in phase 1 — the read model is rebuildable
  and consumers are expected to reconcile; a lost event is healed by the next change or rescan.
  Delivery is at-least-once with redelivery, which the `previous_hash` chain is the gap detector
  for.
- **Correlation**: the reconcile batch's correlation id rides the Hermes envelope
  (`PublishRequest.correlation_id` on gRPC / `X-Correlation-Id` on REST), not the payload. The
  broker adopts it verbatim, never mints or overwrites it, journals it with the message so it
  survives replay, and redelivers it verbatim to consumers. Use the Lexicon `CorrelationNames`
  constants rather than string literals.
  **Version caveat (HermesMQ session, 2026-08-26):** the envelope `correlation_id` field landed
  in hermesmq **v1.13.0**; the cluster still runs **1.11.0**, which predates it — publishing with
  it set against the current broker silently drops it (proto3 unknown-field semantics), with no
  error. Build to the convention, but don't trust end-to-end correlation until the broker roll
  lands. Costs Iris nothing today: publishing is flag-off.

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
   bumps its `lexiconVersion`. **This is the only outstanding ask.**
2. ~~**HermesMQ session:** provision the three `vault.note.*` topics; confirm envelope/correlation
   conventions.~~ **RESOLVED 2026-08-26** — the HermesMQ session confirmed the correlation
   convention (as assumed: envelope, not payload) and corrected the topic ask: the constellation
   self-provisions, so creating the `vault.note.*` topics is **Iris's own startup work**, not
   theirs. Both points are folded into the sections above.

## Iris-side work this implies (when the publisher goes real)

- Create the three topics at startup, idempotently, treating 409 as success — before the first
  publish, alongside the Hermes client wiring. Not built yet: phase 1 ships flag-off with no
  Hermes client dependency.
- Pin the hermesmq client to `v1.13.0`+ (broker auth is off today, so no credential needed).
- Read/write the correlation id through the Lexicon `CorrelationNames` constants; Iris's
  `tracing/CorrelationId.scala` currently mirrors those values as literals with a note to switch
  to the generated constants when the lexicon dependency lands.
