package me.cference.iris.http

import me.cference.iris.domain.{ContentHash, VaultPath}
import me.cference.iris.http.ApiJson.{given, *}
import me.cference.iris.write.{SnapshotArchive, SnapshotEntry, SnapshotError}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.*

/**
 * Reading the undo history.
 *
 *   - `GET /notes/{path...}/snapshots` — what is recoverable for this note, newest first
 *   - `GET /notes/{path...}/snapshots/{sha256}` — one snapshot's exact bytes
 *
 * **Must be mounted BEFORE [[NoteRoutes]]**, whose `extractUnmatchedPath` would otherwise swallow
 * `/notes/x/snapshots` as a note literally named `x/snapshots` and answer 404.
 *
 * Read-only by construction: nothing here writes to the vault or to Apollo. Restoring is
 * deliberately not an endpoint — see the note on `GET .../{sha}` below.
 *
 * Unauthenticated, consistent with the rest of the phase-1 read API. That is defensible only
 * because it discloses nothing new: the note API next door already serves the full current text of
 * every note without a token, and a snapshot is a PRIOR version of that same text. If reads ever
 * get authentication, these routes must be included in it.
 */
final class SnapshotRoutes(archive: SnapshotArchive):

  private val DefaultLimit = 50
  private val MaxLimit = 500

  def routes: Route =
    pathPrefix("notes") {
      get {
        extractUnmatchedPath { unmatched =>
          val raw = decoded(unmatched.toString)
          SnapshotRoutes.parse(raw) match
            case None => reject // not a snapshots path; NoteRoutes will handle it
            case Some((notePath, maybeSha)) =>
              VaultPath.parse(notePath) match
                case Left(err) => badRequest(s"invalid note path: ${err.message}")
                case Right(vp) =>
                  maybeSha match
                    case None => listRoute(vp)
                    case Some(shaRaw) =>
                      ContentHash.parse(shaRaw) match
                        case Left(msg) => badRequest(msg)
                        case Right(sha) => fetchRoute(vp, sha)
        }
      }
    }

  private def listRoute(vp: VaultPath): Route =
    parameters("limit".as[Int].withDefault(DefaultLimit)) { limit =>
      if limit < 1 || limit > MaxLimit then badRequest(s"limit must be between 1 and $MaxLimit")
      else
        archive.list(vp, limit) match
          case Right(entries) =>
            complete(
              SnapshotRoutes
                .SnapshotList(path = vp.value, snapshots = entries.map(SnapshotRoutes.view))
                .toJson
            )
          case Left(err) => failure(err)
    }

  /**
   * Returns the snapshot's bytes as `text/markdown`, NOT a restore.
   *
   * Restoring is left to a human on purpose. An endpoint that writes an old version back over the
   * current one is a last-writer-wins overwrite wearing a helpful name — exactly what the
   * optimistic lock exists to forbid — and the moment you want it most is the moment you are least
   * sure what the current state should be. Fetch the bytes, look at them, and write them back
   * through the ordinary PATCH path with the hash you just read, so the lock still applies.
   */
  private def fetchRoute(vp: VaultPath, sha: ContentHash): Route =
    archive.fetch(vp, sha) match
      case Right(bytes) =>
        complete(
          HttpResponse(entity =
            HttpEntity(ContentType(MediaTypes.`text/markdown`, HttpCharsets.`UTF-8`), bytes)
          )
        )
      case Left(err) => failure(err)

  private def failure(err: SnapshotError): Route =
    err match
      case SnapshotError.NotFound(_) =>
        complete(StatusCodes.NotFound, ErrorBody("not_found", err.message).toJson)
      case SnapshotError.Rejected(_) =>
        // The archive holds bytes that do not match their own hash. Not the caller's fault, and
        // not something a retry fixes.
        complete(StatusCodes.BadGateway, ErrorBody("snapshot_corrupt", err.message).toJson)
      case SnapshotError.NotConfigured =>
        complete(StatusCodes.NotImplemented, ErrorBody("no_archive", err.message).toJson)
      case SnapshotError.Unavailable(_) =>
        complete(StatusCodes.ServiceUnavailable, ErrorBody("unavailable", err.message).toJson)

  private def badRequest(message: String): Route =
    complete(StatusCodes.BadRequest, ErrorBody("bad_request", message).toJson)

  private def decoded(urlPath: String): String =
    java.net.URLDecoder.decode(urlPath.stripPrefix("/"), "UTF-8")

object SnapshotRoutes:

  private val Marker = "/snapshots"

  /**
   * Split `A/B.md/snapshots` or `A/B.md/snapshots/<sha>` into the note path and optional hash.
   * `None` means this is not a snapshots request at all, so the route rejects and lets the ordinary
   * note routes have it.
   *
   * Matched on the LAST occurrence, because a note may legitimately live in a folder called
   * `snapshots` — `Atlas/snapshots/note.md/snapshots` must resolve to the note, not the folder.
   */
  private[http] def parse(raw: String): Option[(String, Option[String])] =
    val idx = raw.lastIndexOf(Marker)
    if idx <= 0 then None
    else
      val notePath = raw.substring(0, idx)
      raw.substring(idx + Marker.length) match
        case "" | "/" => Some((notePath, None))
        case rest if rest.startsWith("/") && !rest.drop(1).contains('/') =>
          Some((notePath, Some(rest.drop(1))))
        case _ => None

  final case class SnapshotView(contentHash: String, size: Long, generation: Long)
  final case class SnapshotList(path: String, snapshots: Vector[SnapshotView])

  private[http] def view(e: SnapshotEntry): SnapshotView =
    SnapshotView(e.sha256.value, e.size, e.generation)

  given RootJsonFormat[SnapshotView] = DefaultJsonProtocol.jsonFormat3(SnapshotView.apply)
  given RootJsonFormat[SnapshotList] = DefaultJsonProtocol.jsonFormat2(SnapshotList.apply)
