package me.cference.iris.scan

import me.cference.iris.domain.VaultPath
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VaultRulesSpec extends AnyWordSpec with Matchers:

  private def p(s: String) = VaultPath.parse(s).getOrElse(fail(s"bad fixture path $s"))

  "VaultRules" should {

    "index ordinary markdown notes" in {
      VaultRules.shouldIndex(p("Atlas/Notes/People/Alcvin Ramos.md")) shouldBe true
      VaultRules.shouldIndex(p("Home.md")) shouldBe true
      VaultRules.shouldIndex(p("+/DEP - Electricité.md")) shouldBe true
    }

    "never open .mdenc files (encrypted recovery codes)" in {
      // The three known real files — this exact shape must stay excluded forever.
      VaultRules.isExcluded(p("Atlas/Notes/Ideas/Github Recovery Codes.mdenc")) shouldBe true
      VaultRules.isExcluded(p("Atlas/Notes/Ideas/LinkedIn Recovery Codes.mdenc")) shouldBe true
      VaultRules.isExcluded(p("Atlas/Notes/Ideas/Uplay Recovery Codes.mdenc")) shouldBe true
      VaultRules.shouldIndex(p("Atlas/Notes/Ideas/Uplay Recovery Codes.mdenc")) shouldBe false
    }

    "exclude dot-directories wherever they appear" in {
      VaultRules.isExcluded(p(".obsidian/plugins/dataview/main.js")) shouldBe true
      VaultRules.isExcluded(p(".smart-env/cache.json")) shouldBe true
      VaultRules.isExcluded(p(".trash/old.md")) shouldBe true
      VaultRules.isExcluded(p("Atlas/.hidden/note.md")) shouldBe true
    }

    "exclude dot-files" in {
      VaultRules.isExcluded(p("Atlas/.DS_Store")) shouldBe true
    }

    "skip non-markdown assets without marking them forbidden" in {
      VaultRules.shouldIndex(p("Atlas/Utilities/Images/mark.png")) shouldBe false
      VaultRules.shouldIndex(p("Efforts/Currently Reading.base")) shouldBe false
      VaultRules.shouldIndex(p("Dionysus/board.canvas")) shouldBe false
      VaultRules.isExcluded(p("Atlas/Utilities/Images/mark.png")) shouldBe false
    }

    "index .sync-conflict copies like any note (they are real markdown)" in {
      VaultRules.shouldIndex(p("Home.sync-conflict-20260826.md")) shouldBe true
    }
  }
