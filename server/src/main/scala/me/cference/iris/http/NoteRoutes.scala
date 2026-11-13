package me.cference.iris.http

import me.cference.iris.domain.VaultPath
import me.cference.iris.http.ApiJson.{given, *}
import me.cference.iris.persistence.NoteQueries
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.{enrichAny, JsArray}

/**
 * The read API over the vault index. Everything here is a query — phase 1 has no mutating endpoint
 * except `/admin/reindex`, which re-reads the vault (never writes to it).
 *
 * Note paths carry spaces, unicode, and slashes: they arrive as the remaining URL path segments,
 * URL-decoded, and are validated through [[VaultPath]] before touching the store.
 */
final class NoteRoutes(queries: NoteQueries, triggerReindex: () => Unit):

  private val MaxLimit = 500
  private val DefaultLimit = 50
  private val MaxDepth = 3
  private val GraphNodeCap = 200
  private val MaxScanLimit = 200
  private val DefaultScanLimit = 20

  def routes: Route =
    pathPrefix("notes") {
      pathEnd {
        get {
          parameters(
            "folder".optional,
            "tag".optional,
            "limit".as[Int].withDefault(DefaultLimit),
            "offset".as[Int].withDefault(0)
          ) { (folder, tag, limit, offset) =>
            if limit < 1 || limit > MaxLimit then
              badRequest(s"limit must be between 1 and $MaxLimit")
            else if offset < 0 then badRequest("offset must be >= 0")
            else complete(queries.listNotes(folder, tag, limit, offset))
          }
        }
      } ~
        // /notes/{path...}/backlinks and /notes/{path...}
        extractUnmatchedPath { unmatched =>
          get {
            val raw = decoded(unmatched.toString)
            if raw.endsWith("/backlinks") then
              withVaultPath(raw.stripSuffix("/backlinks")) { vp =>
                queries.getNote(vp.value) match
                  case Some(view) => complete(JsArray(view.backlinks.map(_.toJson)*))
                  case None => notFound(vp.value)
              }
            else
              withVaultPath(raw) { vp =>
                queries.getNote(vp.value) match
                  case Some(view) => complete(view)
                  case None => notFound(vp.value)
              }
          }
        }
    } ~
      path("tags") {
        get(complete(JsArray(queries.tagCounts().map(_.toJson)*)))
      } ~
      path("links" / "unresolved") {
        get(complete(JsArray(queries.unresolvedTargets().map(_.toJson)*)))
      } ~
      // The frontmatter SHAPE of the vault: which top-level keys exist, how many notes carry each,
      // and at which JSON value types. Content-free — key and type names only, never values, so it
      // sits alongside /tags rather than behind /notes.
      //
      // `?folder=Atlas/Notes/People` is the point of the folder filter: this is the mechanised
      // answer to the design doc's open question about how person/relationship data is encoded,
      // which drives Mnemosyne's parser.
      path("frontmatter" / "keys") {
        get {
          parameters("folder".optional) { folder =>
            complete(JsArray(queries.frontmatterKeys(folder).map(_.toJson)*))
          }
        }
      } ~
      // Scan history. `/metrics` publishes only the latest completed scan, because a gauge cannot
      // carry a series; this is the same bookkeeping as a list, so a bad run stays visible after
      // the next one has overwritten the gauges. Counts and timestamps only — no vault content.
      path("scans") {
        get {
          parameters("limit".as[Int].withDefault(DefaultScanLimit)) { limit =>
            if limit < 1 || limit > MaxScanLimit then
              badRequest(s"limit must be between 1 and $MaxScanLimit")
            else complete(JsArray(queries.recentScans(limit).map(_.toJson)*))
          }
        }
      } ~
      path("graph") {
        get {
          parameters("root", "depth".as[Int].withDefault(1)) { (root, depth) =>
            if depth < 1 || depth > MaxDepth then
              badRequest(s"depth must be between 1 and $MaxDepth")
            else
              withVaultPath(root) { vp =>
                queries.linkGraph(vp.value, depth, GraphNodeCap) match
                  case Some(graph) => complete(graph)
                  case None => notFound(vp.value)
              }
          }
        }
      } ~
      path("admin" / "reindex") {
        post {
          triggerReindex()
          complete(StatusCodes.Accepted, ErrorBody("accepted", "full rescan scheduled").toJson)
        }
      }

  private def decoded(urlPath: String): String =
    java.net.URLDecoder.decode(urlPath.stripPrefix("/"), "UTF-8")

  private def withVaultPath(raw: String)(inner: VaultPath => Route): Route =
    VaultPath.parse(raw) match
      case Right(vp) => inner(vp)
      case Left(err) => badRequest(s"invalid note path: ${err.message}")

  private def badRequest(message: String): Route =
    complete(StatusCodes.BadRequest, ErrorBody("bad_request", message).toJson)

  private def notFound(path: String): Route =
    complete(StatusCodes.NotFound, ErrorBody("not_found", s"note not indexed: $path").toJson)
