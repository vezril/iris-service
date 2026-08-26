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
