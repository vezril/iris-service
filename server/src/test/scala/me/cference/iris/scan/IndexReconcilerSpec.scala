package me.cference.iris.scan

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import me.cference.iris.config.DbConfig
import me.cference.iris.persistence.{NoteRepository, SchemaMigrator}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.concurrent.duration.*

class IndexReconcilerSpec extends AnyWordSpec with Matchers with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:16"))

  private def withFixture[A](
      c: Containers,
      publisher: me.cference.iris.hermes.VaultEventPublisher =
        me.cference.iris.hermes.VaultEventPublisher.NoOp
  )(f: (Path, NoteRepository, IndexReconciler) => A): A =
    val cfg = DbConfig(c.jdbcUrl, c.username, c.password, 30.seconds)
    SchemaMigrator.migrate(cfg).left.foreach(e => fail(e.message))
    val hc = new HikariConfig()
    hc.setJdbcUrl(c.jdbcUrl)
    hc.setUsername(c.username)
    hc.setPassword(c.password)
    hc.setMaximumPoolSize(2)
    val ds = new HikariDataSource(hc)
    val root = Files.createTempDirectory("iris-reconciler-spec")
    try
      val repo = NoteRepository(ds)
      f(root, repo, IndexReconciler(root, repo, publisher))
    finally
      ds.close()
      Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p => Files.delete(p))

  private def write(root: Path, rel: String, content: String): Unit =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    ()

  "IndexReconciler.fullScan" should {

    "index a vault, then report zero work on an unchanged rescan (idempotence)" in withContainers {
      c =>
        withFixture(c) { (root, repo, reconciler) =>
          write(root, "Home.md", "# home [[Atlas Map]]")
          write(root, "Atlas/Atlas Map.md", "---\ntags: [map]\n---\ncentral")
          write(root, "secret.mdenc", "NEVER")

          val first = reconciler.fullScan()
          first.notesSeen shouldBe 2
          first.created shouldBe 2
          first.errors shouldBe 0
          repo.noteCount() shouldBe 2L

          val second = reconciler.fullScan()
          second.created shouldBe 0
          second.changed shouldBe 0
          second.deleted shouldBe 0
        }
    }

    "emit created/changed/deleted events with chained hashes, after commit" in withContainers { c =>
      val events =
        scala.collection.mutable.ArrayBuffer.empty[me.cference.iris.hermes.VaultNoteEvent]
      val recorder = new me.cference.iris.hermes.VaultEventPublisher:
        def publish(e: me.cference.iris.hermes.VaultNoteEvent): Unit = events += e
      withFixture(c, recorder) { (root, repo, reconciler) =>
        write(root, "e.md", "v1")
        reconciler.fullScan()
        write(root, "e.md", "v2 with more bytes")
        reconciler.fullScan()
        java.nio.file.Files.delete(root.resolve("e.md"))
        reconciler.fullScan()

        import me.cference.iris.hermes.VaultEventKind
        // The suite shares one database: earlier tests' leftovers emit their own
        // deletions on the first scan, so assertions scope to this test's note.
        val mine = events.toVector.filter(_.path.value == "e.md")
        mine.map(_.kind) shouldBe Vector(
          VaultEventKind.Created,
          VaultEventKind.Changed,
          VaultEventKind.Deleted
        )
        val Vector(created, changed, deleted) = mine: @unchecked
        created.previousHash shouldBe ""
        changed.previousHash shouldBe created.contentHash // the chain consumers rely on
        deleted.previousHash shouldBe changed.contentHash
        deleted.contentHash shouldBe ""
      }
    }

    "pick up edits and deletes" in withContainers { c =>
      withFixture(c) { (root, repo, reconciler) =>
        write(root, "a.md", "one")
        write(root, "b.md", "two")
        reconciler.fullScan()

        // Ensure a distinct mtime (filesystem granularity), then edit + delete.
        val edited = root.resolve("a.md")
        Files.write(edited, "one, edited".getBytes(StandardCharsets.UTF_8))
        Files.setLastModifiedTime(
          edited,
          java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000)
        )
        Files.delete(root.resolve("b.md"))

        val s = reconciler.fullScan()
        s.changed shouldBe 1
        s.deleted shouldBe 1
        repo.noteCount() shouldBe 1L
        repo.allMeta()("a.md").sizeBytes shouldBe "one, edited".length.toLong
      }
    }
  }
