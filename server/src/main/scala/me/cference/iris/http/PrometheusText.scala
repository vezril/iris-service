package me.cference.iris.http

/**
 * Minimal Prometheus text exposition (format version 0.0.4), hand-rolled.
 *
 * Iris publishes a handful of gauges, so a client library would be more dependency than signal —
 * the same call the Hermes publisher makes in hand-serializing its proto-JSON until the contract
 * lands. If JVM/process metrics are ever wanted, that is the point to adopt a real registry; the
 * metric names here would not have to change.
 */
object PrometheusText:

  /** One gauge sample. `value` is already in the unit the name claims. */
  final case class Gauge(name: String, help: String, value: Double)

  /**
   * Render in a stable order. Each metric gets its own HELP/TYPE pair; absent metrics must simply
   * not be in the list — a gauge whose value is unknown is omitted, never published as 0, because
   * `time() - 0` on a timestamp gauge reads as half a century of staleness and would page.
   */
  def render(gauges: Seq[Gauge]): String =
    val sb = new StringBuilder
    gauges.foreach { g =>
      sb.append("# HELP ").append(g.name).append(' ').append(escapeHelp(g.help)).append('\n')
      sb.append("# TYPE ").append(g.name).append(" gauge\n")
      sb.append(g.name).append(' ').append(format(g.value)).append('\n')
    }
    sb.toString

  /**
   * Integral values render without a decimal point (`5917`, not `5917.0`); everything else keeps
   * full precision. Prometheus accepts both — this is purely so a human reading `curl /metrics`
   * sees note counts as counts.
   */
  private def format(v: Double): String =
    if v.isWhole && math.abs(v) < 1e15 then v.toLong.toString
    else v.toString

  /**
   * HELP runs to end-of-line, so newlines and backslashes are the only things that can break it.
   */
  private def escapeHelp(s: String): String =
    s.replace("\\", "\\\\").replace("\n", "\\n")
