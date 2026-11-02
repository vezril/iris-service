package me.cference.iris.http

import me.cference.iris.http.PrometheusText.Gauge
import me.cference.iris.persistence.{IndexMetrics, IndexMetricsSource}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/**
 * `GET /metrics` — the Prometheus scrape target, per the constellation's convention of health and
 * metrics on the service port (`codex/docs/observability.md`).
 *
 * The gauges, and what each is actually good for, are documented on
 * [[me.cference.iris.persistence.IndexMetricsSource]]. The short version: scan liveness and index
 * shortfall are strong signals worth alerting on; vault mtime age is a weak proxy that cannot tell
 * a wedged sidecar from a quiet week, and is published for dashboards only.
 *
 * Phase 1 exposes no vault content here — counts, timestamps, and scan outcomes only. (The note API
 * next door already serves full note text unauthenticated, so this endpoint is not the sensitive
 * one; it is still worth keeping it content-free on its own merits.)
 */
final class MetricsRoutes(source: IndexMetricsSource):

  private val log = LoggerFactory.getLogger(getClass)

  def routes: Route =
    path("metrics") {
      get {
        complete(
          try
            HttpResponse(
              status = StatusCodes.OK,
              entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, render(source.indexMetrics()))
            )
          catch
            case NonFatal(e) =>
              // A 5xx here would zero Prometheus's own `up` metric, conflating "iris is down" with
              // "iris cannot reach its read model". Report 200 with iris_index_queryable 0 so the
              // two stay distinguishable, and so the scrape itself records the failure as data.
              log.warn(s"metrics scrape could not read the index: ${e.getMessage}", e)
              HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, unqueryable)
              )
        )
      }
    }

  private def render(m: IndexMetrics): String =
    val base = Vector(
      Gauge(
        "iris_index_queryable",
        "1 if the read model answered this scrape, 0 if it could not be reached.",
        1.0
      ),
      Gauge("iris_index_notes", "Notes currently in the Postgres index.", m.indexedNotes.toDouble)
    )

    // Weak signal, deliberately: this cannot separate a wedged sync sidecar from Calvin simply not
    // having edited anything. Dashboard line, not an alert.
    val freshness = m.newestNoteModifiedAt.map { at =>
      Gauge(
        "iris_vault_newest_note_timestamp_seconds",
        "Newest note mtime in the index, unix seconds. WEAK freshness proxy — a quiet vault looks "
          + "identical to a wedged sidecar. Do not alert on this alone.",
        at.toEpochMilli / 1000.0
      )
    }

    // Strong signals. Omitted entirely before the first scan completes, so `absent()` is the
    // never-scanned alert rather than a fake zero.
    val scan = m.lastScan.toVector.flatMap { s =>
      Vector(
        Gauge(
          "iris_last_scan_timestamp_seconds",
          "Completion of the last finished scan, unix seconds. Stops advancing iff Iris's own "
            + "scanning has stopped — alert on age > 2x rescan-interval.",
          s.finishedAt.toEpochMilli / 1000.0
        ),
        Gauge(
          "iris_last_scan_notes_seen",
          "Markdown files the last scan found on disk. Compare against iris_index_notes: a "
            + "positive difference is the silent WatchService drop, i.e. a short index.",
          s.notesSeen.toDouble
        ),
        Gauge("iris_last_scan_created", "Notes added by the last scan.", s.created.toDouble),
        Gauge("iris_last_scan_changed", "Notes updated by the last scan.", s.changed.toDouble),
        Gauge("iris_last_scan_deleted", "Notes removed by the last scan.", s.deleted.toDouble),
        Gauge(
          "iris_last_scan_errors",
          "Files the last scan could not read or parse. Transient ones heal on the next pass; a "
            + "persistent non-zero value means notes are missing from the index.",
          s.errors.toDouble
        )
      )
    }

    PrometheusText.render(base ++ freshness ++ scan)

  private val unqueryable = PrometheusText.render(
    Vector(
      Gauge(
        "iris_index_queryable",
        "1 if the read model answered this scrape, 0 if it could not be reached.",
        0.0
      )
    )
  )
