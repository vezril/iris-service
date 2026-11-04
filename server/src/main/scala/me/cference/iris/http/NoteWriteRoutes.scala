package me.cference.iris.http

import me.cference.iris.domain.ContentHash
import me.cference.iris.http.ApiJson.{given, *}
import me.cference.iris.write.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.*

/**
 * The write surface: `PATCH /notes/{path...}`.
 *
 * Mounted only when `iris.write.enabled` is true, which defaults to FALSE. Phase 2 ships off: the
 * route does not exist until someone turns it on, which is a stronger guarantee than a route that
 * exists and checks a flag.
 *
 * Three refusals happen before anything touches the vault, in this order, and the order is a
 * security property:
 *   1. **identity** — an absent or unknown token is 401, decided before the path is even parsed, so
 *      the response cannot tell an unauthenticated caller which paths exist 2. **confinement** — a
 *      valid token aimed outside its folder is 403 3. **the optimistic lock** — a stale or missing
 *      hash is 409 / 428
 */
final class NoteWriteRoutes(registry: ServiceRegistry, writer: VaultWriter):

  import NoteWriteRoutes.*

  def routes: Route =
    pathPrefix("notes") {
      extractUnmatchedPath { unmatched =>
        patch {
          optionalHeaderValueByName(TokenHeader) { token =>
            entity(as[JsValue]) { body =>
              val raw = decoded(unmatched.toString)
              complete(apply(token, raw, body))
            }
          }
        }
      }
    }

  private def apply(
      token: Option[String],
      rawPath: String,
      body: JsValue
  ): (StatusCodes.ServerError | StatusCodes.ClientError | StatusCodes.Success, JsValue) =
    // Identity FIRST. Everything below leaks information about the vault, so none of it runs for
    // a caller that has not proven who it is.
    registry.resolve(token) match
      case Left(err) => error(StatusCodes.Unauthorized, "unauthorized", err.message)
      case Right(scope) =>
        ConfinedPath.confine(scope, rawPath) match
          case Left(err) => confinementError(err)
          case Right(confined) =>
            parseRequest(body, scope.service) match
              case Left(message) => error(StatusCodes.BadRequest, "bad_request", message)
              case Right((expected, edits)) =>
                writer.write(confined, expected, edits) match
                  case Right(result) =>
                    (
                      StatusCodes.OK,
                      WriteResponse(
                        path = result.path.value,
                        contentHash = result.contentHash.value,
                        changed = result.changed,
                        snapshot = result.snapshot.map(_.objectKey)
                      ).toJson
                    )
                  case Left(err) => writeError(err)

  /**
   * `service` comes from the authenticated identity, never from the request. A caller must not be
   * able to name the managed section it writes — otherwise a service could edit another service's
   * block inside a note in its own folder, which is the cross-contamination the one-folder rule
   * exists to prevent.
   */
  private def parseRequest(
      body: JsValue,
      service: String
  ): Either[String, (Option[ContentHash], Seq[NoteEdit])] =
    for
      obj <- body match
        case o: JsObject => Right(o)
        case _ => Left("body must be a JSON object")
      expected <- obj.fields.get("expectedHash") match
        case None | Some(JsNull) => Right(None)
        case Some(JsString(s)) => ContentHash.parse(s).map(Some(_))
        case Some(_) => Left("expectedHash must be a string")
      editsJson <- obj.fields.get("edits") match
        case Some(JsArray(items)) if items.nonEmpty => Right(items)
        case Some(JsArray(_)) => Left("edits must not be empty")
        case _ => Left("edits must be a non-empty array")
      edits <- editsJson.foldLeft[Either[String, Vector[NoteEdit]]](Right(Vector.empty)) {
        (acc, item) => acc.flatMap(seen => parseEdit(item, service).map(seen :+ _))
      }
    yield (expected, edits)

  private def parseEdit(item: JsValue, service: String): Either[String, NoteEdit] =
    item match
      case o: JsObject =>
        def str(k: String): Either[String, String] =
          o.fields.get(k) match
            case Some(JsString(s)) => Right(s)
            case _ => Left(s"'$k' must be a string")
        o.fields.get("op") match
          case Some(JsString("setFrontmatterKey")) =>
            for k <- str("key"); v <- str("value") yield NoteEdit.SetFrontmatterKey(k, v)
          case Some(JsString("removeFrontmatterKey")) =>
            str("key").map(NoteEdit.RemoveFrontmatterKey.apply)
          case Some(JsString("replaceManagedSection")) =>
            // Note: `service` is the authenticated identity, not a field of the request.
            str("content").map(c => NoteEdit.ReplaceManagedSection(service, c))
          case Some(JsString(other)) => Left(s"unknown op '$other'")
          case _ => Left("each edit needs an 'op' string")
      case _ => Left("each edit must be a JSON object")

  private def confinementError(err: ConfinementError) =
    err match
      case ConfinementError.NoIdentity | ConfinementError.UnknownService =>
        error(StatusCodes.Unauthorized, "unauthorized", err.message)
      case _: ConfinementError.UnparseablePath | ConfinementError.NonCanonicalPath =>
        error(StatusCodes.BadRequest, "bad_request", err.message)
      case _ =>
        // Authenticated, but the target is not theirs. 403 rather than 404: the caller proved an
        // identity, so telling them "not yours" leaks nothing they could not already infer.
        error(StatusCodes.Forbidden, "forbidden", err.message)

  private def writeError(err: WriteError) =
    err match
      case _: WriteError.Conflict =>
        error(StatusCodes.Conflict, "conflict", err.message)
      case WriteError.HashRequired =>
        // RFC 6585: the server requires the request to be conditional.
        error(StatusCodes.PreconditionRequired, "hash_required", err.message)
      case _: WriteError.SymlinkRefused =>
        error(StatusCodes.Forbidden, "forbidden", err.message)
      case _: WriteError.PatchRefused =>
        error(StatusCodes.UnprocessableContent, "unprocessable", err.message)
      case WriteError.SnapshotFailed(cause) =>
        cause match
          case SnapshotError.Rejected(_) =>
            error(StatusCodes.BadGateway, "snapshot_rejected", err.message)
          case _ =>
            // Retryable: the vault is untouched and the caller should come back.
            error(StatusCodes.ServiceUnavailable, "snapshot_unavailable", err.message)
      case _: WriteError.Io =>
        error(StatusCodes.InternalServerError, "io_error", err.message)

  private def error(
      status: StatusCodes.ClientError | StatusCodes.ServerError,
      code: String,
      message: String
  ) =
    (status, ErrorBody(code, message).toJson)

  private def decoded(urlPath: String): String =
    java.net.URLDecoder.decode(urlPath.stripPrefix("/"), "UTF-8")

object NoteWriteRoutes:

  /** The per-service API key header. Tokens arrive from k8s Secrets, never from config files. */
  val TokenHeader = "X-Iris-Service-Token"

  final case class WriteResponse(
      path: String,
      contentHash: String,
      changed: Boolean,
      snapshot: Option[String]
  )

  given RootJsonFormat[WriteResponse] = DefaultJsonProtocol.jsonFormat4(WriteResponse.apply)
