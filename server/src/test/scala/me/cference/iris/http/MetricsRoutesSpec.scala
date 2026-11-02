package me.cference.iris.http

import me.cference.iris.persistence.{IndexMetrics, IndexMetricsSource, LastScan}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class MetricsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val scanAt = Instant.parse("2026-08-26T12:00:00Z")
  private val noteAt = Instant.parse("2026-08-25T09:30:00Z")

  private def source(m: => IndexMetrics): IndexMetricsSource =
    new IndexMetricsSource:
      def indexMetrics(): IndexMetrics = m

  private val healthy = IndexMetrics(
    indexedNotes = 5917L,
    newestNoteModifiedAt = Some(noteAt),
    lastScan = Some(LastScan("full", scanAt, 5917, 0, 0, 0, 0))
  )

  private def body(m: => IndexMetrics): String =
    var out = ""
    Get("/metrics") ~> MetricsRoutes(source(m)).routes ~> check {
      status shouldBe StatusCodes.OK
      out = responseAs[String]
    }
    out

  "GET /metrics" should {

    "expose the index and scan gauges in exposition format" in {
      val text = body(healthy)
      text should include("# TYPE iris_index_notes gauge")
      text should include("\niris_index_notes 5917\n")
      text should include("\niris_last_scan_notes_seen 5917\n")
      text should include("\niris_last_scan_errors 0\n")
      // Unix seconds, and integral values must not render as 5917.0
      text should include(s"\niris_last_scan_timestamp_seconds ${scanAt.getEpochSecond}\n")
      text should include(
        s"\niris_vault_newest_note_timestamp_seconds ${noteAt.getEpochSecond}\n"
      )
    }

    "make a short index visible as a gap between notes_seen and index_notes" in {
      // The failure this whole subsystem exists for: the scan saw 5,917 files on disk but only
      // 5,886 made it into the index. Both gauges present => Hera can subtract them.
      val short = healthy.copy(indexedNotes = 5886L)
      val text = body(short)
      text should include("\niris_index_notes 5886\n")
      text should include("\niris_last_scan_notes_seen 5917\n")
    }

    "OMIT scan gauges entirely before the first scan completes" in {
      // Publishing 0 for a timestamp would read as decades of staleness and page immediately;
      // absent() is the correct never-scanned signal.
      val text = body(healthy.copy(lastScan = None, newestNoteModifiedAt = None))
      text should include("\niris_index_notes 5917\n")
      text should not include "iris_last_scan_timestamp_seconds"
      text should not include "iris_vault_newest_note_timestamp_seconds"
      text should include("\niris_index_queryable 1\n")
    }

    "report 200 with iris_index_queryable 0 when the read model is unreachable" in {
      // NOT a 5xx: that would zero Prometheus's own `up` and conflate "iris is down" with
      // "iris cannot reach Postgres".
      val text = body(throw new RuntimeException("connection refused"))
      text should include("\niris_index_queryable 0\n")
      text should not include "iris_index_notes"
    }

    "carry no vault content" in {
      val text = body(healthy)
      text should not include "Atlas/"
      text should not include ".md"
    }
  }

  "PrometheusText" should {

    "pair HELP and TYPE with every sample, in order" in {
      val out = PrometheusText.render(
        Vector(
          PrometheusText.Gauge("a_metric", "first", 1.0),
          PrometheusText.Gauge("b_metric", "second", 2.5)
        )
      )
      out shouldBe
        """# HELP a_metric first
          |# TYPE a_metric gauge
          |a_metric 1
          |# HELP b_metric second
          |# TYPE b_metric gauge
          |b_metric 2.5
          |""".stripMargin
    }

    "escape what would otherwise break a HELP line" in {
      val out = PrometheusText.render(
        Vector(PrometheusText.Gauge("m", "two\nlines and a \\ backslash", 0.0))
      )
      out should include("# HELP m two\\nlines and a \\\\ backslash\n")
      // Exactly one HELP line, one TYPE line, one sample — the newline did not split the record.
      out.linesIterator.size shouldBe 3
    }
  }
