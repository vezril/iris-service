package me.cference.iris.hermes

import me.cference.iris.domain.VaultPath
import org.slf4j.LoggerFactory

import java.time.Instant

/** What happened to a note, as the constellation hears it. */
enum VaultEventKind(val topic: String):
  case Created extends VaultEventKind("vault.note.created")
  case Changed extends VaultEventKind("vault.note.changed")
  case Deleted extends VaultEventKind("vault.note.deleted")

/**
 * One `vault.note.*` event. Field shape mirrors the `VaultNoteEvent` proto proposed to the Lexicon
 * (docs/vault-proto-proposal.md); the interim wire format is the canonical proto-JSON of that
 * message, so swapping in the generated class later changes no bytes.
 *
 * @param contentHash
 *   hash after the change ("" on delete)
 * @param previousHash
 *   hash before the change ("" on create)
 */
final case class VaultNoteEvent(
    kind: VaultEventKind,
    path: VaultPath,
    contentHash: String,
    previousHash: String,
    modifiedAt: Instant,
    tags: Vector[String],
    observedAt: Instant
):
  /** Canonical proto-JSON payload (lowerCamelCase field names, absent-when-default). */
  def payloadJson: String =
    val fields = Vector(
      Some(s""""path":${quote(path.value)}"""),
      Option.when(contentHash.nonEmpty)(s""""contentHash":${quote(contentHash)}"""),
      Option.when(previousHash.nonEmpty)(s""""previousHash":${quote(previousHash)}"""),
      Some(s""""modifiedAt":${quote(modifiedAt.toString)}"""),
      Option.when(tags.nonEmpty)(s""""tags":[${tags.map(quote).mkString(",")}]"""),
      Some(s""""observedAt":${quote(observedAt.toString)}""")
    ).flatten
    fields.mkString("{", ",", "}")

  private def quote(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    s.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"')
    sb.toString

/**
 * The change-feed seam. Called strictly AFTER the index transaction commits — an event must never
 * describe a state the index does not hold. Publishing is best-effort: a lost event is healed by
 * consumers reconciling against the rebuildable read model (no outbox in phase 1, by design).
 */
trait VaultEventPublisher:
  def publish(event: VaultNoteEvent): Unit

object VaultEventPublisher:

  /** The default until the Hermes contract lands and `iris.hermes.enabled` flips on. */
  object NoOp extends VaultEventPublisher:
    def publish(event: VaultNoteEvent): Unit = ()

  /** Logs what WOULD be published — the shape consumers will see; useful in local dev. */
  final class Logging extends VaultEventPublisher:
    private val log = LoggerFactory.getLogger("me.cference.iris.hermes.events")
    def publish(event: VaultNoteEvent): Unit =
      log.info("{} {}", event.kind.topic, event.payloadJson)
