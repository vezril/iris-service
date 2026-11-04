package me.cference.iris.http

import me.cference.iris.domain.ContentHash
import me.cference.iris.write.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * The write endpoint, against a real [[VaultWriter]] over a temp directory and a no-op snapshot
 * store. The status mapping is the security-relevant part, so most of these assert a code.
 */
class NoteWriteRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val token = "tok-mnemo"
  private val registry =
    ServiceRegistry.build(Map("Mnemosyne" -> token, "Dionysus" -> "tok-dio")).toOption.get

  private val root: Path = Files.createTempDirectory("iris-write-routes")
  private val rel = "Atlas/Olympus/Mnemosyne/note.md"

  /** Accepts everything, so the specs exercise the ROUTE rather than the snapshot path. */
  private val okSnapshots: SnapshotStore = (p, b) =>
    Right(SnapshotRef("iris", s"snap/${p.value}", ContentHash.ofBytes(b), b.length.toLong))

  private def routes(snapshots: SnapshotStore = okSnapshots) =
    NoteWriteRoutes(registry, VaultWriter(root, snapshots)).routes

  private def seed(content: String): ContentHash =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    ContentHash.ofBytes(content.getBytes(StandardCharsets.UTF_8))

  private def body(json: String) = HttpEntity(ContentTypes.`application/json`, json)

  private def setStatus(hash: Option[ContentHash]) =
    val h = hash.map(x => s""""expectedHash":"${x.value}",""").getOrElse("")
    body(s"""{$h"edits":[{"op":"setFrontmatterKey","key":"status","value":"done"}]}""")

  private def req(path: String, entity: HttpEntity.Strict, tok: Option[String] = Some(token)) =
    val base = Patch(s"/notes/$path", entity)
    tok.fold(base)(t => base.withHeaders(RawHeader(NoteWriteRoutes.TokenHeader, t)))

  "Identity" should {

    "401 with no token, before the path is even considered" in {
      // The ordering property: an unauthenticated caller must not be able to probe paths.
      req(rel, setStatus(None), tok = None) ~> routes() ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }

    "401 on an unknown token, identically for a real and an absurd path" in {
      val a = req(rel, setStatus(None), Some("forged"))
      val b = req("Atlas/Notes/People/Alcvin%20Ramos.md", setStatus(None), Some("forged"))
      a ~> routes() ~> check(status shouldBe StatusCodes.Unauthorized)
      b ~> routes() ~> check(status shouldBe StatusCodes.Unauthorized)
    }

    "not echo the presented token back" in {
      req(rel, setStatus(None), Some("forged-secret")) ~> routes() ~> check {
        responseAs[String] should not include "forged-secret"
      }
    }
  }

  "Confinement" should {

    "403 for a valid token aimed at Calvin's own notes" in {
      req("Atlas/Notes/People/Alcvin%20Ramos.md", setStatus(None)) ~> routes() ~> check {
        status shouldBe StatusCodes.Forbidden
      }
    }

    "403 for another service's folder" in {
      req("Atlas/Olympus/Dionysus/x.md", setStatus(None)) ~> routes() ~> check {
        status shouldBe StatusCodes.Forbidden
      }
    }

    "403 for the sibling-prefix path" in {
      req("Atlas/Olympus/Mnemosyne2/x.md", setStatus(None)) ~> routes() ~> check {
        status shouldBe StatusCodes.Forbidden
      }
    }

    "refuse a traversal path — pekko normalizes it, confinement then rejects it" in {
      // pekko-http collapses '..' while parsing the URI, so the route never sees the segment and
      // VaultPath.parse never gets to reject it. The path arrives as 'Atlas/x.md', outside the
      // scope, and is refused as 403. Still refused — just one layer earlier than expected.
      // The direct '..' rejection is covered at unit level in WriteScopeSpec.
      req("Atlas/Olympus/Mnemosyne/../../x.md", setStatus(None)) ~> routes() ~> check {
        status shouldBe StatusCodes.Forbidden
      }
    }
  }

  "The optimistic lock over HTTP" should {

    "200 and report the new hash when the presented hash matches" in {
      val hash = seed("---\nstatus: draft\n---\nbody\n")
      req(rel, setStatus(Some(hash))) ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        val out = responseAs[String]
        out should include("\"changed\":true")
        out should include("\"contentHash\"")
      }
    }

    "409 on a stale hash" in {
      seed("---\nstatus: draft\n---\nbody\n")
      val stale = ContentHash.ofBytes("other".getBytes(StandardCharsets.UTF_8))
      req(rel, setStatus(Some(stale))) ~> routes() ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }

    "428 when an existing note is patched with no hash at all" in {
      seed("body\n")
      req(rel, setStatus(None)) ~> routes() ~> check {
        status shouldBe StatusCodes.PreconditionRequired
      }
    }

    "400 on a malformed hash" in {
      req(
        rel,
        body("""{"expectedHash":"nothex","edits":[{"op":"removeFrontmatterKey","key":"a"}]}""")
      ) ~>
        routes() ~> check(status shouldBe StatusCodes.BadRequest)
    }
  }

  "The snapshot failure path" should {

    "503 when the snapshot store is unavailable, and leave the note untouched" in {
      val before = "---\nstatus: draft\n---\nbody\n"
      val hash = seed(before)
      val down: SnapshotStore = (_, _) => Left(SnapshotError.Unavailable("apollo down"))
      req(rel, setStatus(Some(hash))) ~> routes(down) ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
      new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8) shouldBe before
    }

    "503 when no snapshot store is configured" in {
      val hash = seed("x\n")
      req(rel, setStatus(Some(hash))) ~> routes(SnapshotStore.unconfigured) ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }

    "502 when the snapshot was REJECTED — not the same as unavailable" in {
      val hash = seed("y\n")
      val bad: SnapshotStore = (_, _) => Left(SnapshotError.Rejected("checksum mismatch"))
      req(rel, setStatus(Some(hash))) ~> routes(bad) ~> check {
        status shouldBe StatusCodes.BadGateway
      }
    }
  }

  "The request body" should {

    "422 when the note's structure refuses the patch" in {
      val hash = seed("---\na: 1\nb: 2\na: 3\n---\nbody\n") // duplicate key
      req(
        rel,
        body(
          s"""{"expectedHash":"${hash.value}","edits":[{"op":"setFrontmatterKey","key":"a","value":"9"}]}"""
        )
      ) ~>
        routes() ~> check(status shouldBe StatusCodes.UnprocessableContent)
    }

    "400 on an unknown op, an empty edit list, and a non-object body" in {
      val h = seed("z\n").value
      req(rel, body(s"""{"expectedHash":"$h","edits":[{"op":"nope"}]}""")) ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      req(rel, body(s"""{"expectedHash":"$h","edits":[]}""")) ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      req(rel, body("""["not","an","object"]""")) ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "derive the managed section's service from the TOKEN, not the request" in {
      // The security property: a caller must not be able to name the block it writes, or it could
      // edit another service's section inside a note in its own folder.
      Files.deleteIfExists(root.resolve(rel))
      req(
        rel,
        body("""{"edits":[{"op":"replaceManagedSection","service":"Dionysus","content":"mine"}]}""")
      ) ~> routes() ~> check {
        status shouldBe StatusCodes.OK
      }
      val written = new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8)
      written should include("<!-- iris:begin Mnemosyne -->")
      written should not include "Dionysus"
    }
  }
