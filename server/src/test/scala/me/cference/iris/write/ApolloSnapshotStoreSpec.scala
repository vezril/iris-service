package me.cference.iris.write

import apollostorage.grpc.*
import com.google.protobuf.ByteString as ProtoBytes
import io.grpc.{Status, StatusRuntimeException}
import me.cference.iris.domain.{ContentHash, VaultPath}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.NotUsed
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32C
import scala.concurrent.duration.*
import scala.concurrent.Future

/**
 * Drives the adapter through an in-process fake [[ObjectApi]] — no network, no Apollo, no Docker.
 *
 * The fake deliberately reimplements the two behaviours that were read out of Apollo's source, so
 * that if either is wrong the specs are wrong in the same direction and the real smoke test catches
 * it (see `docs/handoff-homelab-verification.md`):
 *   - checksums are verified ONLY when both are supplied (`ObjectApiImpl.scala:233`)
 *   - comparison is lowercase hex, CRC32C + MD5 (`S3BlobStore.scala:64-80`)
 */
class ApolloSnapshotStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit("apollo-snapshot-spec")
  given org.apache.pekko.actor.ClassicActorSystemProvider = testKit.system
  private given scala.concurrent.ExecutionContext = testKit.system.executionContext

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val path = VaultPath.parse("Atlas/Olympus/Mnemosyne/note.md").toOption.get
  private val payload = "# a note\n\nwith body\n".getBytes(StandardCharsets.UTF_8)

  private def md5Hex(b: Array[Byte]) =
    MessageDigest.getInstance("MD5").digest(b).map("%02x".format(_)).mkString

  private def crc32cHex(b: Array[Byte]) =
    val c = new CRC32C(); c.update(b, 0, b.length); f"${c.getValue}%08x"

  /** Records what it was sent; mirrors Apollo's verification rules. */
  private class FakeApi(
      failWith: Option[Throwable] = None,
      sizeOverride: Option[Long] = None
  ) extends ObjectApi:
    var headers: Vector[PutHeader] = Vector.empty
    var received: Vector[Byte] = Vector.empty
    var bucketsCreated: Vector[String] = Vector.empty

    def putObject(in: Source[PutObjectRequest, NotUsed]): Future[PutObjectResponse] =
      failWith match
        case Some(t) => Future.failed(t)
        case None =>
          in.runWith(Sink.seq).map { msgs =>
            val hdr =
              msgs.collectFirst { case PutObjectRequest(PutObjectRequest.Payload.Header(h), _) =>
                h
              }
            val body = msgs.collect { case PutObjectRequest(PutObjectRequest.Payload.Chunk(c), _) =>
              c.toByteArray
            }
            val bytes = body.flatten.toArray
            headers = headers ++ hdr.toVector
            received = bytes.toVector
            hdr.foreach(h => stored = stored.updated(h.`object`, bytes))

            // Apollo verifies only when BOTH are present (verified from source).
            hdr.foreach { h =>
              if h.expectedCrc32C.nonEmpty && h.expectedMd5.nonEmpty then
                val ok = h.expectedCrc32C == crc32cHex(bytes) && h.expectedMd5 == md5Hex(bytes)
                if !ok then
                  throw new StatusRuntimeException(
                    Status.FAILED_PRECONDITION.withDescription("checksum mismatch")
                  )
            }
            PutObjectResponse(
              generation = 1L,
              crc32C = crc32cHex(bytes),
              md5 = md5Hex(bytes),
              size = sizeOverride.getOrElse(bytes.length.toLong)
            )
          }

    def createBucket(r: CreateBucketRequest): Future[BucketResponse] =
      failWith match
        case Some(t) => Future.failed(t)
        case None =>
          bucketsCreated = bucketsCreated :+ r.bucket
          Future.successful(BucketResponse(bucket = r.bucket))

    /** Keyed store the read side is served from, so list/fetch see what put actually wrote. */
    var stored: Map[String, Array[Byte]] = Map.empty
    var listPageSizes: Vector[Int] = Vector.empty

    def listObjects(r: ListObjectsRequest): Future[ListObjectsResponse] =
      listPageSizes = listPageSizes :+ r.pageSize
      failWith match
        case Some(t) => Future.failed(t)
        case None =>
          val hits = stored.toVector
            .filter(_._1.startsWith(r.prefix))
            .zipWithIndex
            .map { case ((k, b), i) =>
              ObjectEntry(`object` = k, generation = (i + 1).toLong, size = b.length.toLong)
            }
          Future.successful(ListObjectsResponse(objects = hits))

    def getObject(r: GetObjectRequest): Source[GetObjectResponse, NotUsed] =
      failWith match
        case Some(t) => Source.failed(t).mapMaterializedValue(_ => NotUsed)
        case None =>
          stored.get(r.`object`) match
            case None =>
              Source
                .failed(new StatusRuntimeException(Status.NOT_FOUND))
                .mapMaterializedValue(_ => NotUsed)
            case Some(b) =>
              Source.single(
                GetObjectResponse(
                  GetObjectResponse.Payload.Header(
                    ObjectMetadata(bucket = r.bucket, `object` = r.`object`, size = b.length.toLong)
                  )
                )
              ) ++ Source.single(
                GetObjectResponse(GetObjectResponse.Payload.Chunk(ProtoBytes.copyFrom(b)))
              )

    // Unused by the snapshot path.
    def deleteBucket(r: DeleteBucketRequest) = Future.failed(new UnsupportedOperationException)
    def headObject(r: HeadObjectRequest) = Future.failed(new UnsupportedOperationException)
    def deleteObject(r: DeleteObjectRequest) = Future.failed(new UnsupportedOperationException)
    def listBuckets(r: ListBucketsRequest) = Future.failed(new UnsupportedOperationException)

  private def store(api: ObjectApi) = ApolloSnapshotStore(api, "iris", 5.seconds)

  "snapshot" should {

    "send the bytes and return a ref" in {
      val api = FakeApi()
      val r = store(api).snapshot(path, payload)
      r.map(_.bucket) shouldBe Right("iris")
      r.map(_.size) shouldBe Right(payload.length.toLong)
      r.map(_.sha256) shouldBe Right(ContentHash.ofBytes(payload))
      api.received.toArray shouldBe payload
    }

    "send BOTH checksums, in lowercase hex" in {
      // Sending only one means Apollo silently skips verification — the finding that motivated
      // this. Both, or the integrity claim is a fiction.
      val api = FakeApi()
      store(api).snapshot(path, payload).isRight shouldBe true
      val h = api.headers.head
      h.expectedMd5 shouldBe md5Hex(payload)
      h.expectedCrc32C shouldBe crc32cHex(payload)
      h.expectedMd5 should fullyMatch regex "[0-9a-f]{32}"
      h.expectedCrc32C should fullyMatch regex "[0-9a-f]{8}"
    }

    "key by path then sha, so a restore can find a note's history" in {
      val api = FakeApi()
      val r = store(api).snapshot(path, payload)
      r.map(_.objectKey) shouldBe
        Right(s"notes/Atlas/Olympus/Mnemosyne/note.md/${ContentHash.ofBytes(payload).value}")
    }

    "chunk a payload larger than the chunk size, preserving the bytes exactly" in {
      val api = FakeApi()
      val big = Array.tabulate(1000)(i => (i % 251).toByte)
      val small = ApolloSnapshotStore(api, "iris", 5.seconds, chunkSize = 64)
      small.snapshot(path, big).isRight shouldBe true
      api.received.toArray shouldBe big
      api.headers.size shouldBe 1 // exactly one header, the rest chunks
    }

    "handle an empty note without sending a malformed stream" in {
      val api = FakeApi()
      store(api).snapshot(path, Array.emptyByteArray).map(_.size) shouldBe Right(0L)
      api.headers.size shouldBe 1
    }

    "treat a checksum mismatch as Rejected, not Unavailable" in {
      // Apollo deletes the object and fails FAILED_PRECONDITION. A corrupt snapshot is a
      // different problem from an unreachable Apollo and must not be retried blindly.
      val api = FakeApi(failWith =
        Some(new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("checksum")))
      )
      store(api).snapshot(path, payload).left.map(_.getClass.getSimpleName) shouldBe
        Left("Rejected")
    }

    "treat a size disagreement as Rejected" in {
      val api = FakeApi(sizeOverride = Some(1L))
      val r = store(api).snapshot(path, payload)
      r.left.map(_.getClass.getSimpleName) shouldBe Left("Rejected")
      r.left.toOption.get.message should include("expected")
    }

    "classify an unreachable Apollo as Unavailable" in {
      val api = FakeApi(failWith =
        Some(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("connection refused")))
      )
      store(api).snapshot(path, payload).left.map(_.getClass.getSimpleName) shouldBe
        Left("Unavailable")
    }

    "classify a missing bucket as Unavailable, naming ensureBucket" in {
      val api = FakeApi(failWith = Some(new StatusRuntimeException(Status.NOT_FOUND)))
      val r = store(api).snapshot(path, payload)
      r.left.map(_.getClass.getSimpleName) shouldBe Left("Unavailable")
      r.left.toOption.get.message should include("ensureBucket")
    }

    "classify an UNRECOGNIZED failure as Unavailable — refuse, never assume success" in {
      // The bias that matters: an unclassified error means we do not know whether the snapshot
      // exists, and the writer must abandon rather than write without a known-good undo.
      val api = FakeApi(failWith = Some(new RuntimeException("something new and unmapped")))
      store(api).snapshot(path, payload).left.map(_.getClass.getSimpleName) shouldBe
        Left("Unavailable")
    }

    "refuse a key over Apollo's 1024-byte limit rather than truncating it" in {
      // A truncated key could collide with another note's snapshot, which is worse than none.
      val deep = VaultPath
        .parse("Atlas/Olympus/Mnemosyne/" + ("x" * 60 + "/") * 17 + "note.md")
        .toOption
        .get
      val api = FakeApi()
      val r = store(api).snapshot(deep, payload)
      r.left.map(_.getClass.getSimpleName) shouldBe Left("Rejected")
      r.left.toOption.get.message should include("1024")
      api.headers shouldBe empty // nothing was sent
    }
  }

  "ensureBucket" should {

    "create the bucket" in {
      val api = FakeApi()
      store(api).ensureBucket() shouldBe Right(())
      api.bucketsCreated shouldBe Vector("iris")
    }

    "treat ALREADY_EXISTS as success — it is idempotent by intent" in {
      val api = FakeApi(failWith = Some(new StatusRuntimeException(Status.ALREADY_EXISTS)))
      store(api).ensureBucket() shouldBe Right(())
    }

    "report an unreachable Apollo instead of throwing, so boot still serves reads" in {
      val api = FakeApi(failWith = Some(new StatusRuntimeException(Status.UNAVAILABLE)))
      store(api).ensureBucket().left.map(_.getClass.getSimpleName) shouldBe Left("Unavailable")
    }
  }

  "The writer, wired to this store" should {

    "abandon a write when Apollo is down, leaving the note untouched" in {
      // The end-to-end fail-closed path, with the real adapter rather than a stub store.
      val dir = java.nio.file.Files.createTempDirectory("iris-apollo-e2e")
      try
        val rel = "Atlas/Olympus/Mnemosyne/note.md"
        val target = dir.resolve(rel)
        java.nio.file.Files.createDirectories(target.getParent)
        val before = "---\nstatus: draft\n---\nbody\n"
        java.nio.file.Files.write(target, before.getBytes(StandardCharsets.UTF_8))

        val api = FakeApi(failWith = Some(new StatusRuntimeException(Status.UNAVAILABLE)))
        val scope = WriteScope.forService("Mnemosyne").toOption.get
        val confined = ConfinedPath.confine(scope, rel).toOption.get
        val hash = ContentHash.ofBytes(before.getBytes(StandardCharsets.UTF_8))

        val r = VaultWriter(dir, store(api))
          .write(confined, Some(hash), Seq(NoteEdit.SetFrontmatterKey("status", "done")))

        r.left.map(_.getClass.getSimpleName) shouldBe Left("SnapshotFailed")
        new String(java.nio.file.Files.readAllBytes(target), StandardCharsets.UTF_8) shouldBe before
      finally
        java.nio.file.Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => java.nio.file.Files.deleteIfExists(p))
    }
  }

  "The archive" should {

    "round-trip: a snapshot taken is a snapshot listed and fetched" in {
      // The property that makes the undo button real rather than claimed.
      val api = FakeApi()
      val store = ApolloSnapshotStore(api, "iris", 5.seconds)
      store.snapshot(path, payload).isRight shouldBe true

      val listed = store.list(path, 50)
      listed.map(_.map(_.sha256)) shouldBe Right(Vector(ContentHash.ofBytes(payload)))

      store.fetch(path, ContentHash.ofBytes(payload)).map(_.toVector) shouldBe
        Right(payload.toVector)
    }

    "list only the asked-for note, not another note's snapshots" in {
      val api = FakeApi()
      val store = ApolloSnapshotStore(api, "iris", 5.seconds)
      val other = VaultPath.parse("Atlas/Olympus/Mnemosyne/other.md").toOption.get
      store.snapshot(path, payload)
      store.snapshot(other, "different".getBytes(StandardCharsets.UTF_8))

      store.list(path, 50).map(_.size) shouldBe Right(1)
      store.list(other, 50).map(_.size) shouldBe Right(1)
    }

    "pass the limit through as the page size" in {
      val api = FakeApi()
      ApolloSnapshotStore(api, "iris", 5.seconds).list(path, 7)
      api.listPageSizes shouldBe Vector(7)
    }

    "report an absent snapshot as NotFound, NOT as an outage" in {
      // On a GET, Apollo's NOT_FOUND means the object is missing. On a PUT the same code means the
      // bucket is gone. Collapsing them would report a missing snapshot as Apollo being down.
      val api = FakeApi()
      val store = ApolloSnapshotStore(api, "iris", 5.seconds)
      store.fetch(path, ContentHash.ofBytes("never stored".getBytes(StandardCharsets.UTF_8))) match
        case Left(SnapshotError.NotFound(_)) => succeed
        case other => fail(s"expected NotFound, got $other")
    }

    "REFUSE bytes that do not hash to the name they are filed under" in {
      // Corruption in the archive. Returning these as a restore candidate would be worse than
      // reporting that there is nothing to restore.
      val api = FakeApi()
      val store = ApolloSnapshotStore(api, "iris", 5.seconds)
      store.snapshot(path, payload)
      val sha = ContentHash.ofBytes(payload)
      // Tamper with the stored bytes behind the store's back.
      api.stored = api.stored.updated(
        s"notes/${path.value}/${sha.value}",
        "tampered".getBytes(StandardCharsets.UTF_8)
      )
      store.fetch(path, sha) match
        case Left(SnapshotError.Rejected(detail)) => detail should include("hashes to")
        case other => fail(s"expected Rejected, got $other")
    }

    "classify an unreachable archive as Unavailable on both list and fetch" in {
      val api = FakeApi(failWith =
        Some(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("refused")))
      )
      val store = ApolloSnapshotStore(api, "iris", 5.seconds)
      store.list(path, 10).left.map(_.getClass.getSimpleName) shouldBe Left("Unavailable")
      store
        .fetch(path, ContentHash.ofBytes(payload))
        .left
        .map(_.getClass.getSimpleName) shouldBe Left("Unavailable")
    }
  }
