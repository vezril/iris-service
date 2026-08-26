package me.cference.iris.http

import me.cference.iris.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{LoggerFactory, MDC}

/**
 * Correlation + access logging for the HTTP route tree (artemis pattern). Wrapping the routes with
 * [[withCorrelationId]] MINTS a fresh id — the HTTP API is untrusted ingress, so any
 * client-supplied `X-Correlation-Id` is ignored — puts it in the MDC for the synchronous route run,
 * access-logs entry + completion, and echoes `X-Correlation-Id` on every response. The inner routes
 * are sealed BELOW the response mapping, so the header also lands on 4xx/5xx.
 */
object RequestTracing:

  private val log = LoggerFactory.getLogger("me.cference.iris.http.access")

  def withCorrelationId(inner: Route): Route =
    val sealedRoute = Route.seal(inner)
    extractRequest { request =>
      val id = CorrelationId.mint()
      val method = request.method.value
      val path = request.uri.path.toString
      mapResponse { response =>
        withMdc(id)(log.info(s"← HTTP $method $path ${response.status.intValue}"))
        response.addHeader(RawHeader(CorrelationId.HttpHeader, id))
      } { ctx =>
        withMdc(id) {
          log.info(s"→ HTTP $method $path")
          sealedRoute(ctx)
        }
      }
    }

  private def withMdc[A](id: String)(body: => A): A =
    MDC.put(CorrelationId.MdcKey, id)
    try body
    finally MDC.remove(CorrelationId.MdcKey)
