package me.cference.iris.config

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.DispatcherSelector
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

/**
 * Guards the shipped defaults and the config paths `Main` looks up by string. A typo in a
 * dispatcher path or a drifted default would otherwise surface for the first time at boot in the
 * cluster, which is the one place it is expensive to find.
 */
class AppConfigSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit("app-config-spec", ConfigFactory.load())

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "The shipped application.conf" should {

    val cfg = AppConfig.load(ConfigFactory.load())

    "load every block without a missing key" in {
      cfg.http.port shouldBe 8080
      cfg.vault.root.toString shouldBe "/vault"
      cfg.db.jdbcUrl should startWith("jdbc:postgresql://")
      cfg.hermes.enabled shouldBe false // phase 1: the change feed stays off
    }

    "default the rescan to 30m — the STANDING net for the WatchService race" in {
      // Not cosmetic: 6h was the old default and the deployed chart has overridden it to 30m
      // since 2026-08-26. A silently-short index is a wrong-but-plausible answer, so the window
      // must stay small. Do not relax past 1h.
      cfg.scan.rescanInterval shouldBe 30.minutes
      cfg.scan.rescanInterval should be <= 1.hour
    }

    "enable the quiesce gate by default, with a bounded wait" in {
      cfg.scan.quiesce.enabled shouldBe true
      cfg.scan.quiesce.stableSamples should be >= 2
      cfg.scan.quiesce.pollInterval should be > 0.seconds
      // Bounded: the gate fails open, and boot must not hang on a sidecar that never delivers.
      cfg.scan.quiesce.maxWait shouldBe 15.minutes
    }

    "watch by default, debounced for the sidecar's repeated mid-sync touches" in {
      cfg.scan.watchEnabled shouldBe true
      cfg.scan.debounce shouldBe 2.seconds
    }
  }

  "The vault-io dispatcher" should {

    "resolve at the path Main looks up by string" in {
      // Main parks the quiesce gate here for up to 15m; if this path is wrong it throws at boot.
      val ec = testKit.system.dispatchers
        .lookup(DispatcherSelector.fromConfig("iris.vault-io-dispatcher"))
      ec should not be testKit.system.executionContext
    }

    "run blocking work off the default dispatcher" in {
      val ec = testKit.system.dispatchers
        .lookup(DispatcherSelector.fromConfig("iris.vault-io-dispatcher"))
      val threadName = scala.concurrent.Await
        .result(scala.concurrent.Future(Thread.currentThread().getName)(ec), 5.seconds)
      threadName should include("vault-io-dispatcher")
    }
  }
