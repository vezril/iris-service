package me.cference.iris.parse

import me.cference.iris.domain.FmValue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FrontmatterParserSpec extends AnyWordSpec with Matchers:

  "FrontmatterParser.split" should {

    "parse the vault's light frontmatter shape" in {
      val text =
        """---
          |up: "[[People Map]]"
          |aliases:
          |  - Alcvin Ryuzen Ramos
          |---
          |He is the dai-shihan of the [[dokyoku]] school.""".stripMargin
      val s = FrontmatterParser.split(text)
      s.frontmatterError shouldBe None
      val fm = s.frontmatter.value
      fm.up shouldBe Some("[[People Map]]")
      fm.aliases shouldBe Vector("Alcvin Ryuzen Ramos")
      fm.raw shouldBe "up: \"[[People Map]]\"\naliases:\n  - Alcvin Ryuzen Ramos"
      s.body shouldBe "He is the dai-shihan of the [[dokyoku]] school."
    }

    "treat a note without a leading fence as all body" in {
      val s = FrontmatterParser.split("Just prose.\n---\nnot frontmatter")
      s.frontmatter shouldBe None
      s.frontmatterError shouldBe None
      s.body should startWith("Just prose.")
    }

    "treat an unclosed fence as body, not frontmatter" in {
      val s = FrontmatterParser.split("---\ntitle: dangling\nno close")
      s.frontmatter shouldBe None
      s.body should include("dangling")
    }

    "keep a note with malformed YAML, carrying the error" in {
      val s = FrontmatterParser.split("---\n{ not: [ valid\n---\nbody survives")
      s.frontmatter shouldBe None
      s.frontmatterError.value should include("unreadable YAML")
      s.body shouldBe "body survives"
    }

    "reject non-mapping frontmatter without dropping the note" in {
      val s = FrontmatterParser.split("---\njust a scalar\n---\nbody")
      s.frontmatter shouldBe None
      s.frontmatterError.value should include("not a YAML mapping")
      s.body shouldBe "body"
    }

    "accept an empty frontmatter block" in {
      val s = FrontmatterParser.split("---\n---\nbody")
      s.frontmatter.value.fields shouldBe empty
      s.body shouldBe "body"
    }

    "read tags as a list" in {
      val s = FrontmatterParser.split("---\ntags:\n  - swgoh\n  - Games/Mobile\n---\n")
      s.frontmatter.value.tags shouldBe Vector("swgoh", "Games/Mobile")
    }

    "read tags as a comma-separated scalar (legacy form)" in {
      // Unquoted, ' #' starts a YAML comment — so the hash-stripping case needs quotes.
      val s = FrontmatterParser.split("---\ntags: \"one, two, #three\"\n---\n")
      s.frontmatter.value.tags shouldBe Vector("one", "two", "three")
    }

    "carry unknown keys structurally" in {
      val s = FrontmatterParser.split("---\ncustom:\n  nested: 42\nflag: true\n---\n")
      val fields = s.frontmatter.value.fields
      fields("flag") shouldBe FmValue.Bool(true)
      fields("custom") shouldBe FmValue.Obj(Map("nested" -> FmValue.Num(BigDecimal(42))))
    }

    "not instantiate arbitrary classes from YAML tags (SafeConstructor)" in {
      val s = FrontmatterParser.split(
        "---\nx: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader []]\n---\n"
      )
      s.frontmatter shouldBe None
      s.frontmatterError.value should include("unreadable YAML")
    }
  }

  extension [A](o: Option[A]) private def value: A = o.getOrElse(fail("expected Some"))
