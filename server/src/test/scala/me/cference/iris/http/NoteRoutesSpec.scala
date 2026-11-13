package me.cference.iris.http

import me.cference.iris.persistence.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class NoteRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val now = Instant.parse("2026-08-26T12:00:00Z")

  private val person = NoteView(
    path = "Atlas/Notes/People/Alcvin Ramos.md",
    name = "Alcvin Ramos",
    folder = "Atlas/Notes/People",
    frontmatterJson = Some("""{"up":"[[People Map]]"}"""),
    frontmatterRaw = Some("up: \"[[People Map]]\""),
    frontmatterError = None,
    body = "He is the dai-shihan.",
    tags = Vector(TagOnNote("music", "music", "frontmatter")),
    links =
      Vector(LinkView("Michel Dubeau", None, None, embed = false, Some("x/Michel Dubeau.md"))),
    backlinks = Vector(BacklinkView("y/Shakuhachi.md", None)),
    contentHash = "a" * 64,
    sizeBytes = 21L,
    modifiedAt = now
  )

  /** Newest first, as the repository orders them. */
  private val scanHistory = Vector(
    ScanRecord("watch", now.plusSeconds(600), now.plusSeconds(602), 5917, 0, 1, 0, 0),
    ScanRecord("full", now, now.plusSeconds(94), 5917, 5917, 0, 0, 3)
  )

  private val reindexCalls = new AtomicInteger(0)

  private val stub = new NoteQueries:
    def listNotes(folder: Option[String], tag: Option[String], limit: Int, offset: Int) =
      NoteListPage(
        1,
        Vector(
          NoteSummary(person.path, person.name, person.folder, Vector("music"), "a" * 64, 21L, now)
        )
      )
    def getNote(path: String) = Option.when(path == person.path)(person)
    def tagCounts() = Vector(TagCount("music", 3))
    def unresolvedTargets() = Vector(UnresolvedTarget("Missing Note", 2))
    def recentScans(limit: Int) = scanHistory.take(limit)
    def frontmatterKeys(folder: Option[String]) =
      if folder.contains("Atlas/Notes/People") then
        Vector(FrontmatterKey("up", 164, Map("string" -> 160, "array" -> 4)))
      else
        Vector(
          FrontmatterKey("up", 200, Map("string" -> 196, "array" -> 4)),
          FrontmatterKey("tags", 95, Map("array" -> 95))
        )
    def linkGraph(path: String, depth: Int, nodeCap: Int) =
      Option.when(path == person.path)(
        LinkGraph(
          person.path,
          Vector(GraphNode(person.path, person.name)),
          Vector.empty
        )
      )

  private val routes = NoteRoutes(stub, () => { reindexCalls.incrementAndGet(); () }).routes

  "GET /notes" should {
    "list with total" in {
      Get("/notes?tag=music&limit=10") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val page = responseAs[String].parseJson.asJsObject
        page.fields("total") shouldBe JsNumber(1)
      }
    }
    "reject a bad limit" in {
      Get("/notes?limit=0") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "GET /notes/{path}" should {
    "serve a full note view with embedded frontmatter JSON at a url-encoded path" in {
      Get("/notes/Atlas/Notes/People/Alcvin%20Ramos.md") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val obj = responseAs[String].parseJson.asJsObject
        obj.fields("contentHash") shouldBe JsString("a" * 64)
        obj.fields("frontmatter").asJsObject.fields("up") shouldBe JsString("[[People Map]]")
      }
    }
    "404 an unindexed note" in {
      Get("/notes/Nope.md") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "400 a traversal path" in {
      Get("/notes/..%2Fetc%2Fpasswd") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "GET /notes/{path}/backlinks" should {
    "serve backlinks" in {
      Get("/notes/Atlas/Notes/People/Alcvin%20Ramos.md/backlinks") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("y/Shakuhachi.md")
      }
    }
  }

  "GET /tags and /links/unresolved" should {
    "serve counts" in {
      Get("/tags") ~> routes ~> check {
        responseAs[String] should include("music")
      }
      Get("/links/unresolved") ~> routes ~> check {
        responseAs[String] should include("Missing Note")
      }
    }
  }

  "GET /scans" should {
    "serve the history newest first, with both ends of each scan" in {
      Get("/scans") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val scans = responseAs[String].parseJson.asInstanceOf[JsArray].elements
        scans should have size 2
        val newest = scans.head.asJsObject
        newest.fields("kind") shouldBe JsString("watch")
        // Both timestamps, so a reader can see how long the scan took — /metrics cannot say.
        newest.fields("startedAt") shouldBe JsString(now.plusSeconds(600).toString)
        newest.fields("finishedAt") shouldBe JsString(now.plusSeconds(602).toString)
        scans(1).asJsObject.fields("errors") shouldBe JsNumber(3)
      }
    }
    "honour limit" in {
      Get("/scans?limit=1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asInstanceOf[JsArray].elements should have size 1
      }
    }
    "reject a limit outside the bounds rather than silently clamping it" in {
      Get("/scans?limit=0") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      Get("/scans?limit=201") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
    "serve no vault content" in {
      Get("/scans") ~> routes ~> check {
        responseAs[String] should not include "dai-shihan"
        responseAs[String] should not include "Alcvin"
      }
    }
  }

  "GET /frontmatter/keys" should {
    "serve keys with their value-type spread" in {
      Get("/frontmatter/keys") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val keys = responseAs[String].parseJson.asInstanceOf[JsArray].elements
        keys should have size 2
        val up = keys.head.asJsObject
        up.fields("key") shouldBe JsString("up")
        up.fields("notes") shouldBe JsNumber(200)
        // An inconsistently-typed key shows BOTH types rather than one averaged answer.
        up.fields("types").asJsObject.fields("string") shouldBe JsNumber(196)
        up.fields("types").asJsObject.fields("array") shouldBe JsNumber(4)
      }
    }
    "pass the folder filter through" in {
      Get("/frontmatter/keys?folder=Atlas/Notes/People") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val keys = responseAs[String].parseJson.asInstanceOf[JsArray].elements
        keys should have size 1
        keys.head.asJsObject.fields("notes") shouldBe JsNumber(164)
      }
    }
    "serve no frontmatter VALUES — only key and type names" in {
      Get("/frontmatter/keys") ~> routes ~> check {
        // "[[People Map]]" is the stub note's actual `up` value; the shape endpoint must not leak
        // it, nor any body text.
        responseAs[String] should not include "People Map"
        responseAs[String] should not include "dai-shihan"
      }
    }
  }

  "GET /graph" should {
    "serve a bounded graph" in {
      Get("/graph?root=Atlas/Notes/People/Alcvin%20Ramos.md&depth=2") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("nodes")
      }
    }
    "reject an out-of-range depth" in {
      Get("/graph?root=x.md&depth=9") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "POST /admin/reindex" should {
    "accept and trigger" in {
      val before = reindexCalls.get()
      Post("/admin/reindex") ~> routes ~> check {
        status shouldBe StatusCodes.Accepted
      }
      reindexCalls.get() shouldBe before + 1
    }
  }
