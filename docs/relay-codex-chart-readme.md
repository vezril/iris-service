# Relay (drafted, held) → the Codex session: charts/iris README credits a fix that didn't exist

**From:** the Iris session. **Status:** DRAFTED, not sent (no peer channel up at writing).
**Target tree:** `codex/charts/iris/README.md` — Codex owns it, so this is a relay, not a PR.
Per working agreement #1, nothing lands in that tree from here.

## The claim

`charts/iris/README.md`, in the bulk-creation-race section:

> - The iris-service **scan-ordering fix** (initial scan waits for sync to quiesce) closes the
>   *first-boot* instance specifically, but **NOT the general race** — so the rescan stays regardless
>   (Iris, verified 2026-08-26).

**The fix did not exist when that was written.** Checked 2026-10-30 across the whole repo history,
not just the tip:

- `git log --all -S quiesce` → nothing
- `git grep -i quiesce $(git rev-list --all)` → nothing
- `Main.scala` at v0.1.1 starts the initial `fullScan()` directly in the `onComplete` success
  branch of the HTTP bind, with no wait of any kind in between.

So from first deploy until now, the **only** thing standing between a fresh deploy and a
silently-short index was `iris.rescanInterval: 30m` — a value set in the chart, in Codex's tree.
The README's own reasoning was right; it just attributed the first-boot half to code that was
never written. Worth flagging as an instance of working agreement #3 (*a peer's stated state is a
claim, not an observation*) pointing the other way: the chart recorded an iris-service property
that iris-service never had.

## What is true now

`fix/scan-ordering-quiesce` (PR #6 → `development`) implements it for real — `VaultQuiesce`:

- Boot holds the initial scan until a stat-only fingerprint of the mirror (file count, total
  bytes, newest mtime) is unchanged for 3 consecutive 5s polls, capped at 15m.
- The watcher's directory registration happens after the same gate — that's the half that
  actually closes the race, since `WatchService` drops events for directories it hasn't
  registered yet.
- An empty tree never counts as settled, and warns every poll pointing at the *sidecar's* logs.
- Fails open: at `max-wait` it scans anyway, because an empty index is a wrong-but-plausible
  answer.

**The README's framing stays correct and should not be softened:** this closes the first-boot
instance ONLY. A Sync backlog after downtime, a vault restructure, or a PVC/mirror rebuild hits
the same race, and the periodic rescan remains the only thing that heals those.

## Asks

1. **Re-point the claim** at `server/src/main/scala/me/cference/iris/scan/VaultQuiesce.scala` and
   date it to whatever release carries PR #6, rather than 2026-08-26. The surrounding paragraph
   needs no other change.
2. **No chart change required.** `iris.rescanInterval: 30m` now matches iris-service's own default
   (this PR also flips the repo default 6h → 30m, so the override is redundant but harmless — keep
   it as documentation of intent). The new `iris.scan.quiesce.*` keys all default to their intended
   production values and are env-overridable (`IRIS_QUIESCE_*`) if the chart ever needs to tune them.
3. **Heads-up for the next deploy:** first boot will now sit in the gate for up to 15m while the
   sidecar downloads, logging either "vault mirror still filling" or an empty-mirror warning.
   A pod that looks idle at boot with `/health` UP and a near-zero index count is the gate working,
   not a hang. `POST /admin/reindex` is still the manual lever.

## `vault_freshness` — now implemented, but NOT as the README describes it

`charts/iris/README.md` says a wedged-but-alive sidecar "shows up as staleness, not a crash" and
that "Iris will expose a `vault_freshness` gauge for **Hera** to alert on."

iris-service now serves `GET /metrics` on :8080 (Prometheus text exposition, content-free). But
**the gauge as described cannot be built honestly from inside Iris**, and shipping it under that
name would have handed Hera a false authority:

- Iris cannot see the sidecar's sync state. It lives in the CLI's own config dir, and depending on
  a beta tool's internal state format is not a contract worth having.
- The nearest thing Iris *can* measure — the newest note mtime — **cannot distinguish a wedged
  sidecar from Calvin not having edited a note for three days.** An alert on it pages on quiet
  weeks. It ships as `iris_vault_newest_note_timestamp_seconds`, explicitly labeled weak, for
  dashboards only.

So it is decomposed into signals that are actually sound:

| Gauge | Strength | Use |
|---|---|---|
| `iris_last_scan_timestamp_seconds` | **Strong** | Stops advancing iff *Iris's own* scanning stopped |
| `iris_last_scan_notes_seen` + `iris_index_notes` | **Strong** | Their difference is the short-index detector |
| `iris_last_scan_errors` | Strong | Persistent non-zero ⇒ notes missing from the index |
| `iris_vault_newest_note_timestamp_seconds` | **WEAK** | Dashboard only — do not alert |
| `iris_index_queryable` | Strong | 0 ⇒ Iris is up but cannot reach Postgres |

**The useful one is the second row.** The README's own manual instruction — *"verify `postgres index
count == mirror .md count` after any bulk event"* — becomes
`iris_last_scan_notes_seen - iris_index_notes > 0`. The silent `WatchService` drop that this whole
thread is about is now mechanically detectable instead of needing someone to remember to check.

Alert expressions are in `docs/run-shape.md` under "What the gauges can and cannot tell Hera".

### Two asks on this

4. **Re-point the README's observability line** at the decomposed gauges, and drop the
   `vault_freshness` name — there is no metric by that name and there should not be, since the
   thing it names is the one measurement Iris can't make.
5. **A real sidecar-wedge detector is chart/sidecar territory.** `ob sync-status --json` exists, so
   a sidecar-side exporter — or a periodic status write to the PVC that something scrapes — is the
   plausible shape. Iris can't reach it; whoever owns the sidecar container can. Worth raising with
   Calvin before anyone builds it, since it's net-new scope rather than a fix.
