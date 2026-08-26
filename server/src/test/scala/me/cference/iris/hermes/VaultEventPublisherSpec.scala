package me.cference.iris.hermes

import me.cference.iris.domain.VaultPath
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class VaultEventPublisherSpec extends AnyWordSpec with Matchers:

  private val path = VaultPath.parse("Atlas/Notes/People/Alcvin Ramos.md").toOption.get
  private val t0 = Instant.parse("2026-08-26T12:00:00Z")
  private val t1 = Instant.parse("2026-08-26T12:00:01Z")

  "VaultNoteEvent.payloadJson" should {

    "render canonical proto-JSON for a change" in {
      val e = VaultNoteEvent(
        VaultEventKind.Changed,
        path,
        contentHash = "a" * 64,
        previousHash = "b" * 64,
        modifiedAt = t0,
        tags = Vector("music", "shakuhachi"),
        observedAt = t1
      )
      e.kind.topic shouldBe "vault.note.changed"
      e.payloadJson shouldBe
        s"""{"path":"Atlas/Notes/People/Alcvin Ramos.md","contentHash":"${"a" * 64}","previousHash":"${"b" * 64}","modifiedAt":"2026-08-26T12:00:00Z","tags":["music","shakuhachi"],"observedAt":"2026-08-26T12:00:01Z"}"""
    }

    "omit proto-default (empty) fields, as canonical proto-JSON does" in {
      val e = VaultNoteEvent(
        VaultEventKind.Deleted,
        path,
        contentHash = "",
        previousHash = "b" * 64,
        modifiedAt = t0,
        tags = Vector.empty,
        observedAt = t1
      )
      e.payloadJson should not include "contentHash"
      e.payloadJson should not include "tags"
      e.payloadJson should include(""""previousHash"""")
    }

    "escape quotes and control characters in paths" in {
      val weird = VaultPath.parse("""a/"quoted".md""").toOption.get
      val e = VaultNoteEvent(VaultEventKind.Created, weird, "a" * 64, "", t0, Vector.empty, t1)
      e.payloadJson should include("""\"quoted\"""")
    }
  }
