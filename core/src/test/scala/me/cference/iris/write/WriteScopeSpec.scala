package me.cference.iris.write

import me.cference.iris.domain.VaultPath
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The write-confinement gate. This is the phase-2 safety boundary from
 * `docs/vault-access-model.md`, so these specs are written as escape attempts rather than as
 * feature coverage: the happy path is one case, and the rest try to get out.
 */
class WriteScopeSpec extends AnyWordSpec with Matchers:

  private val mnemosyne = WriteScope.forService("Mnemosyne").toOption.get
  private val dionysus = WriteScope.forService("Dionysus").toOption.get

  private def confine(scope: WriteScope, path: String) = ConfinedPath.confine(scope, path)

  private def refused(scope: WriteScope, path: String): ConfinementError =
    confine(scope, path).left.getOrElse(
      fail(s"ESCAPE: '$path' was accepted into scope '${scope.root}'")
    )

  "WriteScope.forService" should {

    "put a service under Atlas/Olympus/<Service>" in {
      mnemosyne.root shouldBe "Atlas/Olympus/Mnemosyne"
    }

    "refuse a name that is not a single safe segment" in {
      // Each of these would name a folder outside Base once concatenated — the scope-widening
      // bug that validating the NAME (not just the path) exists to stop.
      val illegal =
        Seq("..", ".", "a/b", "../../etc", "", " ", "Mnemosyne/sub", "/abs", ".hidden", "a\\b")
      illegal.foreach { name =>
        WriteScope.forService(name).isLeft shouldBe true
      }
    }

    "refuse a traversal name that would otherwise escape the base entirely" in {
      // The specific catastrophe: scope root would become "Atlas/Olympus/../.." => the vault root.
      WriteScope.forService("../..") match
        case Left(ConfinementError.IllegalServiceName(_)) => succeed
        case other => fail(s"expected IllegalServiceName, got $other")
    }
  }

  "ConfinedPath.confine" should {

    "accept a note inside the service's own folder" in {
      val ok = confine(mnemosyne, "Atlas/Olympus/Mnemosyne/People/Alcvin Ramos.md")
      ok.map(_.path.value) shouldBe
        Right("Atlas/Olympus/Mnemosyne/People/Alcvin Ramos.md")
      ok.map(_.scope.service) shouldBe Right("Mnemosyne")
    }

    "refuse the sibling-prefix path — the textbook startsWith bug" in {
      // 'Atlas/Olympus/Dionysus2/x.md' STARTS WITH 'Atlas/Olympus/Dionysus'. Segment comparison
      // is the only thing that catches this; property 3 of the access model names it explicitly.
      refused(dionysus, "Atlas/Olympus/Dionysus2/x.md") shouldBe a[ConfinementError.OutsideScope]
      refused(dionysus, "Atlas/Olympus/DionysusEvil/x.md") shouldBe
        a[ConfinementError.OutsideScope]
    }

    "refuse another service's folder" in {
      refused(mnemosyne, "Atlas/Olympus/Dionysus/stolen.md") shouldBe
        a[ConfinementError.OutsideScope]
    }

    "refuse Calvin's own notes — the whole point of the rule" in {
      Seq(
        "Atlas/Notes/People/Alcvin Ramos.md",
        "Home.md",
        "Atlas/Olympus/note-at-base.md",
        "Atlas/note.md"
      ).foreach { p =>
        refused(mnemosyne, p) shouldBe a[ConfinementError.OutsideScope]
      }
    }

    "refuse traversal out of the scope, even when it textually starts inside it" in {
      // VaultPath.parse rejects '..' outright, so this surfaces as UnparseablePath — but the
      // assertion that matters is that it is REFUSED, by whichever layer catches it first.
      Seq(
        "Atlas/Olympus/Mnemosyne/../Dionysus/x.md",
        "Atlas/Olympus/Mnemosyne/../../../Home.md",
        "Atlas/Olympus/Mnemosyne/sub/../../Dionysus/x.md"
      ).foreach { p =>
        refused(mnemosyne, p) shouldBe a[ConfinementError.UnparseablePath]
      }
    }

    "refuse an absolute path and a backslash path" in {
      refused(mnemosyne, "/Atlas/Olympus/Mnemosyne/x.md") shouldBe
        a[ConfinementError.UnparseablePath]
      refused(mnemosyne, "Atlas\\Olympus\\Mnemosyne\\x.md") shouldBe
        a[ConfinementError.UnparseablePath]
    }

    "refuse a '.' segment as non-canonical" in {
      // VaultPath tolerates '.', so confinement refuses it rather than confine against a
      // non-canonical value. 'Atlas/Olympus/Mnemosyne/./x.md' resolves inside, but accepting
      // one non-canonical form invites reasoning about which others are safe.
      refused(mnemosyne, "Atlas/Olympus/Mnemosyne/./x.md") shouldBe
        ConfinementError.NonCanonicalPath
    }

    "refuse the scope root itself" in {
      refused(mnemosyne, "Atlas/Olympus/Mnemosyne") shouldBe
        a[ConfinementError.ScopeRootItself]
      // With a trailing slash VaultPath strips it, so this is the same refusal.
      refused(mnemosyne, "Atlas/Olympus/Mnemosyne/") shouldBe
        a[ConfinementError.ScopeRootItself]
    }

    "refuse a case-variant folder rather than guess across two filesystems" in {
      // macOS (case-insensitive) and the Linux PVC (case-sensitive) disagree about whether these
      // are the same folder. Refusing is the fail-closed direction.
      refused(mnemosyne, "atlas/olympus/mnemosyne/x.md") shouldBe
        a[ConfinementError.OutsideScope]
      refused(mnemosyne, "Atlas/Olympus/MNEMOSYNE/x.md") shouldBe
        a[ConfinementError.OutsideScope]
    }

    "accept arbitrarily deep nesting inside the scope" in {
      confine(mnemosyne, "Atlas/Olympus/Mnemosyne/a/b/c/d/deep.md").isRight shouldBe true
    }

    "normalize unicode before comparing, so an NFD path still confines" in {
      // The vault is full of accented names and macOS hands out NFD. VaultPath normalizes to NFC;
      // confinement must therefore accept an NFD spelling of an in-scope path.
      val nfd = java.text.Normalizer.normalize(
        "Atlas/Olympus/Mnemosyne/Electricité.md",
        java.text.Normalizer.Form.NFD
      )
      confine(mnemosyne, nfd).map(_.path.value) shouldBe
        Right("Atlas/Olympus/Mnemosyne/Electricité.md")
    }
  }

  "WriteScope.contains" should {

    "never accept the root, and never accept a parent" in {
      val root = VaultPath.parse("Atlas/Olympus/Mnemosyne").toOption.get
      val parent = VaultPath.parse("Atlas/Olympus").toOption.get
      mnemosyne.contains(root) shouldBe false
      mnemosyne.contains(parent) shouldBe false
    }
  }

  /**
   * Property 5 of the access model — "enforced in `core/`, not at the HTTP edge" — is only real if
   * the gate cannot be sidestepped. These assertions are the proof: they fail the BUILD if a future
   * change makes `ConfinedPath` constructible without going through `confine`.
   */
  "The gate" should {

    "make an unconfined ConfinedPath impossible to construct" in {
      assertDoesNotCompile("ConfinedPath(VaultPath.parse(\"Home.md\").toOption.get, mnemosyne)")
      assertDoesNotCompile("new ConfinedPath(VaultPath.parse(\"Home.md\").toOption.get, mnemosyne)")
    }

    "make a confined path impossible to re-point at another file afterwards" in {
      // The subtler hole: obtain a legitimate ConfinedPath, then `copy` it onto Calvin's notes.
      // Scala 3 gives `copy` the constructor's visibility, which is what closes this.
      assertDoesNotCompile(
        """val ok = ConfinedPath.confine(mnemosyne, "Atlas/Olympus/Mnemosyne/x.md").toOption.get
           ok.copy(path = VaultPath.parse("Atlas/Notes/People/Alcvin Ramos.md").toOption.get)"""
      )
    }

    "still compile the legitimate route, so the above is not vacuous" in {
      assertCompiles(
        """ConfinedPath.confine(mnemosyne, "Atlas/Olympus/Mnemosyne/x.md")"""
      )
    }
  }
