package me.cference.iris.scan

import me.cference.iris.domain.VaultPath
import me.cference.iris.persistence.IndexedMeta

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable

/** One vault file as seen on disk (not yet read). */
final case class FileMeta(path: VaultPath, sizeBytes: Long, modifiedAt: Instant)

/** What a walk found, relative to the index: work to do. */
final case class ChangeSet(
    createdOrChanged: Vector[FileMeta],
    deleted: Vector[String]
)

/**
 * Walks the vault and diffs it against the index using cheap `(size, mtime)` comparison — 5,900
 * notes must rescan in seconds, so unchanged files are skipped without being opened. The hash check
 * in the reconciler is the second, content-true line: a file touched but unchanged upserts to the
 * same hash and emits nothing.
 */
object VaultScanner:

  /**
   * File mtimes are compared against what Postgres stored, and `timestamptz` keeps microseconds
   * while APFS/ext4 report nanoseconds — un-truncated, every rescan would see every note as
   * "changed". One truncation, applied at every capture point.
   */
  def storedPrecision(i: Instant): Instant = i.truncatedTo(ChronoUnit.MICROS)

  /** Every indexable file currently in the vault. Excluded paths are never visited. */
  def listVault(root: Path): Vector[FileMeta] =
    val out = Vector.newBuilder[FileMeta]
    if Files.isDirectory(root) then
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(
              dir: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult =
            // Never descend into dot-directories (.obsidian, .smart-env, .trash).
            if dir != root && dir.getFileName.toString.startsWith(".") then
              FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE

          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            val rel = root.relativize(file).toString.replace('\\', '/')
            VaultPath.parse(rel) match
              case Right(vp) if VaultRules.shouldIndex(vp) =>
                out += FileMeta(
                  vp,
                  attrs.size(),
                  storedPrecision(attrs.lastModifiedTime().toInstant)
                )
              case _ => () // unparseable or excluded: not ours to open
            FileVisitResult.CONTINUE

          override def visitFileFailed(
              file: Path,
              exc: java.io.IOException
          ): FileVisitResult =
            FileVisitResult.CONTINUE // a vanished file mid-walk is a change we'll catch next pass
      )
    out.result()

  /** Diff the walk against the index. Same `(size, mtime)` => assumed unchanged, skipped. */
  def diff(current: Vector[FileMeta], indexed: Map[String, IndexedMeta]): ChangeSet =
    val seen = mutable.Set.empty[String]
    val work = Vector.newBuilder[FileMeta]
    current.foreach { fm =>
      seen += fm.path.value
      indexed.get(fm.path.value) match
        case Some(m) if m.sizeBytes == fm.sizeBytes && m.modifiedAt == fm.modifiedAt => ()
        case _ => work += fm
    }
    val deleted = indexed.keysIterator.filterNot(seen).toVector
    ChangeSet(work.result(), deleted)
