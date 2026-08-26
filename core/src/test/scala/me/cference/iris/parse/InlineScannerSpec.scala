package me.cference.iris.parse

import me.cference.iris.domain.{Tag, WikiLink}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class InlineScannerSpec extends AnyWordSpec with Matchers:

  "InlineScanner" should {

    "extract a plain wikilink" in {
      InlineScanner.scan("see [[Michel Dubeau]] for lessons").links shouldBe
        Vector(WikiLink("Michel Dubeau", None, None, embed = false))
    }

    "extract alias and header forms" in {
      val s = InlineScanner.scan("with [[Jacinthe Marcil|Jazz]] at [[Shakuhachi#History]]")
      s.links shouldBe Vector(
        WikiLink("Jacinthe Marcil", None, Some("Jazz"), embed = false),
        WikiLink("Shakuhachi", Some("History"), None, embed = false)
      )
    }

    "extract the full target#header|alias form" in {
      InlineScanner.scan("[[Note#Sec|shown]]").links shouldBe
        Vector(WikiLink("Note", Some("Sec"), Some("shown"), embed = false))
    }

    "mark embeds" in {
      InlineScanner.scan("![[scan.png]]").links shouldBe
        Vector(WikiLink("scan.png", None, None, embed = true))
    }

    "not match links inside fenced code blocks" in {
      val body =
        """before [[Real]]
          |```
          |[[NotALink]] #nottag
          |```
          |after [[AlsoReal]]""".stripMargin
      val s = InlineScanner.scan(body)
      s.links.map(_.rawTarget) shouldBe Vector("Real", "AlsoReal")
      s.tags shouldBe empty
    }

    "not match links or tags inside inline code" in {
      val s = InlineScanner.scan("use `[[NotALink]]` and `#nottag` but [[Real]] #real")
      s.links.map(_.rawTarget) shouldBe Vector("Real")
      s.tags shouldBe Vector(Tag("real"))
    }

    "treat an unclosed backtick as text (the link after it still counts)" in {
      InlineScanner.scan("a ` stray [[Link]]").links.map(_.rawTarget) shouldBe Vector("Link")
    }

    "extract inline tags, including nested and unicode" in {
      InlineScanner.scan("work on #3d-printing and #Games/Mobile and #électricité").tags shouldBe
        Vector(Tag("3d-printing"), Tag("Games/Mobile"), Tag("électricité"))
    }

    "not treat a heading or a bare number as a tag" in {
      val s = InlineScanner.scan("# Heading\nissue #42 is not a tag")
      s.tags shouldBe empty
    }

    "not double-count a link header as a tag" in {
      InlineScanner.scan("[[Note#Header]]").tags shouldBe empty
    }

    "keep scanning a line with multiple links" in {
      InlineScanner.scan("[[A]] then [[B|b]] then [[C]]").links.map(_.rawTarget) shouldBe
        Vector("A", "B", "C")
    }
  }
