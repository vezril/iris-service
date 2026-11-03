package me.cference.iris.write

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The patcher is the place a write can corrupt a note, so these specs are mostly about what must
 * NOT change. Several use a note deliberately full of things a YAML round-trip would destroy —
 * comments, key order, single quotes, a block scalar, a list — and assert they come back
 * byte-identical.
 */
class NotePatchSpec extends AnyWordSpec with Matchers:

  /** Everything in here except `status` must survive a patch untouched. */
  private val realistic =
    """---
      |up: "[[People Map]]"
      |# Calvin's own comment, which a YAML re-render would delete
      |aliases:
      |  - Al
      |  - Alcvin
      |tags: [music, shakuhachi]
      |note: 'single quoted on purpose'
      |description: >
      |  a folded block scalar
      |  spanning two lines
      |status: draft
      |---
      |# Alcvin Ramos
      |
      |He is the dai-shihan.
      |""".stripMargin

  private def patch(text: String, edits: NoteEdit*) =
    NotePatch(text, edits).getOrElse(fail("expected the patch to apply"))

  "NotePatch.SetFrontmatterKey" should {

    "change only the named key, leaving every other byte alone" in {
      val out = patch(realistic, NoteEdit.SetFrontmatterKey("status", "published"))

      out should include("""status: "published"""")
      // The things a YAML round-trip would have destroyed:
      out should include("# Calvin's own comment, which a YAML re-render would delete")
      out should include("""up: "[[People Map]]"""")
      out should include("note: 'single quoted on purpose'")
      out should include("tags: [music, shakuhachi]")
      out should include("  - Alcvin")
      out should include("description: >")
      out should include("  spanning two lines")
      // Body untouched.
      out should include("He is the dai-shihan.")
      // And nothing else moved: the only differing line is the one we set.
      val before = realistic.linesIterator.toVector
      val after = out.linesIterator.toVector
      after.length shouldBe before.length
      before.zip(after).count { case (b, a) => b != a } shouldBe 1
    }

    "append a new key without disturbing the existing block" in {
      val out = patch(realistic, NoteEdit.SetFrontmatterKey("iris-indexed", "yes"))
      out should include("""iris-indexed: "yes"""")
      out.linesIterator.toVector.length shouldBe realistic.linesIterator.toVector.length + 1
      out should include("# Calvin's own comment, which a YAML re-render would delete")
      out should include("He is the dai-shihan.")
    }

    "replace a multi-line value entirely, not just its first line" in {
      val out = patch(realistic, NoteEdit.SetFrontmatterKey("aliases", "Al"))
      out should include("""aliases: "Al"""")
      // The old list items must be gone, not orphaned into invalid YAML.
      out should not include "  - Alcvin"
      out should not include "  - Al\n"
      // The key AFTER the list is still present and intact.
      out should include("tags: [music, shakuhachi]")
    }

    "preserve a comment that follows the replaced key rather than swallowing it" in {
      val note = "---\na: 1\n# keep me\nb: 2\n---\nbody\n"
      val out = patch(note, NoteEdit.SetFrontmatterKey("a", "9"))
      out shouldBe "---\na: \"9\"\n# keep me\nb: 2\n---\nbody\n"
    }

    "create a frontmatter block on a note that has none, leaving the body intact" in {
      val note = "# Just a heading\n\nSome prose.\n"
      val out = patch(note, NoteEdit.SetFrontmatterKey("status", "new"))
      out shouldBe "---\nstatus: \"new\"\n---\n# Just a heading\n\nSome prose.\n"
    }

    "be idempotent — the second application changes nothing" in {
      val once = patch(realistic, NoteEdit.SetFrontmatterKey("status", "published"))
      val twice = patch(once, NoteEdit.SetFrontmatterKey("status", "published"))
      twice shouldBe once
    }

    "quote the value, so a service cannot inject sibling keys" in {
      // The injection: an unquoted value containing a newline would become YAML structure and
      // write `evil: true` into Calvin's frontmatter.
      val out = patch(realistic, NoteEdit.SetFrontmatterKey("status", "draft\nevil: true"))
      out should include("""status: "draft\nevil: true"""")
      out should not include "\nevil: true"
    }

    "escape quotes and backslashes in a value" in {
      val out = patch("---\na: 1\n---\n", NoteEdit.SetFrontmatterKey("a", """he said "hi" \ ok"""))
      out should include("""a: "he said \"hi\" \\ ok"""")
    }

    "refuse an illegal key instead of writing it" in {
      NotePatch(realistic, Seq(NoteEdit.SetFrontmatterKey("evil: injected", "x"))) shouldBe
        Left(PatchError.IllegalKey("evil: injected"))
      NotePatch(realistic, Seq(NoteEdit.SetFrontmatterKey("", "x"))).isLeft shouldBe true
    }

    "refuse when the same key appears twice — which one to edit is a guess" in {
      val dupe = "---\na: 1\nb: 2\na: 3\n---\nbody\n"
      NotePatch(dupe, Seq(NoteEdit.SetFrontmatterKey("a", "9"))) shouldBe
        Left(PatchError.AmbiguousKey("a"))
    }

    "not mistake a nested key for a top-level one" in {
      val nested = "---\nouter:\n  status: inner\nstatus: outer\n---\nbody\n"
      val out = patch(nested, NoteEdit.SetFrontmatterKey("status", "set"))
      out should include("  status: inner") // the nested one is untouched
      out should include("""status: "set"""")
    }

    "REFUSE every frontmatter edit when the frontmatter did not parse" in {
      // Iris will not edit a note it cannot read. Fail closed.
      val broken = "---\na: [unclosed\n---\nbody\n"
      NotePatch(
        broken,
        Seq(NoteEdit.SetFrontmatterKey("a", "x")),
        frontmatterError = Some("unreadable YAML")
      ) shouldBe Left(PatchError.UnreadableFrontmatter("unreadable YAML"))
    }
  }

  "NotePatch.RemoveFrontmatterKey" should {

    "remove a key and its multi-line value, leaving neighbours intact" in {
      val out = patch(realistic, NoteEdit.RemoveFrontmatterKey("aliases"))
      out should not include "aliases:"
      out should not include "  - Alcvin"
      out should include("# Calvin's own comment, which a YAML re-render would delete")
      out should include("tags: [music, shakuhachi]")
    }

    "treat an absent key as success, changing nothing" in {
      patch(realistic, NoteEdit.RemoveFrontmatterKey("never-existed")) shouldBe realistic
    }

    "change nothing on a note with no frontmatter" in {
      val note = "just body\n"
      patch(note, NoteEdit.RemoveFrontmatterKey("a")) shouldBe note
    }
  }

  "NotePatch.ReplaceManagedSection" should {

    "append a block when absent, after the body" in {
      val note = "# Heading\n\nProse.\n"
      val out = patch(note, NoteEdit.ReplaceManagedSection("Mnemosyne", "generated line"))
      out shouldBe
        "# Heading\n\nProse.\n<!-- iris:begin Mnemosyne -->\ngenerated line\n<!-- iris:end Mnemosyne -->\n"
    }

    "add a terminator first, so a marker never lands on Calvin's last line" in {
      val note = "No trailing newline"
      val out = patch(note, NoteEdit.ReplaceManagedSection("Mnemosyne", "x"))
      out should startWith("No trailing newline\n")
      out should include("<!-- iris:begin Mnemosyne -->")
    }

    "replace only what is between the markers" in {
      val note =
        "Prose above.\n<!-- iris:begin Mnemosyne -->\nold\nlines\n<!-- iris:end Mnemosyne -->\nProse below.\n"
      val out = patch(note, NoteEdit.ReplaceManagedSection("Mnemosyne", "new"))
      out shouldBe
        "Prose above.\n<!-- iris:begin Mnemosyne -->\nnew\n<!-- iris:end Mnemosyne -->\nProse below.\n"
    }

    "leave another service's block alone" in {
      val note =
        "<!-- iris:begin Dionysus -->\ntheirs\n<!-- iris:end Dionysus -->\n<!-- iris:begin Mnemosyne -->\nmine\n<!-- iris:end Mnemosyne -->\n"
      val out = patch(note, NoteEdit.ReplaceManagedSection("Mnemosyne", "updated"))
      out should include("<!-- iris:begin Dionysus -->\ntheirs\n<!-- iris:end Dionysus -->")
      out should include("<!-- iris:begin Mnemosyne -->\nupdated\n<!-- iris:end Mnemosyne -->")
    }

    "empty content leaves an empty block rather than removing the markers" in {
      val note = "<!-- iris:begin M -->\nold\n<!-- iris:end M -->\n"
      patch(note, NoteEdit.ReplaceManagedSection("M", "")) shouldBe
        "<!-- iris:begin M -->\n<!-- iris:end M -->\n"
    }

    "be idempotent" in {
      val note = "Prose.\n"
      val once = patch(note, NoteEdit.ReplaceManagedSection("M", "a\nb"))
      val twice = patch(once, NoteEdit.ReplaceManagedSection("M", "a\nb"))
      twice shouldBe once
    }

    "refuse content carrying a marker, which would break the boundaries" in {
      NotePatch(
        "body\n",
        Seq(NoteEdit.ReplaceManagedSection("M", "x\n<!-- iris:end M -->\nescaped"))
      ) shouldBe Left(PatchError.MarkerInContent("M"))
    }

    "refuse a malformed existing block instead of guessing" in {
      val orphanBegin = "<!-- iris:begin M -->\ncontent with no end\n"
      NotePatch(orphanBegin, Seq(NoteEdit.ReplaceManagedSection("M", "x"))).isLeft shouldBe true

      val reversed = "<!-- iris:end M -->\nx\n<!-- iris:begin M -->\n"
      NotePatch(reversed, Seq(NoteEdit.ReplaceManagedSection("M", "x"))).isLeft shouldBe true

      val doubled =
        "<!-- iris:begin M -->\na\n<!-- iris:end M -->\n<!-- iris:begin M -->\nb\n<!-- iris:end M -->\n"
      NotePatch(doubled, Seq(NoteEdit.ReplaceManagedSection("M", "x"))).isLeft shouldBe true
    }

    "be allowed even when the frontmatter is unreadable, since it only touches the body" in {
      NotePatch(
        "---\nbad: [\n---\nbody\n",
        Seq(NoteEdit.ReplaceManagedSection("M", "x")),
        frontmatterError = Some("unreadable")
      ).isRight shouldBe true
    }
  }

  "Line terminators" should {

    "survive a CRLF note" in {
      val crlf = "---\r\nstatus: draft\r\n---\r\nbody\r\n"
      val out = patch(crlf, NoteEdit.SetFrontmatterKey("status", "done"))
      out shouldBe "---\r\nstatus: \"done\"\r\n---\r\nbody\r\n"
      out should not include "\n\n"
    }

    "not introduce a terminator on a note that lacks a trailing one" in {
      val note = "---\na: 1\n---\nbody"
      val out = patch(note, NoteEdit.SetFrontmatterKey("a", "2"))
      out shouldBe "---\na: \"2\"\n---\nbody"
    }
  }

  "The round trip" should {

    "be byte-exact when no edits are applied" in {
      NotePatch(realistic, Seq.empty) shouldBe Right(realistic)
    }

    "be byte-exact for every shape of note, with no edits" in {
      Seq(
        "",
        "\n",
        "a",
        "a\n",
        "---\n---\n",
        "---\na: 1\n---\n",
        "---\r\na: 1\r\n---\r\nbody",
        "no frontmatter at all",
        "---\nunclosed fence\nbody\n",
        "mixed\r\nterminators\nin one file\r\n"
      ).foreach { note =>
        withClue(s"round-trip of ${note.replace("\n", "\\n").replace("\r", "\\r")}: ") {
          NotePatch(note, Seq.empty) shouldBe Right(note)
        }
      }
    }
  }
