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
   * The snapshot is not there. Only ever produced by the READ side: on a put, Apollo's NOT_FOUND
   * means the BUCKET is missing, which is an availability problem, not an absent snapshot.
   */
  case NotFound(detail: String) extends SnapshotError(s"no such snapshot: $detail")

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

/** One stored snapshot of a note, as the archive lists it. Newest first, by generation. */
final case class SnapshotEntry(
    sha256: ContentHash,
    size: Long,
    generation: Long
)

/**
 * The read half of the undo button: finding and retrieving what was saved.
 *
 * Deliberately a separate trait from [[SnapshotStore]]. The writer needs only `snapshot`, and
 * keeping that interface to one method means a writer cannot be handed the ability to read the
 * archive, nor the archive the ability to write it. It also keeps the write-side stubs in the specs
 * honest — they implement exactly what the writer can call.
 *
 * **Why this exists at all:** before it, snapshots were write-only. A safety net nobody can inspect
 * is a claim rather than a guarantee — there was no way to confirm from Iris that an undo actually
 * existed, let alone use one. The design calls the snapshots "a vault time machine"; this is the
 * part that lets anyone look inside it.
 */
trait SnapshotArchive:

  /**
   * Snapshots held for `path`, newest first.
   *
   * **Eventually consistent.** Apollo serves listings from a read model, so a snapshot taken
   * moments ago may be absent here while definitely existing. Never treat an empty list as proof
   * that no undo exists.
   */
  def list(path: VaultPath, limit: Int): Either[SnapshotError, Vector[SnapshotEntry]]

  /**
   * Retrieve one snapshot's bytes.
   *
   * Takes the path and hash rather than a storage key, so the key is always CONSTRUCTED here from
   * validated inputs. A caller therefore cannot name an arbitrary object — the only things it can
   * reach are snapshots of the note it asked about.
   *
   * The returned bytes are verified against `sha256` before being handed back: a snapshot that does
   * not hash to its own name is corrupt, and returning it as a restore candidate would be worse
   * than admitting there is nothing to restore.
   */
  def fetch(path: VaultPath, sha256: ContentHash): Either[SnapshotError, Array[Byte]]
