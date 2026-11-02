package me.cference.iris.persistence

import java.time.Instant

/** The most recent row in `scans`, as the metrics surface reports it. */
final case class LastScan(
    kind: String,
    finishedAt: Instant,
    notesSeen: Int,
    created: Int,
    changed: Int,
    deleted: Int,
    errors: Int
)

/**
 * What the index can honestly say about itself at scrape time.
 *
 * @param indexedNotes
 *   rows in `notes` right now
 * @param newestNoteModifiedAt
 *   the newest file mtime in the index — a WEAK freshness proxy (see [[IndexMetricsSource]])
 * @param lastScan
 *   None before the first scan completes; the metric is then omitted rather than reported as 0
 */
final case class IndexMetrics(
    indexedNotes: Long,
    newestNoteModifiedAt: Option[Instant],
    lastScan: Option[LastScan]
)

/**
 * The metrics seam, deliberately separate from [[NoteQueries]]: the API's read surface and the
 * operational surface have different consumers, and route stubs shouldn't have to carry one to test
 * the other.
 *
 * **On what these can and cannot detect.** `charts/iris/README.md` asks for a `vault_freshness`
 * gauge so Hera can catch a *wedged-but-alive* sync sidecar, which shows up as staleness rather
 * than a crash. Iris cannot see the sidecar's sync state (it lives in the CLI's own config dir, and
 * reading a beta tool's internal state format is not a contract worth depending on), so the honest
 * decomposition is:
 *
 *   - **Scan liveness (strong).** `iris_last_scan_timestamp_seconds` stops advancing iff Iris's own
 *     scanning has stopped. Low false-positive; a good alert.
 *   - **Index shortfall (strong, and the one this project cares about).** The runbook's manual
 *     "verify postgres index count == mirror .md count" becomes `iris_last_scan_notes_seen -
 *     iris_index_notes > 0` — the mechanized detector for the silent WatchService drop that the
 *     quiesce gate and the periodic rescan exist to mitigate.
 *   - **Vault mtime age (WEAK — dashboard, not alert).** `iris_vault_newest_note_timestamp_seconds`
 *     cannot distinguish "the sidecar is wedged" from "Calvin hasn't edited a note in three days".
 *     Alerting on it alone generates false pages; it is published as a human-readable signal, not
 *     as the wedge detector the README hoped for.
 *
 * A true sidecar-wedge detector needs the sidecar to report its own sync state. Flagged for Hera.
 */
trait IndexMetricsSource:
  def indexMetrics(): IndexMetrics
