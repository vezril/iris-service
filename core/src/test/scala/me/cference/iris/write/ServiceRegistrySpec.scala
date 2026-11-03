package me.cference.iris.write

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Identity resolution. The property under test throughout: a caller presents a TOKEN and can prove
 * an identity, but can never claim one — nothing here accepts a service name from the wire.
 */
class ServiceRegistrySpec extends AnyWordSpec with Matchers:

  private val registry = ServiceRegistry
    .build(Map("Mnemosyne" -> "tok-mnemo", "Dionysus" -> "tok-dio"))
    .toOption
    .get

  "ServiceRegistry.build" should {

    "map each configured service to its own folder" in {
      registry.size shouldBe 2
      registry.services shouldBe Vector("Dionysus", "Mnemosyne")
      registry.resolve(Some("tok-mnemo")).map(_.root) shouldBe
        Right("Atlas/Olympus/Mnemosyne")
      registry.resolve(Some("tok-dio")).map(_.root) shouldBe Right("Atlas/Olympus/Dionysus")
    }

    "refuse an empty token, which would authenticate a caller presenting nothing" in {
      ServiceRegistry.build(Map("Mnemosyne" -> "")) shouldBe
        Left(RegistryError.EmptyToken("Mnemosyne"))
    }

    "refuse a shared token, naming both sides of the collision" in {
      // Left tolerated, one service would write as the other and WHICH would depend on ordering.
      ServiceRegistry.build(Map("Mnemosyne" -> "same", "Dionysus" -> "same")) shouldBe
        Left(RegistryError.DuplicateToken("Mnemosyne", "Dionysus"))
    }

    "refuse an illegal service name instead of sanitizing it" in {
      ServiceRegistry.build(Map("../.." -> "tok")) shouldBe
        Left(RegistryError.IllegalServiceName("../.."))
      ServiceRegistry.build(Map("a/b" -> "tok")) shouldBe
        Left(RegistryError.IllegalServiceName("a/b"))
    }
  }

  "ServiceRegistry.resolve" should {

    "deny by default — no identity is a refusal, not an unscoped write" in {
      registry.resolve(None) shouldBe Left(ConfinementError.NoIdentity)
      registry.resolve(Some("")) shouldBe Left(ConfinementError.NoIdentity)
    }

    "refuse an unknown token without echoing it back" in {
      val err = registry.resolve(Some("tok-forged"))
      err shouldBe Left(ConfinementError.UnknownService)
      // The refusal must not leak the presented secret into a message that will be logged.
      err.left.toOption.map(_.message).get should not include "tok-forged"
    }

    "refuse a token that is a prefix or extension of a real one" in {
      registry.resolve(Some("tok-mnem")).isLeft shouldBe true
      registry.resolve(Some("tok-mnemo-extra")).isLeft shouldBe true
      registry.resolve(Some("TOK-MNEMO")).isLeft shouldBe true
    }

    "authenticate NOBODY when empty — the correct phase-1 default" in {
      ServiceRegistry.empty.resolve(Some("anything")) shouldBe
        Left(ConfinementError.UnknownService)
      ServiceRegistry.empty.resolve(None) shouldBe Left(ConfinementError.NoIdentity)
      ServiceRegistry.empty.size shouldBe 0
    }
  }

  "ServiceRegistry.confine" should {

    "resolve identity and confine the path in one step" in {
      registry
        .confine(Some("tok-mnemo"), "Atlas/Olympus/Mnemosyne/x.md")
        .map(_.path.value) shouldBe Right("Atlas/Olympus/Mnemosyne/x.md")
    }

    "refuse a valid token aimed at another service's folder" in {
      // The cross-contamination case: real identity, wrong target.
      registry.confine(Some("tok-mnemo"), "Atlas/Olympus/Dionysus/x.md") match
        case Left(_: ConfinementError.OutsideScope) => succeed
        case other => fail(s"ESCAPE or wrong error: $other")
    }

    "check identity BEFORE the path, so a forged token cannot probe paths" in {
      // A bad token must fail identically whether the path is valid or absurd — otherwise the
      // error type tells an unauthenticated caller which paths exist in scope.
      val a = registry.confine(Some("forged"), "Atlas/Olympus/Mnemosyne/x.md")
      val b = registry.confine(Some("forged"), "/../../etc/passwd")
      a shouldBe Left(ConfinementError.UnknownService)
      b shouldBe Left(ConfinementError.UnknownService)
      a shouldBe b
    }

    "refuse every write when no identity is presented, whatever the path" in {
      registry.confine(None, "Atlas/Olympus/Mnemosyne/x.md") shouldBe
        Left(ConfinementError.NoIdentity)
    }
  }
