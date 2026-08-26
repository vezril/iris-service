package me.cference.iris.scan

import me.cference.iris.domain.VaultPath
import me.cference.iris.persistence.IndexedMeta
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator

class VaultScannerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private var root: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    root = Files.createTempDirectory("iris-vault-spec")
    write("Home.md", "# home")
    write("Atlas/Notes/People/Alcvin Ramos.md", "a person")
    write("Atlas/Notes/Ideas/Uplay Recovery Codes.mdenc", "ENCRYPTED")
    write("Atlas/Utilities/Images/mark.png", "not markdown")
    write(".obsidian/app.json", "{}")
    write(".smart-env/cache.json", "{}")
    write("+/DEP - Electricité.md", "unicode name")

  override def afterAll(): Unit =
    Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p => Files.delete(p))

  private def write(rel: String, content: String): Unit =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    ()

  private def meta(path: String, size: Long, at: Instant) =
    path -> IndexedMeta(path, size, at, "0" * 64)

  "VaultScanner.listVault" should {

    "find exactly the indexable markdown notes" in {
      val found = VaultScanner.listVault(root).map(_.path.value).sorted
      found shouldBe Vector(
        "+/DEP - Electricité.md",
        "Atlas/Notes/People/Alcvin Ramos.md",
        "Home.md"
      )
    }

    "report size and mtime for each file" in {
      val home = VaultScanner.listVault(root).find(_.path.value == "Home.md").get
      home.sizeBytes shouldBe "# home".getBytes(StandardCharsets.UTF_8).length.toLong
    }

    "return empty for a missing root (sidecar not yet synced)" in {
      VaultScanner.listVault(root.resolve("nope")) shouldBe empty
    }
  }

  "VaultScanner.diff" should {

    val now = Instant.parse("2026-08-26T12:00:00Z")
    def fm(p: String, size: Long, at: Instant) =
      FileMeta(VaultPath.parse(p).toOption.get, size, at)

    "skip files whose size and mtime match the index" in {
      val current = Vector(fm("A.md", 10, now))
      val indexed = Map(meta("A.md", 10, now))
      VaultScanner.diff(current, indexed) shouldBe ChangeSet(Vector.empty, Vector.empty)
    }

    "pick up new, changed, and deleted files" in {
      val current = Vector(fm("new.md", 5, now), fm("changed.md", 6, now))
      val indexed = Map(meta("changed.md", 6, now.minusSeconds(60)), meta("gone.md", 7, now))
      val cs = VaultScanner.diff(current, indexed)
      cs.createdOrChanged.map(_.path.value) shouldBe Vector("new.md", "changed.md")
      cs.deleted shouldBe Vector("gone.md")
    }
  }
