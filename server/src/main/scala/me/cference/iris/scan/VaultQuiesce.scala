package me.cference.iris.scan

import me.cference.iris.config.QuiesceConfig
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * A stat-only fingerprint of the vault tree — what a walk can see without opening a single file.
 * Two identical fingerprints taken apart in time mean the mirror stopped moving.
 */
final case class VaultFingerprint(fileCount: Int, totalBytes: Long, newestMtime: Option[Instant])

/**
 * The boot-ordering gate: hold the initial scan until the vault mirror stops growing.
 *
 * On a fresh deploy the obsidian-sync sidecar downloads ~5,900 files while Iris is already up. The
 * one guaranteed-complete initial scan would otherwise run against a near-empty mirror (observed:
 * 63 of 5,917 seen, 31 silently missed), and the watcher would then register its directories
 * against a tree still being written — `WatchService` drops events for directories it has not
 * registered yet, with no error and no OVERFLOW. Waiting for quiescence first means both the scan
 * and the watcher registration see a settled tree.
 *
 * **This closes the first-boot instance only, NOT the general race.** Any later bulk landing — a
 * Sync backlog after downtime, a vault restructure, a PVC/mirror rebuild — hits the same
 * registration race, so `iris.scan.rescan-interval` remains the standing safety net. A plain
 * restart re-scans against an already-full mirror and never hits this.
 *
 * Fail-open by design: if the mirror is still changing when `max-wait` elapses, scan anyway. The
 * alternative — refusing to scan — serves an empty index, which is a wrong-but-plausible answer,
 * exactly what the design forbids. Scanning is read-only either way, so the vault is never at risk.
 */
object VaultQuiesce:

  private val log = LoggerFactory.getLogger(getClass)

  enum Outcome:
    /** The tree held the same fingerprint for `cfg.stableSamples` consecutive polls. */
    case Stable(fingerprint: VaultFingerprint, waited: FiniteDuration)

    /** `max-wait` elapsed while the tree was still moving (or still empty). Scan anyway. */
    case TimedOut(fingerprint: VaultFingerprint, waited: FiniteDuration)

  /** Walk the vault for sizes and mtimes only, through the same rules the scanner indexes by. */
  def fingerprint(root: Path): VaultFingerprint =
    val files = VaultScanner.listVault(root)
    VaultFingerprint(
      fileCount = files.size,
      totalBytes = files.map(_.sizeBytes).sum,
      newestMtime = files.map(_.modifiedAt).maxOption
    )

  /** Block until the vault at `root` is quiet, or `cfg.maxWait` elapses. */
  def awaitStable(root: Path, cfg: QuiesceConfig): Outcome =
    awaitStableWith(
      sample = () => fingerprint(root),
      cfg = cfg,
      sleep = d => Thread.sleep(d.toMillis),
      nanoTime = () => System.nanoTime()
    )

  /**
   * The loop, with its clock and its I/O injected so it is testable without a real sync sidecar.
   *
   * An EMPTY tree never counts as stable: at boot, zero files means the sidecar has landed nothing
   * yet, and treating that as "settled" would declare victory on the exact failure this gate exists
   * to prevent. A genuinely empty vault therefore waits out `max-wait` once, at boot — an
   * acceptable one-time cost, since readiness is already UP and queries serve the prior index
   * throughout.
   */
  private[scan] def awaitStableWith(
      sample: () => VaultFingerprint,
      cfg: QuiesceConfig,
      sleep: FiniteDuration => Unit,
      nanoTime: () => Long
  ): Outcome =
    val startedAt = nanoTime()
    def waited(): FiniteDuration = (nanoTime() - startedAt).nanos

    var last = sample()
    var unchangedPolls = 0
    var outcome: Option[Outcome] = None

    while outcome.isEmpty do
      if waited() >= cfg.maxWait then outcome = Some(Outcome.TimedOut(last, waited()))
      else
        sleep(cfg.pollInterval)
        val next = sample()
        // A tree that is still empty is still waiting on the sidecar, not settled.
        if next == last && next.fileCount > 0 then unchangedPolls += 1
        else
          if next.fileCount == 0 then
            // An empty mirror is the likeliest real fault here (no token, failed signin, wrong
            // vault name). Say so on every poll: silence through a 15-minute boot wait would send
            // the next debugger to the wrong layer.
            log.warn(
              "vault mirror is empty after {}s — waiting on the sync sidecar (check ITS logs for a 5xx or an auth failure)",
              java.lang.Long.valueOf(waited().toSeconds)
            )
          else if next != last then
            log.info(
              "vault mirror still filling: {} files, {} bytes",
              Integer.valueOf(next.fileCount),
              java.lang.Long.valueOf(next.totalBytes)
            )
          unchangedPolls = 0
        last = next
        if unchangedPolls >= cfg.stableSamples then outcome = Some(Outcome.Stable(last, waited()))

    outcome.get
