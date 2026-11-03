package me.cference.iris.write

import me.cference.iris.domain.{ContentHash, VaultPath}

/** Where a pre-write snapshot ended up, so a restore can find it. */
final case class SnapshotRef(bucket: String, objectKey: String, sha256: ContentHash, size: Long)

enum SnapshotError(val message: String):
  /** No snapshot store configured. The default, and a refusal — never a silent skip. */
  case NotConfigured
      extends SnapshotError("no snapshot store configured; refusing to write without an undo")
  case Unavailable(detail: String) extends SnapshotError(s"snapshot store unavailable: $detail")
  case Rejected(detail: String) extends SnapshotError(s"snapshot rejected: $detail")

/**
 * Saves a note's bytes **before** Iris modifies it. This is the undo button, and the reason it is
 * mandatory: the vault syncs through Obsidian Sync rather than git, so there is no history to
 * recover from if a write goes wrong. The snapshot is the history.
 *
 * Every implementation is create-only — there is no delete, and nothing here can overwrite an
 * existing snapshot, because a snapshot that can be replaced is not a safety net.
 */
trait SnapshotStore:

  /**
   * Store `bytes` as the pre-write state of `path`.
   *
   * Returning `Left` must cause the caller to ABANDON the write (see
   * [[me.cference.iris.write.VaultWriter]]). That is the fail-closed decision: an Apollo outage
   * stops writes rather than letting them proceed unrecoverably. Calvin's vault is worth more than
   * write availability, and a write during an outage is exactly the one you would most want back.
   */
  def snapshot(path: VaultPath, bytes: Array[Byte]): Either[SnapshotError, SnapshotRef]

object SnapshotStore:

  /**
   * The shipped default: refuses everything, so a service deployed without a configured snapshot
   * store cannot write at all. Deny by default, as with [[ServiceRegistry.empty]].
   */
  val unconfigured: SnapshotStore = (_, _) => Left(SnapshotError.NotConfigured)
