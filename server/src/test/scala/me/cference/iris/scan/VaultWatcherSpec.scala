package me.cference.iris.scan

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import me.cference.iris.config.DbConfig
import me.cference.iris.persistence.{NoteRepository, SchemaMigrator}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * End-to-end watcher test. macOS's JDK WatchService is poll-based (seconds of latency), so the
 * assertions use generous eventually-windows; on the Linux PVC inotify makes this near-instant.
 */
class VaultWatcherSpec
    extends AnyWordSpec
    with Matchers
    with TestContainerForAll
    with Eventually
    with BeforeAndAfterAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:16"))

  private val testKit = ActorTestKit("iris-watcher-spec")

  override def afterAll(): Unit = testKit.shutdownTestKit()

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(45, Seconds), interval = Span(1, Seconds))

  "VaultWatcher" should {

    "converge the index on create, edit, and delete" in withContainers { c =>
      val cfg = DbConfig(c.jdbcUrl, c.username, c.password, 30.seconds)
      SchemaMigrator.migrate(cfg).left.foreach(e => fail(e.message))
      val hc = new HikariConfig()
      hc.setJdbcUrl(c.jdbcUrl)
      hc.setUsername(c.username)
      hc.setPassword(c.password)
      hc.setMaximumPoolSize(2)
      val ds = new HikariDataSource(hc)
      val root = Files.createTempDirectory("iris-watcher-spec")
      val overflow = new AtomicInteger(0)
      try
        val repo = NoteRepository(ds)
        val reconciler = IndexReconciler(root, repo)
        given org.apache.pekko.actor.typed.ActorSystem[Nothing] = testKit.system
        val watcher =
          VaultWatcher(root, reconciler, 300.millis, () => { overflow.incrementAndGet(); () })
        watcher.start()

        val note = root.resolve("Watched.md")
        Files.write(note, "hello [[There]]".getBytes(StandardCharsets.UTF_8))
        eventually {
          repo.allMeta() should contain key "Watched.md"
        }

        Files.write(note, "hello again".getBytes(StandardCharsets.UTF_8))
        eventually {
          val meta = repo.allMeta()("Watched.md")
          meta.sizeBytes shouldBe "hello again".length.toLong
        }

        Files.delete(note)
        eventually {
          repo.allMeta() should not contain key("Watched.md")
        }

        watcher.drain()
        ()
      finally
        ds.close()
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p => Files.delete(p))
    }
  }
