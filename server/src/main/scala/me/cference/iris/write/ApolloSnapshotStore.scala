package me.cference.iris.write

import apollostorage.grpc.*
import com.google.protobuf.ByteString as ProtoBytes
import io.grpc.{Status, StatusRuntimeException}
import me.cference.iris.domain.{ContentHash, VaultPath}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.util.zip.CRC32C
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Await
import scala.util.control.NonFatal

/**
 * The real [[SnapshotStore]]: Apollo's object API over gRPC.
 *
 * Takes the [[ObjectApi]] trait rather than a concrete client, so the specs drive it with an
 * in-process fake and never touch a network.
 *
 * **Verified against Apollo's source (`vezril/apollo-storage`, 2026-11-03), not guessed:**
 *   - Checksums are lowercase hex — `md.digest().map("%02x".format(_)).mkString` for MD5 and
 *     `f"${crc.getValue}%08x"` over `java.util.zip.CRC32C` (`S3BlobStore.scala:64-80`).
 *   - Apollo verifies **only if BOTH `expected_crc32c` and `expected_md5` are non-empty**
 *     (`ObjectApiImpl.scala:233`). Supplying one alone means the check is silently SKIPPED — no
 *     error, no rejection. So this sends both, always; sending one would buy nothing while looking
 *     like integrity.
 *   - On mismatch Apollo deletes the object and fails `FAILED_PRECONDITION`, so a corrupt snapshot
 *     never remains retrievable.
 *   - A put to an existing key bumps `generation` rather than failing (`BucketDomain.scala:41`), so
 *     the store is versioned, not create-only. Keys here are content-addressed, which makes a
 *     repeat snapshot of identical bytes a harmless no-op rather than a clobber.
 *   - The bucket must exist first; `BucketNotFound` is a real error. Hence [[ensureBucket]].
 */
final class ApolloSnapshotStore(
    api: ObjectApi,
    bucket: String,
    callTimeout: FiniteDuration,
    chunkSize: Int = 256 * 1024
) extends SnapshotStore:

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Create the snapshot bucket, tolerating "already exists".
   *
   * Call at boot. Returns the error rather than throwing so the caller can decide: a service that
   * cannot reach Apollo still starts and serves reads — it simply refuses writes, which is the
   * fail-closed posture rather than a crash loop over a feature phase 1 does not use.
   */
  def ensureBucket(): Either[SnapshotError, Unit] =
    try
      Await.result(api.createBucket(CreateBucketRequest(bucket)), callTimeout)
      log.info("apollo snapshot bucket '{}' ready", bucket)
      Right(())
    catch
      case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.ALREADY_EXISTS =>
        log.info("apollo snapshot bucket '{}' already exists", bucket)
        Right(())
      case NonFatal(e) =>
        Left(classify(e))

  /**
   * Key layout: `notes/<vault path>/<sha256>`.
   *
   * Path-prefixed rather than purely content-addressed, because the question a restore asks is
   * "what did THIS note look like before?" — a prefix list answers that, where a bare content hash
   * would be unfindable without a separate index. The sha suffix still dedups identical bytes for
   * the same note.
   *
   * Apollo's `ObjectName` permits slashes, spaces and unicode, forbidding only NUL, backslashes, a
   * leading `/`, `.`/`..` segments, and >1024 UTF-8 bytes (`ObjectName.scala:21-29`). A
   * [[ConfinedPath]] already satisfies every one of those — it has no backslashes, is relative, and
   * has no dot segments — so no escaping is needed. Length is the one real limit, checked below.
   */
  private[write] def objectKey(path: VaultPath, sha: ContentHash): String =
    s"notes/${path.value}/${sha.value}"

  def snapshot(path: VaultPath, bytes: Array[Byte]): Either[SnapshotError, SnapshotRef] =
    val sha = ContentHash.ofBytes(bytes)
    val key = objectKey(path, sha)
    val keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length

    if keyBytes > 1024 then
      // Refuse rather than truncate: a truncated key could collide with another note's snapshot,
      // and a snapshot that might be another note's is worse than no snapshot.
      Left(SnapshotError.Rejected(s"snapshot key is $keyBytes UTF-8 bytes, Apollo's limit is 1024"))
    else
      val header = PutHeader(
        bucket = bucket,
        `object` = key,
        contentType = "text/markdown; charset=utf-8",
        expectedCrc32C = crc32cHex(bytes),
        expectedMd5 = md5Hex(bytes)
      )
      val requests: Source[PutObjectRequest, NotUsed] =
        Source.single(PutObjectRequest(PutObjectRequest.Payload.Header(header))) ++
          Source
            .fromIterator(() => bytes.grouped(chunkSize))
            .map(c => PutObjectRequest(PutObjectRequest.Payload.Chunk(ProtoBytes.copyFrom(c))))

      try
        val response = Await.result(api.putObject(requests), callTimeout)
        // Apollo verified the checksums on ingest; this catches a transport that delivered a
        // different number of bytes than we think we sent.
        if response.size != bytes.length.toLong then
          Left(
            SnapshotError.Rejected(
              s"apollo stored ${response.size} bytes, expected ${bytes.length}"
            )
          )
        else
          log.debug("snapshotted {} to {} (generation {})", path.value, key, response.generation)
          Right(SnapshotRef(bucket, key, sha, response.size))
      catch case NonFatal(e) => Left(classify(e))

  /**
   * Classify a failure, biased toward refusing the write.
   *
   * Anything unrecognized becomes `Unavailable`, which the writer treats as "abandon". That is the
   * deliberate direction: the only safe reading of an unclassified error is that we do not know
   * whether the snapshot exists, and a write without a known-good undo is the thing this whole
   * mechanism exists to prevent.
   */
  private def classify(e: Throwable): SnapshotError =
    e match
      case s: StatusRuntimeException =>
        s.getStatus.getCode match
          case Status.Code.FAILED_PRECONDITION =>
            // Apollo's checksum mismatch, after it deleted the object.
            SnapshotError.Rejected(s"apollo rejected the snapshot: ${s.getStatus.getDescription}")
          case Status.Code.INVALID_ARGUMENT =>
            SnapshotError.Rejected(s"apollo refused the request: ${s.getStatus.getDescription}")
          case Status.Code.NOT_FOUND =>
            SnapshotError.Unavailable(
              s"apollo bucket '$bucket' is missing — was ensureBucket() run at boot?"
            )
          case code =>
            SnapshotError.Unavailable(s"apollo $code: ${s.getStatus.getDescription}")
      case _: java.util.concurrent.TimeoutException =>
        SnapshotError.Unavailable(s"apollo did not answer within $callTimeout")
      case other =>
        SnapshotError.Unavailable(Option(other.getMessage).getOrElse(other.getClass.getName))

  private def md5Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def crc32cHex(bytes: Array[Byte]): String =
    val crc = new CRC32C()
    crc.update(bytes, 0, bytes.length)
    f"${crc.getValue}%08x"
