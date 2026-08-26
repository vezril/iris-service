package me.cference.iris.http

import me.cference.iris.persistence.*
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.*

import java.time.Instant

/** spray-json wire formats for the read API. Instants render as ISO-8601 strings. */
object ApiJson extends SprayJsonSupport with DefaultJsonProtocol:

  given JsonFormat[Instant] with
    def write(i: Instant): JsValue = JsString(i.toString)
    def read(json: JsValue): Instant =
      json match
        case JsString(s) => Instant.parse(s)
        case other => deserializationError(s"expected ISO-8601 string, got $other")

  /**
   * Directly-defined Vector format: the two inherited CollectionFormats members (immSeqFormat /
   * indexedSeqFormat) are ambiguous for Vector under Scala 3; a definition in this object outranks
   * both.
   */
  given [T](using JsonFormat[T]): RootJsonFormat[Vector[T]] with
    def write(v: Vector[T]): JsValue = JsArray(v.map(_.toJson)*)
    def read(json: JsValue): Vector[T] =
      json match
        case JsArray(elements) => elements.map(_.convertTo[T]).toVector
        case other => deserializationError(s"expected array, got $other")

  given RootJsonFormat[NoteSummary] = jsonFormat7(NoteSummary.apply)
  given RootJsonFormat[NoteListPage] = jsonFormat2(NoteListPage.apply)
  given RootJsonFormat[TagOnNote] = jsonFormat3(TagOnNote.apply)
  given RootJsonFormat[LinkView] = jsonFormat5(LinkView.apply)
  given RootJsonFormat[BacklinkView] = jsonFormat2(BacklinkView.apply)
  given RootJsonFormat[TagCount] = jsonFormat2(TagCount.apply)
  given RootJsonFormat[UnresolvedTarget] = jsonFormat2(UnresolvedTarget.apply)
  given RootJsonFormat[GraphNode] = jsonFormat2(GraphNode.apply)
  given RootJsonFormat[GraphEdge] = jsonFormat2(GraphEdge.apply)
  given RootJsonFormat[LinkGraph] = jsonFormat3(LinkGraph.apply)

  /** NoteView renders `frontmatter` as embedded JSON (it is stored as jsonb), not a string. */
  given RootJsonFormat[NoteView] with
    def write(v: NoteView): JsValue =
      JsObject(
        "path" -> JsString(v.path),
        "name" -> JsString(v.name),
        "folder" -> JsString(v.folder),
        "frontmatter" -> v.frontmatterJson.map(_.parseJson).getOrElse(JsNull),
        "frontmatterRaw" -> v.frontmatterRaw.map(JsString(_)).getOrElse(JsNull),
        "frontmatterError" -> v.frontmatterError.map(JsString(_)).getOrElse(JsNull),
        "body" -> JsString(v.body),
        "tags" -> v.tags.toJson,
        "links" -> v.links.toJson,
        "backlinks" -> v.backlinks.toJson,
        "contentHash" -> JsString(v.contentHash),
        "sizeBytes" -> JsNumber(v.sizeBytes),
        "modifiedAt" -> JsString(v.modifiedAt.toString)
      )
    def read(json: JsValue): NoteView =
      deserializationError("NoteView is response-only")

  final case class ErrorBody(error: String, message: String)
  given RootJsonFormat[ErrorBody] = jsonFormat2(ErrorBody.apply)
