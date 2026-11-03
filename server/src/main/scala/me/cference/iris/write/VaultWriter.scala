package me.cference.iris.write

import me.cference.iris.domain.{ContentHash, VaultPath}
import me.cference.iris.parse.FrontmatterParser
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, LinkOption, Path, StandardCopyOption}
import scala.util.control.NonFatal

enum WriteError(val message: String):

  /**
   * The caller's hash does not match what is on disk: Calvin edited the note. Surface, never win.
   */
  case Conflict(expected: Option[String], actual: Option[String])
      extends WriteError(
        s"note changed since it was read (expected ${expected.getOrElse("absent")}, " +
          s"found ${actual.getOrElse("absent")})"
      )

  /** A write to an existing note must prove which version it read. */
  case HashRequired
      extends WriteError("a write to an existing note must carry the content hash it read")

  /** Property 2: a symlink could point outside the service's folder. */
  case SymlinkRefused(detail: String)
      extends WriteError(s"refusing to write through a symlink: $detail")

  case PatchRefused(cause: PatchError) extends WriteError(cause.message)
  case SnapshotFailed(cause: SnapshotError) extends WriteError(cause.message)
  case Io(detail: String) extends WriteError(s"write failed: $detail")

/** What a successful write produced. `changed = false` means the edits were already applied. */
final case class WriteResult(
    path: VaultPath,
    contentHash: ContentHash,
    changed: Boolean,
    snapshot: Option[SnapshotRef]
)

/**
 * The single writer. Applies edits to one note, in an order chosen so that every failure leaves the
 * vault as it was.
 *
 * The sequence, and why it is this sequence:
 *   1. refuse symlinks anywhere between the vault root and the target (property 2 of the access
 *      model — the one thing [[ConfinedPath]] cannot check, because it needs the filesystem) 2.
 *      read the current bytes and check the optimistic lock — a stale hash is a CONFLICT, never a
 *      last-writer-wins overwrite 3. apply the patch in memory; if the bytes are unchanged, stop
 *      here — no snapshot, no write, so a retry is free and an idempotent re-apply does not churn
 *      the vault or Apollo 4. snapshot the ORIGINAL bytes, and ABANDON the write if that fails 5.
 *      write atomically, so a crash mid-write cannot leave a half-written note
 *
 * Steps 3 and 4 are in that order deliberately: snapshotting before knowing whether anything
 * changes would fill Apollo with copies of notes nobody edited. Step 4 before step 5 is the
 * fail-closed rule — there is never a window where the vault is modified and no undo exists.
 */
final class VaultWriter(root: Path, snapshots: SnapshotStore):

  private val log = LoggerFactory.getLogger(getClass)

  def write(
      confined: ConfinedPath,
      expectedHash: Option[ContentHash],
      edits: Seq[NoteEdit]
  ): Either[WriteError, WriteResult] =
    val vp = confined.path
    val target = root.resolve(vp.value)

    for
      _ <- refuseSymlinks(target)
      current <- readCurrent(target)
      _ <- checkLock(expectedHash, current.map(_._2))
      patched <- patch(current.map(_._1), edits)
      result <- commit(vp, target, current.map(_._1), patched)
    yield result

  /**
   * Property 2. Checks the target AND every directory between it and the vault root, because a
   * symlinked PARENT escapes confinement just as effectively as a symlinked file.
   *
   * Refuses rather than resolving-and-rechecking: the access model offers both and notes that
   * refusing is simpler and nothing needs them. A resolve-and-recheck would also be racy — the link
   * could be replaced between the check and the write.
   */
  private def refuseSymlinks(target: Path): Either[WriteError, Unit] =
    try
      if Files.isSymbolicLink(target) then
        Left(WriteError.SymlinkRefused(s"${root.relativize(target)} is a symlink"))
      else
        var dir = target.getParent
        var bad: Option[String] = None
        while bad.isEmpty && dir != null && dir.startsWith(root) && dir != root do
          if Files.isSymbolicLink(dir) then
            bad = Some(s"${root.relativize(dir)} is a symlinked directory")
          dir = dir.getParent
        bad.map(d => Left(WriteError.SymlinkRefused(d))).getOrElse(Right(()))
    catch case NonFatal(e) => Left(WriteError.Io(e.getMessage))

  /** Current bytes and hash, or None when the note does not exist yet. */
  private def readCurrent(target: Path): Either[WriteError, Option[(Array[Byte], ContentHash)]] =
    try
      if !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then Right(None)
      else if !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) then
        Left(WriteError.Io("target exists and is not a regular file"))
      else
        val bytes = Files.readAllBytes(target)
        Right(Some(bytes -> ContentHash.ofBytes(bytes)))
    catch case NonFatal(e) => Left(WriteError.Io(e.getMessage))

  /**
   * Optimistic concurrency, in the strict direction.
   *
   *   - existing note, no hash presented → refused. A caller must prove which version it read; "I
   *     didn't look" is not permission to overwrite.
   *   - existing note, wrong hash → conflict (Calvin edited it in Obsidian since the read).
   *   - absent note, a hash presented → conflict. The note the caller read is gone; recreating it
   *     from their stale copy would silently resurrect a deleted note.
   *   - absent note, no hash → a create.
   */
  private def checkLock(
      expected: Option[ContentHash],
      actual: Option[ContentHash]
  ): Either[WriteError, Unit] =
    (expected, actual) match
      case (None, None) => Right(())
      case (Some(e), Some(a)) if e == a => Right(())
      case (None, Some(_)) => Left(WriteError.HashRequired)
      case (e, a) => Left(WriteError.Conflict(e.map(_.value), a.map(_.value)))

  private def patch(
      current: Option[Array[Byte]],
      edits: Seq[NoteEdit]
  ): Either[WriteError, String] =
    val text = current.map(b => new String(b, StandardCharsets.UTF_8)).getOrElse("")
    // Pass the parse error through, so NotePatch refuses frontmatter edits on a note Iris
    // cannot read rather than guessing at its structure.
    val fmError = current.flatMap(_ => FrontmatterParser.split(text).frontmatterError)
    NotePatch(text, edits, fmError).left.map(WriteError.PatchRefused.apply)

  private def commit(
      vp: VaultPath,
      target: Path,
      original: Option[Array[Byte]],
      patched: String
  ): Either[WriteError, WriteResult] =
    val newBytes = patched.getBytes(StandardCharsets.UTF_8)

    // No-op short circuit: identical bytes mean no snapshot and no write at all.
    if original.exists(_.sameElements(newBytes)) then
      Right(WriteResult(vp, ContentHash.ofBytes(newBytes), changed = false, snapshot = None))
    else
      // Snapshot only what exists. A create has no prior state to preserve, so requiring a
      // snapshot would block every first write for no safety gain.
      val snapshotStep: Either[WriteError, Option[SnapshotRef]] =
        original match
          case None => Right(None)
          case Some(bytes) =>
            snapshots
              .snapshot(vp, bytes)
              .map(Some(_))
              .left
              .map { err =>
                log.warn("refusing write to {}: {}", vp.value, err.message)
                WriteError.SnapshotFailed(err)
              }

      for
        snapshot <- snapshotStep
        _ <- atomicWrite(target, newBytes)
      yield
        log.info(
          "wrote {} ({} bytes, snapshot {})",
          vp.value,
          Integer.valueOf(newBytes.length),
          snapshot.map(_.objectKey).getOrElse("none — created")
        )
        WriteResult(vp, ContentHash.ofBytes(newBytes), changed = true, snapshot = snapshot)

  /**
   * Write to a temp file in the SAME directory, then move it over the target.
   *
   * Same directory because `ATOMIC_MOVE` only holds within one filesystem. The point is that a
   * crash can leave the old note or the new note, never a half-written one — Obsidian is watching
   * this directory, and a torn note is corruption the user would see.
   */
  private def atomicWrite(target: Path, bytes: Array[Byte]): Either[WriteError, Unit] =
    try
      val dir = target.getParent
      Files.createDirectories(dir) // the service's folder on its first write
      val tmp = Files.createTempFile(dir, ".iris-", ".tmp")
      try
        Files.write(tmp, bytes)
        try Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        catch
          case _: AtomicMoveNotSupportedException =>
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        Right(())
      finally Files.deleteIfExists(tmp)
    catch case NonFatal(e) => Left(WriteError.Io(e.getMessage))
