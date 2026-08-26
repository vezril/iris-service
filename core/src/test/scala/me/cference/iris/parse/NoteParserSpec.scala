package me.cference.iris.parse

import me.cference.iris.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.time.Instant

class NoteParserSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-08-26T12:00:00Z")
  private def path(p: String) =
    VaultPath.parse(p).getOrElse(fail(s"bad fixture path $p"))
  private def parse(p: String, text: String) =
    NoteParser.parse(path(p), text.getBytes(StandardCharsets.UTF_8), now)

  "NoteParser" should {

    "assemble a full note with tags from both sources" in {
      val note = parse(
        "Atlas/Notes/People/Alcvin Ramos.md",
        """---
          |up: "[[People Map]]"
          |tags: [music]
          |---
          |Teacher of [[Michel Dubeau]]. #shakuhachi""".stripMargin
      )
      note.frontmatterError shouldBe None
      note.tags shouldBe Set(
        NoteTag(Tag("music"), TagSource.FrontmatterKey),
        NoteTag(Tag("shakuhachi"), TagSource.Inline)
      )
      note.links.map(_.rawTarget) shouldBe Vector("Michel Dubeau")
      note.modifiedAt shouldBe now
    }

    "hash the raw bytes, stably" in {
      val a = parse("A.md", "same content")
      val b = parse("B.md", "same content")
      val c = parse("C.md", "different content")
      a.contentHash shouldBe b.contentHash
      a.contentHash should not be c.contentHash
      a.contentHash.value should fullyMatch regex "[0-9a-f]{64}"
      a.sizeBytes shouldBe "same content".getBytes(StandardCharsets.UTF_8).length.toLong
    }

    "survive a large body" in {
      val big = "x" * (1400 * 1024) // the vault's outlier is ~1.4MB
      val note = parse("Efforts/Supply.md", big)
      note.body.length shouldBe big.length
    }

    "survive invalid UTF-8 without losing the true hash" in {
      val bytes = Array[Byte]('h', 'i', ' ', 0xff.toByte, 0xfe.toByte)
      val note = NoteParser.parse(path("weird.md"), bytes, now)
      note.contentHash shouldBe ContentHash.ofBytes(bytes)
      note.body should startWith("hi ")
    }

    "keep a note whose frontmatter is broken" in {
      val note = parse("broken.md", "---\n{ nope: [\n---\nstill here [[Link]]")
      note.frontmatter shouldBe None
      note.frontmatterError should not be empty
      note.links.map(_.rawTarget) shouldBe Vector("Link")
    }
  }
