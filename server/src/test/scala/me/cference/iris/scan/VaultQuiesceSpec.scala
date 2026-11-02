package me.cference.iris.scan

import me.cference.iris.config.QuiesceConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import scala.concurrent.duration.*

/**
 * The quiesce loop drives its own clock and sleep, so these specs run in microseconds against a
 * scripted sequence of fingerprints — no sync sidecar, no Docker, no real waiting.
 */
class VaultQuiesceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val cfg = QuiesceConfig(
    enabled = true,
    pollInterval = 5.seconds,
    stableSamples = 3,
    maxWait = 15.minutes
  )

  private def fp(count: Int, bytes: Long = 0L) =
    VaultFingerprint(count, bytes, Some(Instant.parse("2026-08-26T12:00:00Z")))

  /** Replays `samples` in order, repeating the last one forever; advances a fake clock on sleep. */
  private def run(
      samples: Seq[VaultFingerprint],
      config: QuiesceConfig = cfg
  ): (VaultQuiesce.Outcome, Int) =
    var i = 0
    var nanos = 0L
    val outcome = VaultQuiesce.awaitStableWith(
      sample = () =>
        val s = samples(math.min(i, samples.size - 1))
        i += 1
        s
      ,
      cfg = config,
      sleep = d => nanos += d.toNanos,
      nanoTime = () => nanos
    )
    (outcome, i)

  "VaultQuiesce.awaitStableWith" should {

    "settle once the tree holds the same fingerprint for stable-samples polls" in {
      val (outcome, _) = run(Seq(fp(100), fp(3000), fp(5917), fp(5917), fp(5917), fp(5917)))
      outcome match
        case VaultQuiesce.Outcome.Stable(f, waited) =>
          f.fileCount shouldBe 5917
          waited shouldBe 25.seconds // 5 polls to reach the 3rd unchanged comparison
        case other => fail(s"expected Stable, got $other")
    }

    "restart the count when the tree moves again mid-wait" in {
      // Two unchanged polls, then a late file lands: the streak must reset, not carry.
      val (outcome, _) = run(
        Seq(fp(5917), fp(5917), fp(5917), fp(5918), fp(5918), fp(5918), fp(5918))
      )
      outcome match
        case VaultQuiesce.Outcome.Stable(f, _) => f.fileCount shouldBe 5918
        case other => fail(s"expected Stable, got $other")
    }

    "NEVER treat an empty tree as settled — that is the first-boot failure itself" in {
      // The sidecar has landed nothing; an unchanged zero must not pass for quiet.
      val (outcome, _) = run(Seq(fp(0)))
      outcome match
        case VaultQuiesce.Outcome.TimedOut(f, waited) =>
          f.fileCount shouldBe 0
          waited should be >= cfg.maxWait
        case other => fail(s"expected TimedOut, got $other")
    }

    "fail OPEN when the mirror is still filling at max-wait" in {
      // A tree that grows by one file every poll, forever, must still scan eventually.
      var n = 0
      var nanos = 0L
      val outcome = VaultQuiesce.awaitStableWith(
        sample = () => { n += 1; fp(n) },
        cfg = cfg,
        sleep = d => nanos += d.toNanos,
        nanoTime = () => nanos
      )
      outcome match
        case VaultQuiesce.Outcome.TimedOut(f, _) => f.fileCount should be > 100
        case other => fail(s"expected TimedOut, got $other")
    }

    "treat a size change at a constant file count as movement" in {
      // Obsidian Sync writes a file's bytes after creating it: count holds, size climbs.
      val (outcome, _) = run(
        Seq(fp(10, 1000), fp(10, 2000), fp(10, 3000), fp(10, 3000), fp(10, 3000), fp(10, 3000))
      )
      outcome match
        case VaultQuiesce.Outcome.Stable(f, _) => f.totalBytes shouldBe 3000
        case other => fail(s"expected Stable, got $other")
    }

    "settle immediately when stable-samples is 1 and the tree is already quiet" in {
      val (outcome, calls) = run(Seq(fp(5917)), cfg.copy(stableSamples = 1))
      outcome match
        case VaultQuiesce.Outcome.Stable(_, waited) => waited shouldBe 5.seconds
        case other => fail(s"expected Stable, got $other")
      calls shouldBe 2 // the baseline sample plus one unchanged confirmation
    }
  }

  // --- fingerprint, against a real directory ------------------------------------------------

  private var root: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    root = Files.createTempDirectory("iris-quiesce-spec")
    write("Home.md", "# home")
    write("Atlas/Notes/People/Alcvin Ramos.md", "a person")
    write("Atlas/Notes/Ideas/Uplay Recovery Codes.mdenc", "ENCRYPTED")
    write(".obsidian/app.json", "{}")

  override def afterAll(): Unit =
    Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p => Files.delete(p))

  private def write(rel: String, content: String): Unit =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    ()

  "VaultQuiesce.fingerprint" should {

    "count only the files the scanner would index" in {
      val f = VaultQuiesce.fingerprint(root)
      f.fileCount shouldBe 2 // the .mdenc and the dot-directory are not ours to see
      f.totalBytes shouldBe ("# home".length + "a person".length).toLong
      f.newestMtime shouldBe defined
    }

    "report an empty fingerprint for a root the sidecar has not created yet" in {
      VaultQuiesce.fingerprint(root.resolve("nope")) shouldBe
        VaultFingerprint(0, 0L, None)
    }
  }
