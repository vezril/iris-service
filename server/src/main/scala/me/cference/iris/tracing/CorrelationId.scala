package me.cference.iris.tracing

import org.slf4j.MDC

import java.security.SecureRandom

/**
 * The per-request correlation id (constellation request-tracing convention). Names mirror the
 * shared Lexicon `CorrelationNames` values verbatim; switch to the generated constants when the
 * lexicon dependency lands with the Hermes milestone — the values must not drift.
 *
 * Trust posture: the HTTP edge MINTS and ignores any client-supplied header (anti-injection).
 */
object CorrelationId:

  /** MDC key → promoted to a top-level JSON log field by the Logstash encoder. */
  val MdcKey: String = "correlationId"

  /** HTTP response header echoed to the caller (title-case). */
  val HttpHeader: String = "X-Correlation-Id"

  private val Rng = SecureRandom()
  private val Alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
  private val Length = 12

  /** A fresh, short, URL-safe token. Uniqueness + log-friendliness are all that matter. */
  def mint(): String =
    LazyList.continually(Alphabet(Rng.nextInt(Alphabet.length))).take(Length).mkString

  /** The id currently in the MDC, if any — the source outbound propagation reads. */
  def current(): Option[String] = Option(MDC.get(MdcKey)).filter(_.nonEmpty)
