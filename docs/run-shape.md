# Iris run shape — spec for the Codex session (chart owner)

**From:** the Iris session. The chart lands at `codex/charts/iris` + `codex/apps/iris` (owned by
Codex, per session-coordination). This doc is the ask; nothing here is a chart.

## Decision context (Calvin, 2026-08-26)

Vault access is **"Variant 3"**, superseding the design doc's V1/V2 fork: the official headless
Obsidian Sync CLI (`obsidian-headless` on npm — open beta, v0.0.14 at writing, Node ≥ 22, binary
`ob`) runs as a **sidecar** in the iris pod, pulling the vault into a shared PVC. Rationale: mimir
NFS mounts into the cluster are broken (QNAP ACLs), the MacBook holding `~/Mindmap` is not
always-on, and the bridge must stay current 24/7. The codex design doc still says "no headless
mode exists" — it predates the beta; propose an update alongside this.

## Pod: two containers, one PVC

```
iris pod
├── obsidian-sync (sidecar)          image: node:22-slim + `npm i -g obsidian-headless`
│     HOME=/vault/ob-state             (Dockerfile owned by Codex; iris-service ships none)
│     cmd: ob sync --path /vault/vault --continuous
│     sync mode: PULL-ONLY  ← the hard phase-1 guarantee (see below)
│     mounts: iris-vault PVC at /vault (read-write)
└── iris                             image: calvinference/iris (sbt-native-packager, :8080)
      env: IRIS_VAULT_ROOT=/vault/vault, IRIS_DB_* (pg-service secret), IRIS_HERMES_ENABLED=false
      mounts: iris-vault PVC at /vault (**readOnly: true** — kernel-level cannot-write)
```

- **PVC `iris-vault`, ~5Gi, local-filesystem storage class (local-path) — NOT NFS.** The watcher
  uses inotify, which does not fire on NFS; on NFS Iris degrades to its 6h rescans. The vault is
  ~6k markdown notes + assets (~a few hundred MB today).
- **PVC layout:** `vault/` (the Mindmap mirror — disposable: a fresh sync rebuilds it, the
  Postgres index rebuilds from it) and `ob-state/` (the CLI's HOME: login session + sync state —
  this must survive restarts).

## Phase-1 safety posture (why this shape cannot harm the vault)

1. `ob sync-config` mode **pull-only** — the sidecar never pushes local changes to the real vault.
   **Must be enforced fail-closed, not merely set once (added 2026-08-26).** See the section
   below: this is the only one of the four layers that can silently stop being true.
2. The iris container's mount is **read-only** — it cannot write even if it wanted to.
3. Iris's own code never opens a vault file for writing (review-enforced; all I/O funnels through
   the scanner).
4. The mirror and the index are both disposable and rebuildable.

## Bootstrap (revised 2026-08-26 — no `kubectl exec` needed)

Every command that bootstrap needs takes flags, so the only step requiring a human is minting
the token, and that happens **once, on Calvin's own machine**:

1. **Calvin, locally:** `ob login` (email + password + MFA — prompted, or via `--email`
   `--password` `--mfa`). Then read the minted token from
   `~/.obsidian-headless/auth_token` on macOS.
2. **Two Secrets** (values never in chart values): `OBSIDIAN_AUTH_TOKEN` from step 1, and the
   E2EE sync password.
3. **The sidecar bootstraps itself** on start, non-interactively:

```
ob sync-setup  --vault "<vault>" --path /vault/vault --password "$OBSIDIAN_SYNC_PASSWORD"
ob sync-config --path /vault/vault --mode pull-only
ob sync-status --path /vault/vault --json     # assert mode is pull-only, else exit 1
ob sync        --path /vault/vault --continuous
```

That is the fail-closed wrapper and the bootstrap in one: re-asserting the mode on every start
is exactly what stops a wiped PVC from silently reverting to `bidirectional`, and `sync-setup`
is idempotent enough to re-run against existing state. The `sidecar.bootstrap` gate can stay as
an escape hatch for manual intervention, but it is no longer the normal path.

## Pull-only must fail closed (safety defect found 2026-08-26, in the first chart draft)

The other three no-harm layers are structural: a kernel mount flag, the absence of a write path
in the code, a design property. **Pull-only is a human running one command once**, persisted as
CLI state inside `ob-state/` — the only layer that can quietly stop being true.

Worse, layers 1 and 4 interact. The mirror is deliberately disposable, and the stated recovery
story is "wipe it, a fresh sync rebuilds it." That is safe **only because** pull-only stops local
deletions propagating. Invert it — a fresh or wiped PVC, a re-run bootstrap where the
`sync-config` step is skipped or fat-fingered — and an empty-or-partial mirror syncs
bidirectionally against the real vault. Disposability becomes the delivery mechanism for mass
deletion of the thing this design exists to protect, and it fails **silently**: healthy sidecar,
no error, mirror looks correct.

**Requirement:** the sidecar must not trust persisted state. On every container start it should
re-assert pull-only (idempotent), then *verify* the effective mode and **exit non-zero if it is
not pull-only**, so the pod crashloops loudly rather than syncing bidirectionally. Fail closed —
"when in doubt, read-only" applied to the sync layer itself.

**Syntax (probed 2026-08-26, `obsidian-headless` 0.0.14 — no longer blocked):**

```
ob sync-config --path /vault/vault --mode pull-only    # set
ob sync-status --path /vault/vault --json              # read back, for the assertion
```

`--mode` takes `bidirectional`, `pull-only` (only download, ignore local changes), or
`mirror-remote` (only download, revert local changes). **`bidirectional` is the DEFAULT** — the
dangerous mode is what you get by omission, which is the whole argument for fail-closed.

`mirror-remote` is worth considering over `pull-only`: both are download-only, but it actively
reverts local divergence instead of ignoring it. Since Iris never writes the mirror, either is
correct; `mirror-remote` is the more self-healing of the two.

**RESOLVED 2026-08-26** (read from `obsidian-headless` 0.0.14's own source, no login required).
The CLI keeps all state in one config directory:

- **Linux** (the container): `$XDG_CONFIG_HOME/obsidian-headless`, defaulting to
  `~/.config/obsidian-headless`.
- **macOS/other**: `~/.obsidian-headless` (note: *not* `~/.config`).

Contents: `auth_token` (the account session), `sync/<vault>/config.json` (per-vault sync
configuration — **including the mode**), and `publish/` (irrelevant here).

Two consequences:

1. **The `HOME=/vault/ob-state` assumption holds.** On Linux with that HOME, state lands at
   `/vault/ob-state/.config/obsidian-headless/` — on the PVC, surviving restarts. No layout
   change needed.
2. **`OBSIDIAN_AUTH_TOKEN` overrides the file entirely** — the source checks the env var first
   and returns it directly. So the session can come from a **k8s Secret** instead of persisted
   volume state: it then survives PVC loss, and matches the constellation's secrets-in-Secrets
   convention rather than trusting a disposable volume.

**This also confirms the fail-closed defect concretely.** The pull-only mode is stored in
`sync/<vault>/config.json` *inside that same config dir*, i.e. on the PVC we call disposable. Wipe
the PVC, re-run setup without `--mode`, and you silently get `bidirectional`. The predicted
failure mode has a file path.

## Resource asks

- sidecar: requests 100m/256Mi, limits 500m/512Mi (Node + Electron-less sync only)
- iris: requests 250m/512Mi, limits 1000m/768Mi (JVM; initial scan is the peak)
- **Postgres:** an `iris` database + user via the shared `pg-service` chart
  (`codex/apps/iris/postgres.yaml`). Iris self-migrates on boot; no initdb ConfigMap needed.

## Health / observability

- `GET /health` on :8080 (200 UP / 503 during shutdown drain) — probe target.
- Sidecar liveness: process exit → container restart (k8s default); a wedged-but-alive sync shows
  up as staleness, which Iris will expose as a `vault_freshness` metric (planned; not yet
  implemented — flag for Hera later).
- Logs: `LOG_FORMAT=json` (the image default) → constellation structured-log shape.

## Beta-CLI risk register

`obsidian-headless` is an open beta at v0.0.x: crash/hang behavior and its mid-sync write pattern
are undocumented. Mitigations already in Iris: debounced watch events, hash-noop upserts, read
retry via next scan, periodic full rescan, disposable mirror, and the pull-only mode meaning no
failure mode can propagate to the real vault. Re-evaluate CLI maturity before phase 2 (writes).
