package me.cference.iris.http

import me.cference.iris.domain.{ContentHash, VaultPath}
import me.cference.iris.write.{SnapshotArchive, SnapshotEntry, SnapshotError}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets

class SnapshotRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val notePath = "Atlas/Olympus/Mnemosyne/note.md"
  private val older = "---\nstatus: draft\n---\nbody\n".getBytes(StandardCharsets.UTF_8)
  private val newer = "---\nstatus: review\n---\nbody\n".getBytes(StandardCharsets.UTF_8)
  private val olderSha = ContentHash.ofBytes(older)
  private val newerSha = ContentHash.ofBytes(newer)

  private def archive(
      entries: Vector[SnapshotEntry] = Vector(
        SnapshotEntry(olderSha, older.length.toLong, 1L),
        SnapshotEntry(newerSha, newer.length.toLong, 2L)
      ),
      listError: Option[SnapshotError] = None,
      fetchError: Option[SnapshotError] = None,
      bytes: Map[String, Array[Byte]] = Map(
        olderSha.value -> older,
        newerSha.value -> newer
      )
  ): SnapshotArchive =
    new SnapshotArchive:
      var listedLimit: Int = -1
      def list(path: VaultPath, limit: Int) =
        listedLimit = limit
        listError.toLeft(entries.sortBy(-_.generation))
      def fetch(path: VaultPath, sha256: ContentHash) =
        fetchError.toLeft(bytes.getOrElse(sha256.value, Array.emptyByteArray))

  private def routes(a: SnapshotArchive = archive()) = SnapshotRoutes(a).routes

  "GET /notes/{path}/snapshots" should {

    "list what is recoverable, newest first" in {
      Get(s"/notes/${enc(notePath)}/snapshots") ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include(s""""path":"$notePath"""")
        // generation 2 before generation 1
        body.indexOf(newerSha.value) should be < body.indexOf(olderSha.value)
        body should include(""""generation":2""")
      }
    }

    "return an empty list rather than a 404 when nothing is held" in {
      // An empty archive is a legitimate answer: a note that has never been written has no
      // snapshots. It is NOT proof that no undo exists — Apollo's listing is eventually
      // consistent — which is why this is 200-with-empty rather than 404.
      Get(s"/notes/${enc(notePath)}/snapshots") ~> routes(archive(entries = Vector.empty)) ~>
        check {
          status shouldBe StatusCodes.OK
          responseAs[String] should include(""""snapshots":[]""")
        }
    }

    "reject a limit outside 1..500" in {
      Get(s"/notes/${enc(notePath)}/snapshots?limit=0") ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      Get(s"/notes/${enc(notePath)}/snapshots?limit=501") ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "503 when the archive is unreachable" in {
      Get(s"/notes/${enc(notePath)}/snapshots") ~>
        routes(archive(listError = Some(SnapshotError.Unavailable("apollo down")))) ~> check {
          status shouldBe StatusCodes.ServiceUnavailable
        }
    }

    "501 when no archive is configured" in {
      Get(s"/notes/${enc(notePath)}/snapshots") ~>
        routes(archive(listError = Some(SnapshotError.NotConfigured))) ~> check {
          status shouldBe StatusCodes.NotImplemented
        }
    }
  }

  "GET /notes/{path}/snapshots/{sha}" should {

    "return the exact bytes as markdown" in {
      Get(s"/notes/${enc(notePath)}/snapshots/${olderSha.value}") ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        contentType.mediaType.subType shouldBe "markdown"
        responseAs[String] shouldBe new String(older, StandardCharsets.UTF_8)
      }
    }

    "404 a snapshot that is not in the archive" in {
      Get(s"/notes/${enc(notePath)}/snapshots/${"a" * 64}") ~>
        routes(archive(fetchError = Some(SnapshotError.NotFound("absent")))) ~> check {
          status shouldBe StatusCodes.NotFound
        }
    }

    "502 when the stored bytes do not match their own hash" in {
      // The archive is corrupt. Not the caller's fault and not fixed by retrying — and offering
      // the bytes anyway would present a corrupt file as a restore candidate.
      Get(s"/notes/${enc(notePath)}/snapshots/${olderSha.value}") ~>
        routes(archive(fetchError = Some(SnapshotError.Rejected("hash mismatch")))) ~> check {
          status shouldBe StatusCodes.BadGateway
        }
    }

    "400 a hash that is not 64 lowercase hex chars" in {
      Get(s"/notes/${enc(notePath)}/snapshots/not-a-hash") ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      Get(s"/notes/${enc(notePath)}/snapshots/${"A" * 64}") ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "Path parsing" should {

    "reject a non-snapshots path so the note routes can have it" in {
      SnapshotRoutes.parse("Atlas/Olympus/Mnemosyne/note.md") shouldBe None
      SnapshotRoutes.parse("Atlas/Olympus/Mnemosyne/note.md/backlinks") shouldBe None
    }

    "match on the LAST /snapshots, so a folder named snapshots still works" in {
      // A note genuinely living under a 'snapshots' folder must resolve to the note.
      SnapshotRoutes.parse("Atlas/snapshots/note.md/snapshots") shouldBe
        Some(("Atlas/snapshots/note.md", None))
      SnapshotRoutes.parse(s"Atlas/snapshots/note.md/snapshots/${olderSha.value}") shouldBe
        Some(("Atlas/snapshots/note.md", Some(olderSha.value)))
    }

    "not treat a deeper path under /snapshots as a hash" in {
      SnapshotRoutes.parse("a/note.md/snapshots/x/y") shouldBe None
    }

    "tolerate a trailing slash" in {
      SnapshotRoutes.parse("a/note.md/snapshots/") shouldBe Some(("a/note.md", None))
    }
  }

  private def enc(p: String): String =
    p.split('/').map(java.net.URLEncoder.encode(_, "UTF-8").replace("+", "%20")).mkString("/")
