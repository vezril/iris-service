package me.cference.iris.persistence

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import me.cference.iris.config.DbConfig
import me.cference.iris.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.*

class NoteRepositorySpec extends AnyWordSpec with Matchers with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:16"))

  private val now = Instant.parse("2026-08-26T12:00:00Z")

  private def withRepo[A](c: Containers)(f: (NoteRepository, HikariDataSource) => A): A =
    val cfg = DbConfig(c.jdbcUrl, c.username, c.password, 30.seconds)
    SchemaMigrator.migrate(cfg).left.foreach(e => fail(e.message))
    val hc = new HikariConfig()
    hc.setJdbcUrl(c.jdbcUrl)
    hc.setUsername(c.username)
    hc.setPassword(c.password)
    hc.setMaximumPoolSize(2)
    val ds = new HikariDataSource(hc)
    try f(NoteRepository(ds), ds)
    finally ds.close()

  private def note(path: String, text: String): Note =
    val vp = VaultPath.parse(path).toOption.get
    me.cference.iris.parse.NoteParser.parse(vp, text.getBytes(StandardCharsets.UTF_8), now)

  private def resolvedTargets(ds: HikariDataSource, source: String): Vector[Option[String]] =
    val conn = ds.getConnection
    try
      val ps = conn.prepareStatement(
        "SELECT resolved_path FROM note_links WHERE source_path = ? ORDER BY ordinal"
      )
      ps.setString(1, source)
      val rs = ps.executeQuery()
      val out = Vector.newBuilder[Option[String]]
      while rs.next() do out += Option(rs.getString(1))
      out.result()
    finally conn.close()

  "NoteRepository" should {

    "upsert idempotently and expose meta" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        val n = note("Home.md", "---\ntags: [start]\n---\nhello #world")
        repo.upsert(n)
        repo.upsert(n) // idempotent
        repo.noteCount() shouldBe 1L
        val meta = repo.allMeta()("Home.md")
        meta.contentHash shouldBe n.contentHash.value
        meta.sizeBytes shouldBe n.sizeBytes
      }
    }

    "resolve links by unique basename and re-resolve on create and delete" in withContainers { c =>
      withRepo(c) { (repo, ds) =>
        // Source links to a not-yet-indexed note: unresolved.
        repo.upsert(note("a/Source.md", "see [[Target]]"))
        resolvedTargets(ds, "a/Source.md") shouldBe Vector(None)

        // Target appears: the link resolves.
        repo.upsert(note("b/Target.md", "i exist"))
        resolvedTargets(ds, "a/Source.md") shouldBe Vector(Some("b/Target.md"))

        // A second note of the same name appears: ambiguity unresolves it.
        repo.upsert(note("c/Target.md", "me too"))
        resolvedTargets(ds, "a/Source.md") shouldBe Vector(None)

        // The duplicate goes away: unique again.
        repo.delete(VaultPath.parse("c/Target.md").toOption.get)
        resolvedTargets(ds, "a/Source.md") shouldBe Vector(Some("b/Target.md"))
      }
    }

    "resolve an exact vault path over a basename match" in withContainers { c =>
      withRepo(c) { (repo, ds) =>
        repo.upsert(note("x/Exact.md", "target"))
        repo.upsert(note("S.md", "see [[x/Exact]] and [[x/Exact.md]]"))
        resolvedTargets(ds, "S.md") shouldBe Vector(Some("x/Exact.md"), Some("x/Exact.md"))
      }
    }

    "cascade tags and links on delete" in withContainers { c =>
      withRepo(c) { (repo, ds) =>
        repo.upsert(note("t/Note.md", "#tagged and [[Elsewhere]]"))
        repo.delete(VaultPath.parse("t/Note.md").toOption.get)
        // The container is shared across tests, so assertions stay note-scoped.
        val conn = ds.getConnection
        try
          val ps = conn.prepareStatement(
            "SELECT (SELECT count(*) FROM note_tags WHERE path = ?) + " +
              "(SELECT count(*) FROM note_links WHERE source_path = ?) + " +
              "(SELECT count(*) FROM notes WHERE path = ?)"
          )
          ps.setString(1, "t/Note.md")
          ps.setString(2, "t/Note.md")
          ps.setString(3, "t/Note.md")
          val rs = ps.executeQuery()
          rs.next()
          rs.getLong(1) shouldBe 0L
        finally conn.close()
      }
    }

    "store frontmatter as queryable jsonb and record scans" in withContainers { c =>
      withRepo(c) { (repo, ds) =>
        repo.upsert(note("fm.md", "---\nup: \"[[Map]]\"\nn: 3\n---\nbody"))
        val conn = ds.getConnection
        try
          val rs = conn
            .createStatement()
            .executeQuery("SELECT frontmatter->>'up' FROM notes WHERE path = 'fm.md'")
          rs.next()
          rs.getString(1) shouldBe "[[Map]]"
        finally conn.close()
        repo.recordScan(ScanSummary("full", now, now.plusSeconds(3), 1, 1, 0, 0, 0))
      }
    }
  }
