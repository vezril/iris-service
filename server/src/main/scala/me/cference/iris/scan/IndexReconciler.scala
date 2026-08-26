package me.cference.iris.scan

import me.cference.iris.domain.VaultPath
import me.cference.iris.parse.NoteParser
import me.cference.iris.persistence.{NoteRepository, ScanSummary}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.util.control.NonFatal

/**
 * Drives a full scan: walk → diff → per-file read+parse+upsert, then deletes, then a bookkeeping
 * row. Every file is one repository transaction, so a crash mid-scan leaves a consistent index that
 * the next scan simply continues from.
 *
 * Reads tolerate the sync sidecar's undocumented write pattern: a file that vanishes or errors
 * mid-read is logged, counted, and left for the next pass — never a crash, never a partial note.
 */
final class IndexReconciler(root: Path, repo: NoteRepository):

  private val log = LoggerFactory.getLogger(getClass)

  def fullScan(kind: String = "full"): ScanSummary =
    val startedAt = Instant.now()
    val indexed = repo.allMeta()
    val current = VaultScanner.listVault(root)
    val changes = VaultScanner.diff(current, indexed)

    var created = 0
    var changed = 0
    var errors = 0

    changes.createdOrChanged.foreach { fm =>
      readAndIndex(fm.path) match
        case ReadOutcome.Indexed =>
          if indexed.contains(fm.path.value) then changed += 1 else created += 1
        case ReadOutcome.Skipped => ()
        case ReadOutcome.Failed => errors += 1
    }

    changes.deleted.foreach { p =>
      VaultPath.parse(p) match
        case Right(vp) =>
          repo.delete(vp)
          log.info("deleted from index: {}", p)
        case Left(_) => errors += 1
    }

    val summary = ScanSummary(
      kind = kind,
      startedAt = startedAt,
      finishedAt = Instant.now(),
      notesSeen = current.size,
      created = created,
      changed = changed,
      deleted = changes.deleted.size,
      errors = errors
    )
    repo.recordScan(summary)
    log.info(
      "scan[{}] complete: {} notes seen, {} created, {} changed, {} deleted, {} errors in {}ms",
      kind,
      Integer.valueOf(summary.notesSeen),
      Integer.valueOf(created),
      Integer.valueOf(changed),
      Integer.valueOf(summary.deleted),
      Integer.valueOf(errors),
      java.lang.Long.valueOf(summary.finishedAt.toEpochMilli - summary.startedAt.toEpochMilli)
    )
    summary

  /**
   * Reconcile ONE path against the vault — the watcher's unit of work. Reads current disk state
   * rather than trusting the event kind: a create/modify/delete race collapses to "what is true
   * now". Returns true when the index changed.
   */
  def reconcilePath(vp: VaultPath): Boolean =
    if !VaultRules.shouldIndex(vp) then false
    else
      val file = root.resolve(vp.value)
      val indexed = repo.allMeta().get(vp.value)
      try
        if Files.isRegularFile(file) then
          val bytes = Files.readAllBytes(file)
          val mtime = VaultScanner.storedPrecision(Files.getLastModifiedTime(file).toInstant)
          val note = NoteParser.parse(vp, bytes, mtime)
          if indexed.exists(_.contentHash == note.contentHash.value) then false
          else
            repo.upsert(note)
            log.info("watch: indexed {}", vp.value)
            true
        else if indexed.isDefined then
          repo.delete(vp)
          log.info("watch: deleted {}", vp.value)
          true
        else false
      catch
        case NonFatal(e) =>
          log.warn("watch: failed to reconcile {}: {} — rescan will heal", vp.value, e.getMessage)
          false

  private enum ReadOutcome:
    case Indexed, Skipped, Failed

  private def readAndIndex(vp: VaultPath): ReadOutcome =
    val file = root.resolve(vp.value)
    try
      if !Files.isRegularFile(file) then ReadOutcome.Skipped // vanished mid-scan; next pass owns it
      else
        val bytes = Files.readAllBytes(file)
        val mtime = VaultScanner.storedPrecision(Files.getLastModifiedTime(file).toInstant)
        val note = NoteParser.parse(vp, bytes, mtime)
        repo.upsert(note)
        ReadOutcome.Indexed
    catch
      case NonFatal(e) =>
        log.warn("failed to index {}: {} — will retry next scan", vp.value, e.getMessage)
        ReadOutcome.Failed
