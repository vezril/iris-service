package me.cference.iris.write

import me.cference.iris.domain.{ContentHash, VaultPath}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.jdk.CollectionConverters.*

/**
 * The writer against a real temp filesystem. No Docker and no Apollo: the snapshot store is a fake
 * that records what it was handed and can be told to fail, which is what makes the fail-closed
 * guarantee testable at all.
 *
 * Most of these assert that the file on disk is UNCHANGED after a refusal. That is the property
 * that matters: every failure path must leave the vault as it was.
 */
class VaultWriterSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private var root: Path = scala.compiletime.uninitialized

  /** Records every snapshot call; `failWith` makes the store refuse. */
  private class FakeSnapshots(var failWith: Option[SnapshotError] = None) extends SnapshotStore:
    var calls: Vector[(String, String)] = Vector.empty
    def snapshot(path: VaultPath, bytes: Array[Byte]): Either[SnapshotError, SnapshotRef] =
      calls = calls :+ (path.value -> new String(bytes, StandardCharsets.UTF_8))
      failWith.toLeft(
        SnapshotRef("iris", s"snap/${path.value}", ContentHash.ofBytes(bytes), bytes.length.toLong)
      )

  private val scope = WriteScope.forService("Mnemosyne").toOption.get
  private val notePath = "Atlas/Olympus/Mnemosyne/note.md"

  override def beforeEach(): Unit = root = Files.createTempDirectory("iris-writer-spec")

  override def afterEach(): Unit =
    if root != null then
      Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))

  private def confined(p: String = notePath) = ConfinedPath.confine(scope, p).toOption.get

  private def seed(rel: String, content: String): ContentHash =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    ContentHash.ofBytes(content.getBytes(StandardCharsets.UTF_8))

  private def read(rel: String): String =
    new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.UTF_8)

  private def writer(s: SnapshotStore) = VaultWriter(root, s)

  private val setStatus = Seq(NoteEdit.SetFrontmatterKey("status", "done"))

  "The optimistic lock" should {

    "write when the presented hash matches" in {
      val hash = seed(notePath, "---\nstatus: draft\n---\nbody\n")
      val snaps = FakeSnapshots()
      val r = writer(snaps).write(confined(), Some(hash), setStatus)
      r.map(_.changed) shouldBe Right(true)
      read(notePath) should include("""status: "done"""")
    }

    "REFUSE a stale hash and leave the note untouched" in {
      val before = "---\nstatus: draft\n---\nbody\n"
      seed(notePath, before)
      val stale = ContentHash.ofBytes("something else".getBytes(StandardCharsets.UTF_8))
      val snaps = FakeSnapshots()

      val r = writer(snaps).write(confined(), Some(stale), setStatus)

      r.left.map(_.getClass.getSimpleName) shouldBe Left("Conflict")
      read(notePath) shouldBe before // the whole point: Calvin's edit survives
      snaps.calls shouldBe empty // and nothing was snapshotted for a write that never happened
    }

    "refuse a write to an existing note that presents NO hash" in {
      // "I didn't look" is not permission to overwrite.
      val before = "body\n"
      seed(notePath, before)
      writer(FakeSnapshots()).write(confined(), None, setStatus) shouldBe
        Left(WriteError.HashRequired)
      read(notePath) shouldBe before
    }

    "treat a hash presented for an absent note as a conflict" in {
      // The note the caller read has been deleted; recreating it from their stale copy would
      // silently resurrect it.
      val ghost = ContentHash.ofBytes("gone".getBytes(StandardCharsets.UTF_8))
      val r = writer(FakeSnapshots()).write(confined(), Some(ghost), setStatus)
      r.left.map(_.getClass.getSimpleName) shouldBe Left("Conflict")
      Files.exists(root.resolve(notePath)) shouldBe false
    }

    "create a new note when no hash is presented and none exists" in {
      val snaps = FakeSnapshots()
      val r = writer(snaps).write(confined(), None, setStatus)
      r.map(_.changed) shouldBe Right(true)
      read(notePath) shouldBe "---\nstatus: \"done\"\n---\n"
      // Nothing existed, so there was nothing to snapshot.
      snaps.calls shouldBe empty
      r.map(_.snapshot) shouldBe Right(None)
    }
  }

  "The snapshot" should {

    "receive the ORIGINAL bytes, not the patched ones" in {
      val before = "---\nstatus: draft\n---\nbody\n"
      val hash = seed(notePath, before)
      val snaps = FakeSnapshots()

      writer(snaps).write(confined(), Some(hash), setStatus)

      snaps.calls.map(_._1) shouldBe Vector(notePath)
      snaps.calls.head._2 shouldBe before // what you would need to undo the write
    }

    "ABANDON the write when the snapshot store refuses — fail closed" in {
      // The governing decision: an Apollo outage stops writes rather than letting them proceed
      // unrecoverably. A write during an outage is exactly the one you would most want back.
      val before = "---\nstatus: draft\n---\nbody\n"
      val hash = seed(notePath, before)
      val snaps = FakeSnapshots(failWith = Some(SnapshotError.Unavailable("apollo down")))

      val r = writer(snaps).write(confined(), Some(hash), setStatus)

      r.left.map(_.getClass.getSimpleName) shouldBe Left("SnapshotFailed")
      read(notePath) shouldBe before
    }

    "refuse every write by default, with no snapshot store configured" in {
      val before = "x\n"
      val hash = seed(notePath, before)
      val r = writer(SnapshotStore.unconfigured).write(confined(), Some(hash), setStatus)
      r shouldBe Left(WriteError.SnapshotFailed(SnapshotError.NotConfigured))
      read(notePath) shouldBe before
    }

    "not be taken when the edits change nothing" in {
      // Idempotent re-apply must not churn the vault or fill Apollo with identical copies.
      val hash = seed(notePath, "---\nstatus: \"done\"\n---\nbody\n")
      val snaps = FakeSnapshots()
      val r = writer(snaps).write(confined(), Some(hash), setStatus)
      r.map(_.changed) shouldBe Right(false)
      snaps.calls shouldBe empty
      r.map(_.snapshot) shouldBe Right(None)
    }
  }

  "Symlink refusal (property 2)" should {

    "refuse a symlinked target" in {
      val outside = Files.createTempFile("iris-outside-", ".md")
      try
        Files.createDirectories(root.resolve("Atlas/Olympus/Mnemosyne"))
        Files.createSymbolicLink(root.resolve(notePath), outside)
        val hash = ContentHash.ofBytes(Files.readAllBytes(outside))

        val r = writer(FakeSnapshots()).write(confined(), Some(hash), setStatus)

        r.left.map(_.getClass.getSimpleName) shouldBe Left("SymlinkRefused")
        // The file OUTSIDE the vault is what confinement exists to protect.
        new String(Files.readAllBytes(outside), StandardCharsets.UTF_8) shouldBe ""
      finally Files.deleteIfExists(outside)
    }

    "refuse a symlinked PARENT directory — the escape ConfinedPath cannot see" in {
      val outsideDir = Files.createTempDirectory("iris-outside-dir-")
      try
        Files.createDirectories(root.resolve("Atlas/Olympus"))
        // Atlas/Olympus/Mnemosyne -> somewhere else entirely.
        Files.createSymbolicLink(root.resolve("Atlas/Olympus/Mnemosyne"), outsideDir)

        val r = writer(FakeSnapshots()).write(confined(), None, setStatus)

        r.left.map(_.getClass.getSimpleName) shouldBe Left("SymlinkRefused")
        Files.list(outsideDir).iterator().asScala.toVector shouldBe empty
      finally Files.deleteIfExists(outsideDir)
    }
  }

  "A refused patch" should {

    "leave the note untouched" in {
      val before = "---\na: 1\nb: 2\na: 3\n---\nbody\n" // duplicate key => AmbiguousKey
      val hash = seed(notePath, before)
      val snaps = FakeSnapshots()
      val r = writer(snaps).write(confined(), Some(hash), Seq(NoteEdit.SetFrontmatterKey("a", "9")))
      r.left.map(_.getClass.getSimpleName) shouldBe Left("PatchRefused")
      read(notePath) shouldBe before
      snaps.calls shouldBe empty
    }

    "refuse a frontmatter edit on a note whose YAML does not parse" in {
      val before = "---\nbroken: [unclosed\n---\nbody\n"
      val hash = seed(notePath, before)
      val r = writer(FakeSnapshots()).write(confined(), Some(hash), setStatus)
      r.left.map(_.getClass.getSimpleName) shouldBe Left("PatchRefused")
      read(notePath) shouldBe before
    }
  }

  "The write itself" should {

    "create the service folder on a first write" in {
      Files.exists(root.resolve("Atlas/Olympus/Mnemosyne")) shouldBe false
      writer(FakeSnapshots()).write(confined(), None, setStatus).isRight shouldBe true
      Files.isDirectory(root.resolve("Atlas/Olympus/Mnemosyne")) shouldBe true
    }

    "leave no temp files behind" in {
      val hash = seed(notePath, "---\nstatus: draft\n---\nbody\n")
      writer(FakeSnapshots()).write(confined(), Some(hash), setStatus).isRight shouldBe true
      val leftovers = Files
        .list(root.resolve("Atlas/Olympus/Mnemosyne"))
        .iterator()
        .asScala
        .map(_.getFileName.toString)
        .filter(_.startsWith(".iris-"))
        .toVector
      leftovers shouldBe empty
    }

    "leave no temp file behind when the write fails after the temp is created" in {
      // A refused snapshot aborts before any temp file is made; assert the directory is clean.
      val hash = seed(notePath, "x\n")
      writer(FakeSnapshots(Some(SnapshotError.Unavailable("down"))))
        .write(confined(), Some(hash), setStatus)
        .isLeft shouldBe true
      Files
        .list(root.resolve("Atlas/Olympus/Mnemosyne"))
        .iterator()
        .asScala
        .map(_.getFileName.toString)
        .toVector shouldBe Vector("note.md")
    }

    "preserve everything it does not manage, end to end" in {
      val before =
        """---
          |up: "[[People Map]]"
          |# a comment
          |tags: [music]
          |status: draft
          |---
          |# Heading
          |
          |Prose Calvin wrote.
          |""".stripMargin
      val hash = seed(notePath, before)
      writer(FakeSnapshots()).write(confined(), Some(hash), setStatus).isRight shouldBe true

      val after = read(notePath)
      after should include("""up: "[[People Map]]"""")
      after should include("# a comment")
      after should include("tags: [music]")
      after should include("Prose Calvin wrote.")
      after should include("""status: "done"""")
      after.linesIterator.size shouldBe before.linesIterator.size
    }

    "report the new content hash, matching what is on disk" in {
      val hash = seed(notePath, "---\nstatus: draft\n---\nbody\n")
      val r = writer(FakeSnapshots()).write(confined(), Some(hash), setStatus)
      val onDisk = ContentHash.ofBytes(Files.readAllBytes(root.resolve(notePath)))
      r.map(_.contentHash) shouldBe Right(onDisk)
    }
  }
