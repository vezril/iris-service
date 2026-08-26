package me.cference.iris.domain

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.text.Normalizer

class VaultPathSpec extends AnyWordSpec with Matchers with EitherValues:

  "VaultPath.parse" should {

    "accept a simple vault-relative path" in {
      val p = VaultPath.parse("Atlas/Notes/People/Alcvin Ramos.md").value
      p.value shouldBe "Atlas/Notes/People/Alcvin Ramos.md"
      p.folder shouldBe "Atlas/Notes/People"
      p.fileName shouldBe "Alcvin Ramos.md"
      p.name shouldBe "Alcvin Ramos"
      p.extension shouldBe "md"
    }

    "accept a note at the vault root" in {
      val p = VaultPath.parse("Home.md").value
      p.folder shouldBe ""
      p.name shouldBe "Home"
    }

    "strip a trailing slash" in {
      VaultPath.parse("Atlas/Notes/").value.value shouldBe "Atlas/Notes"
    }

    "normalize NFD input to NFC so macOS and Linux names compare equal" in {
      val nfd = Normalizer.normalize("Electricité.md", Normalizer.Form.NFD)
      val nfc = Normalizer.normalize("Electricité.md", Normalizer.Form.NFC)
      nfd should not be nfc // the premise: the two byte forms differ
      VaultPath.parse(nfd).value shouldBe VaultPath.parse(nfc).value
    }

    "preserve spaces, apostrophes, and unicode" in {
      val p = VaultPath.parse("+/DEP - Electricité.md").value
      p.name shouldBe "DEP - Electricité"
    }

    "reject an empty path" in {
      VaultPath.parse("").left.value shouldBe PathError.Empty
      VaultPath.parse("/").left.value shouldBe PathError.Empty
    }

    "reject an absolute path" in {
      VaultPath.parse("/etc/passwd").left.value shouldBe PathError.Absolute
    }

    "reject parent traversal" in {
      VaultPath.parse("Atlas/../secrets.md").left.value shouldBe PathError.Traversal
    }

    "reject backslash separators" in {
      VaultPath.parse("Atlas\\Notes\\x.md").left.value shouldBe PathError.Backslash
    }

    "reject empty segments" in {
      VaultPath.parse("Atlas//Notes.md").left.value shouldBe PathError.EmptySegment
    }

    "treat a leading-dot file as extensionless" in {
      val p = VaultPath.parse("Atlas/.hidden").value
      p.name shouldBe ".hidden"
      p.extension shouldBe ""
    }

    "lowercase the extension" in {
      VaultPath.parse("Atlas/Note.MD").value.extension shouldBe "md"
    }
  }
