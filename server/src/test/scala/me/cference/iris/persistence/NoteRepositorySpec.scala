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

  /**
   * One container is shared across the suite (`TestContainerForAll`), so every test starts by
   * emptying the tables. Tests that assert only on rows they inserted themselves survive without
   * this; tests that assert on AGGREGATES — `count(*)`, `max(modified_at)`, the latest scan row —
   * read whatever earlier tests left behind, and silently depend on declaration order. Truncating
   * here rather than per-test makes the whole suite order-independent.
   *
   * `notes` cascades to `note_tags` and `note_links`; `scans` is independent.
   */
  private def withRepo[A](c: Containers)(f: (NoteRepository, HikariDataSource) => A): A =
    val cfg = DbConfig(c.jdbcUrl, c.username, c.password, 30.seconds)
    SchemaMigrator.migrate(cfg).left.foreach(e => fail(e.message))
    val hc = new HikariConfig()
    hc.setJdbcUrl(c.jdbcUrl)
    hc.setUsername(c.username)
    hc.setPassword(c.password)
    hc.setMaximumPoolSize(2)
    val ds = new HikariDataSource(hc)
    try
      val conn = ds.getConnection
      try conn.createStatement().executeUpdate("TRUNCATE notes, scans CASCADE")
      finally conn.close()
      f(NoteRepository(ds), ds)
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

    "report empty metrics on a fresh index, with no scan row to speak of" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        val m = repo.indexMetrics()
        m.indexedNotes shouldBe 0L
        // Both absent rather than zero: the route omits the gauges entirely, so Prometheus sees
        // absent() instead of a 1970 timestamp that would read as decades of staleness.
        m.newestNoteModifiedAt shouldBe None
        m.lastScan shouldBe None
      }
    }

    "report the newest note mtime and the LAST COMPLETED scan" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        repo.upsert(note("old.md", "older"))
        val newer = me.cference.iris.parse.NoteParser.parse(
          VaultPath.parse("new.md").toOption.get,
          "newer".getBytes(StandardCharsets.UTF_8),
          now.plusSeconds(3600)
        )
        repo.upsert(newer)

        repo.recordScan(ScanSummary("full", now, now.plusSeconds(1), 2, 2, 0, 0, 0))
        repo.recordScan(ScanSummary("watch", now, now.plusSeconds(90), 2, 0, 1, 0, 4))

        val m = repo.indexMetrics()
        m.indexedNotes shouldBe 2L
        m.newestNoteModifiedAt shouldBe Some(now.plusSeconds(3600))
        // The later finished_at wins, not insertion order and not the 'full' kind.
        m.lastScan.map(_.kind) shouldBe Some("watch")
        m.lastScan.map(_.finishedAt) shouldBe Some(now.plusSeconds(90))
        m.lastScan.map(_.changed) shouldBe Some(1)
        m.lastScan.map(_.errors) shouldBe Some(4)
      }
    }

    "ignore an in-flight scan so the liveness gauge cannot report a scan that is still walking" in
      withContainers { c =>
        withRepo(c) { (repo, ds) =>
          repo.recordScan(ScanSummary("full", now, now.plusSeconds(5), 1, 1, 0, 0, 0))
          // A row with NULL finished_at: started later, not done. recordScan only writes finished
          // scans, so insert directly — this is the shape IndexReconciler would leave on a crash.
          val conn = ds.getConnection
          try
            val ps = conn.prepareStatement(
              "INSERT INTO scans (kind, started_at, finished_at) VALUES ('full', ?, NULL)"
            )
            ps.setTimestamp(1, java.sql.Timestamp.from(now.plusSeconds(600)))
            ps.executeUpdate()
          finally conn.close()

          repo.indexMetrics().lastScan.map(_.finishedAt) shouldBe Some(now.plusSeconds(5))
        }
      }

    "surface a short index as a gap between notes_seen and indexedNotes" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        // The silent WatchService drop, as the metrics see it: the scan walked 5,917 files but
        // only 1 reached the index. Hera subtracts the two gauges to detect exactly this.
        repo.upsert(note("only.md", "the one that landed"))
        repo.recordScan(ScanSummary("full", now, now.plusSeconds(2), 5917, 1, 0, 0, 0))

        val m = repo.indexMetrics()
        m.lastScan.map(_.notesSeen) shouldBe Some(5917)
        m.indexedNotes shouldBe 1L
        m.lastScan.map(_.notesSeen.toLong - m.indexedNotes) shouldBe Some(5916L)
      }
    }
  }

  "recentScans" should {

    "return nothing before any scan completes — not a zero-valued row" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        repo.recentScans(20) shouldBe empty
      }
    }

    "return completed scans newest first, carrying both ends of each" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        repo.recordScan(ScanSummary("full", now, now.plusSeconds(94), 5917, 5917, 0, 0, 3))
        repo.recordScan(
          ScanSummary("watch", now.plusSeconds(600), now.plusSeconds(602), 5917, 0, 1, 0, 0)
        )

        val scans = repo.recentScans(20)
        scans.map(_.kind) shouldBe Vector("watch", "full")
        scans.head.startedAt shouldBe now.plusSeconds(600)
        scans.head.finishedAt shouldBe now.plusSeconds(602)
        scans(1).errors shouldBe 3
        scans(1).created shouldBe 5917
      }
    }

    "respect the limit" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        (1 to 5).foreach { i =>
          repo.recordScan(ScanSummary("full", now, now.plusSeconds(i.toLong), i, 0, 0, 0, 0))
        }
        repo.recentScans(3).map(_.notesSeen) shouldBe Vector(5, 4, 3)
      }
    }

    /**
     * The head of the history and the gauge must agree. If the two surfaces ordered differently,
     * the console would show a "latest scan" card contradicting the scan at the top of its own list
     * — the exact kind of quiet disagreement this project treats as a lie.
     */
    "agree with the metrics surface about which scan is latest" in withContainers { c =>
      withRepo(c) { (repo, _) =>
        // Same finished_at, so only the id tie-break can decide. Both surfaces order by
        // finished_at DESC, id DESC, so the later INSERT must win in both.
        repo.recordScan(ScanSummary("full", now, now.plusSeconds(10), 1, 0, 0, 0, 0))
        repo.recordScan(ScanSummary("watch", now, now.plusSeconds(10), 2, 0, 0, 0, 0))

        val head = repo.recentScans(20).head
        val gauge = repo.indexMetrics().lastScan
        head.kind shouldBe "watch"
        gauge.map(_.kind) shouldBe Some(head.kind)
        gauge.map(_.notesSeen) shouldBe Some(head.notesSeen)
        gauge.map(_.finishedAt) shouldBe Some(head.finishedAt)
      }
    }

    "exclude an in-flight scan, matching the metrics surface" in withContainers { c =>
      withRepo(c) { (repo, ds) =>
        repo.recordScan(ScanSummary("full", now, now.plusSeconds(5), 1, 1, 0, 0, 0))
        val conn = ds.getConnection
        try
          val ps = conn.prepareStatement(
            "INSERT INTO scans (kind, started_at, finished_at) VALUES ('full', ?, NULL)"
          )
          ps.setTimestamp(1, java.sql.Timestamp.from(now.plusSeconds(600)))
          ps.executeUpdate()
        finally conn.close()

        repo.recentScans(20).map(_.finishedAt) shouldBe Vector(now.plusSeconds(5))
      }
    }
  }
