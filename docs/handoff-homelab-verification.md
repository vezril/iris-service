# Handoff — verification that needs a container runtime (homelab pickup)

**Why this exists:** the session that wrote the quiesce gate and the metrics surface (2026-10-30 /
2026-11-02) worked on a machine with **no container runtime at all**. Three test suites and any
end-to-end run were therefore never executed locally. This is the list of what to actually run once
you're somewhere with Docker or podman, and what to conclude from each result.

```
RELAY → next Iris session (homelab)
Kind: state-report
Authorization: none — Calvin asked for this handoff to be left in the repo
  ("leave in the repo a handoff prompt for when i run the repo on my homelab
  network for the container runtime"). Nothing here authorizes a deploy, a tag,
  a published image, or any write to the vault.
Ask: Run the four verifications below (full suite, end-to-end boot, the staged-fill
  quiesce test, the metrics shape), then record the results in this file and correct
  anything the empirical run contradicts — especially the quiesce timings, which were
  chosen by reasoning, not measurement.
Claims:
- [verified search: "no container runtime"] `docker`, `docker-compose`, `podman` are all absent
  from PATH and `/Applications/Docker.app` does not exist on the authoring machine.
- [verified server/src/test/.../NoteRepositorySpec.scala, IndexReconcilerSpec.scala,
  VaultWatcherSpec.scala] exactly three suites use `PostgreSQLContainer`/`TestContainerForAll`;
  they abort with "Could not find a valid Docker environment" without a runtime.
- [verified server/src/main/scala/me/cference/iris/scan/VaultQuiesce.scala:73] the quiesce loop is
  `awaitStableWith`, which takes `sleep` and `nanoTime` as parameters.
- [verified server/src/test/scala/me/cference/iris/scan/VaultQuiesceSpec.scala:44-45] every quiesce
  test injects a fake clock (`nanoTime = () => nanos`) and a fake sleep, so the suite proves the
  LOGIC and has never observed a real filesystem filling up.
- [verified server/src/main/resources/application.conf:99-111] the shipped gate is
  `enabled = true`, `poll-interval = 5s`, `stable-samples = 3`, `max-wait = 15m`.
- [verified server/src/main/resources/application.conf:89] `rescan-interval = 30m`.
- [verified server/src/main/scala/me/cference/iris/scan/VaultQuiesce.scala:92] an empty tree never
  counts as settled (`next.fileCount > 0` is part of the stability condition).
- [verified git log -S quiesce] the earliest commit introducing any quiesce logic is `4498c2b`
  (this work). Before it, none existed.
- [refuted ~/Code/Olympus/codex/charts/iris/README.md:140] "The iris-service **scan-ordering fix**
  (initial scan waits for sync to quiesce) ... (Iris, verified 2026-08-26)" — actually no such code
  existed on 2026-08-26; `4498c2b` is the first implementation. Correction is queued for Codex in
  `docs/relay-codex-chart-readme.md`; do not re-land it in their tree.
- [verified ~/Code/Olympus/codex/apps/iris/iris.yaml:20] the HelmRelease PINS
  `calvinference/iris:0.1.1`. That is evidence about the pin only.
- [unverified] what is actually RUNNING in the cluster, and whether the pin has moved. Check the
  cluster, not the repo.
- [unverified] PR #7 (`development`→`main`) and PR #8 (`feat/metrics-surface`→`development`) were
  both green and open as of 2026-11-02 ~14:30. Merge state may have changed — re-check with
  `gh pr list`.
- [unverified] whether `3 x 5s` quiescence and a `15m` cap match how `obsidian-headless` actually
  writes during a first sync. Chosen by reasoning; never measured. This is the main open question.
Delivery: queued — committed in this repo rather than sent. The target is this repo's own next
  session, not a peer, so no `codex/docs/session-coordination.md` "Pending relays" entry is needed
  (and that file belongs to the Codex session regardless — don't edit it from here). A pointer to
  this doc is in README.md so kickoff finds it.
```

## 0. Guardrails (read before pointing anything at the vault)

- **Phase 1 has no write path**, so running Iris against the real vault is safe by construction.
  Verified 2026-11-02 — this returns nothing across `server/src/main` and `core/src/main`:

  ```bash
  grep -rn "Files.write\|newBufferedWriter\|newOutputStream\|Files.delete\|Files.createFile\|FileWriter" \
    server/src/main core/src/main
  ```

  **Re-run it before you trust this paragraph**: it is a property of the code at a point in time,
  and it is the cheapest check that the read-only guarantee still holds.
- For the staged-fill test below you will be creating, filling and deleting a directory tree. **Use
  a COPY of the vault, never `~/Mindmap` itself.** The test is about a directory being populated;
  pointing it at the real vault means a mistake in the harness is a mistake on Calvin's notes.
- Nothing here needs the cluster, and nothing here should touch it.

## 1. The full suite (the three suites that have never run)

```bash
cd ~/Code/Olympus/iris-service
sbt -batch "scalafmtCheckAll; scalafmtSbtCheck; compile; Test/compile; test"
```

Expected, matching the last CI run (v0.4.0, 2026-11-12): **core 105/105 (9 suites)**,
**server 132/132 (14 suites)**, `aborted 0`. Measured, not estimated — core by running
`sbt core/test` here, server from the release run's own log.

*(The figures this section carried until 2026-11-13 — core 47/47, server 54/54 — were from
2026-11-02 and had been overtaken by phase 2, the snapshot archive and `GET /scans`.)*

If the server count is lower and you see `Could not find a valid Docker environment`, the runtime
isn't reachable from the JVM — see §5.

**What a failure here means:** `NoteRepositorySpec` is the one carrying the metrics SQL
(`indexMetrics`: NULL handling on a fresh index, last-*completed*-scan ordering, the in-flight-scan
exclusion, the shortfall gap) and, since v0.4.0, the `recentScans` history — including the case
asserting that the history's head is the same scan `/metrics` reports, tie-break included. Those
four metrics cases already failed once on shared-container state
leakage and were fixed by truncating in `withRepo`; if they fail again, check whether a newly added
test asserts on an aggregate without that isolation.

## 2. End-to-end boot

```bash
docker run -d --name iris-pg -e POSTGRES_PASSWORD=iris -e POSTGRES_USER=iris \
  -e POSTGRES_DB=iris -p 5432:5432 postgres:16

# A COPY of the vault, not the vault.
cp -R ~/Mindmap /tmp/iris-vault-copy
IRIS_VAULT_ROOT=/tmp/iris-vault-copy sbt server/run
```

Then:

```bash
curl -s localhost:8080/health                      # {"status":"UP",...}
curl -s localhost:8080/metrics                     # see §4
curl -s 'localhost:8080/notes?limit=3' | head -c 400
curl -s -X POST localhost:8080/admin/reindex       # 202
```

On an already-full directory the gate should settle in **~15–20s** (three unchanged 5s polls) and
log `vault mirror settled after Ns: N notes on disk`. The initial scan then runs. Confirm
`index holds N notes after initial scan` matches `find /tmp/iris-vault-copy -name '*.md' | wc -l`
— a mismatch is the short-index bug this whole subsystem exists for, and it would be the first time
it has been observed outside the cluster.

## 3. The staged-fill test — the one that actually matters

This is the verification that could not be done on the authoring machine, and it **does not need
the cluster or the sidecar**. It reproduces the first-boot condition directly: Iris comes up
against an empty directory that then fills.

```bash
# 1. Empty target, Iris pointed at it.
rm -rf /tmp/iris-staged && mkdir -p /tmp/iris-staged
IRIS_VAULT_ROOT=/tmp/iris-staged sbt server/run      # leave running, watch the log

# 2. In another shell, trickle the vault in — imitating a sync download.
cd ~/Mindmap
find . -name '*.md' | head -2000 | while read -r f; do
  mkdir -p "/tmp/iris-staged/$(dirname "$f")"
  cp "$f" "/tmp/iris-staged/$f"
  sleep 0.01
done
```

What to confirm, in order:

1. **While the directory is empty**, the log warns *every poll*:
   `vault mirror is empty after Ns — waiting on the sync sidecar`. Silence here is a bug — the
   whole point was that an empty mirror is loud rather than a mute 15-minute wait.
2. **While files are landing**, it logs `vault mirror still filling: N files, N bytes` and does
   **not** scan.
3. **After the copy ends**, it settles within ~15–20s and the initial scan sees the **full** count,
   not a partial one. This is the behaviour the old code got wrong (63 of 5,917).
4. **The watcher registers after the gate**, so notes added *after* settling still get indexed:
   `echo '# late' > /tmp/iris-staged/late-note.md` and confirm it appears in
   `curl -s 'localhost:8080/notes?limit=500'` within a few seconds.

Then the question the tests cannot answer: **re-run with a pause longer than 15s in the middle of
the copy** (`sleep 20` halfway). The gate will declare the mirror settled during that pause and scan
early. That is a real false-settle, and it tells you whether `stable-samples = 3` at `5s` is right
for a sync that stalls — adjust `IRIS_QUIESCE_STABLE_SAMPLES` / `IRIS_QUIESCE_POLL_INTERVAL` and
record what you chose and why. **Do not just raise the numbers**: a longer gate delays every boot,
and the 30m rescan is the backstop for exactly this miss. Documenting that the trade-off was
measured is worth more than a different number.

## 4. The metrics shape

```bash
curl -s localhost:8080/metrics
```

Confirm against `docs/run-shape.md` ("What the gauges can and cannot tell Hera"):

- `iris_index_notes` and `iris_last_scan_notes_seen` are **both present and equal** after a clean
  scan. Their difference is the short-index detector, so equality is the healthy baseline.
- `iris_last_scan_timestamp_seconds` is a plausible unix timestamp (not 0, not absent after a scan).
- Integral values render without `.0` (`5917`, not `5917.0`).
- Before the first scan completes, the `iris_last_scan_*` gauges are **absent entirely**, not zero.
  Easiest check: scrape during the staged-fill while the gate is still holding.
- Stop Postgres (`docker stop iris-pg`) and scrape again: expect **200** with
  `iris_index_queryable 0` — *not* a 5xx. A 5xx would zero Prometheus's own `up` and conflate
  "iris is down" with "iris can't reach its read model".
- No vault content anywhere in the output.

## 5. If testcontainers can't see the runtime

- **podman** needs the docker-compatible socket exported, e.g.
  `export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')`
  plus `export TESTCONTAINERS_RYUK_DISABLED=true` (Ryuk is the usual podman sticking point).
- **Colima**: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.
- Verify with `docker info` before blaming sbt.

**Read the whole error before concluding the runtime is missing.** A reachable daemon fails the
same way if the *client* is too old, and the message says which:

```
UnixSocketClientProviderStrategy: failed with
Status 400: client version 1.32 is too old.
           Minimum supported API version is 1.40
```

docker-java advertises Engine API 1.32 unless told otherwise, and a current Docker Engine refuses
anything below 1.40 — so all three suites abort with Docker running fine. This bit CI on
2026-11-12 and cost two wrong diagnoses (first "the Ubuntu 26 runner has no Docker", then "it's a
flaky runner") before the log was read to the end.

`build.sbt` already carries the fix — `Test / fork := true` plus
`Test / javaOptions += "-Dapi.version=1.43"` — so a fresh clone should not see it. If you do, check
that those two settings survived a merge. Note that bumping testcontainers-scala does **not**
substitute for the property: 0.43.0 still resolves docker-java 3.4.0, whose default is
`UNKNOWN_VERSION` → 1.32 (measured by the Ariadne session).

## 6. Record the outcome here

Append results below, including anything that contradicts this document — the quiesce timings
especially are the authoring session's reasoning, not measurement, and an empirical result should
overwrite them rather than sit alongside them.

| Date | What ran | Result |
|---|---|---|
| | | |

## Still open, and not for this pickup

Two items this section listed as open have since shipped: **phase 2 safe writes** (v0.3.0, still
`enabled = false` by default) and **`iris-ui`** (v0.1.0, image published, not deployed).

Still needs Calvin's own word (per `AGENTS.md`): a **sidecar-side wedge detector**
(`ob sync-status --json` exists; Iris can't reach it — net-new scope), and the **deploy** — the
HelmRelease still pins `calvinference/iris:0.1.1` while `0.4.0` and `irisui:0.1.0` sit published
and unrolled. Roll the service before the console: iris-ui's scan-history card needs
iris-service > v0.3.0 and renders a 404 failure state against 0.1.1.

Blocked on another session: the **Lexicon `vault.proto`** contract, which keeps the Hermes
`vault.note.*` feed flag-off (re-checked 2026-11-13 — no vault protos in the-lexicon yet; the
proposal is `docs/vault-proto-proposal.md`). Also note the envelope `correlation_id` needs
hermesmq ≥ 1.13.0 and the cluster runs 1.11.0.
