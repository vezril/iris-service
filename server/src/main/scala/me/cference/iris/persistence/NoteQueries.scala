package me.cference.iris.persistence

import java.time.Instant

/** A note in a list response — everything but the body. */
final case class NoteSummary(
    path: String,
    name: String,
    folder: String,
    tags: Vector[String],
    contentHash: String,
    sizeBytes: Long,
    modifiedAt: Instant
)

final case class NoteListPage(total: Long, notes: Vector[NoteSummary])

final case class TagOnNote(tag: String, raw: String, source: String)
final case class LinkView(
    rawTarget: String,
    header: Option[String],
    alias: Option[String],
    embed: Boolean,
    resolvedPath: Option[String]
)
final case class BacklinkView(sourcePath: String, alias: Option[String])

/** The full single-note view: content + relations + the hash phase 2 will lock on. */
final case class NoteView(
    path: String,
    name: String,
    folder: String,
    frontmatterJson: Option[String],
    frontmatterRaw: Option[String],
    frontmatterError: Option[String],
    body: String,
    tags: Vector[TagOnNote],
    links: Vector[LinkView],
    backlinks: Vector[BacklinkView],
    contentHash: String,
    sizeBytes: Long,
    modifiedAt: Instant
)

final case class TagCount(tag: String, notes: Long)
final case class UnresolvedTarget(rawTarget: String, referencingNotes: Long)

/**
 * One completed scan, as the history endpoint reports it.
 *
 * Deliberately its own type rather than a reuse of the scanner's [[ScanSummary]] bookkeeping row or
 * of [[LastScan]] (the metrics projection, which drops `startedAt`): this is the API's wire shape,
 * and it should not shift because either of the other two gained a field.
 *
 * `startedAt` is here and not on [[LastScan]] on purpose — with both ends, a reader can see how
 * long a scan took, which is a signal `/metrics` cannot express as a gauge.
 */
final case class ScanRecord(
    kind: String,
    startedAt: Instant,
    finishedAt: Instant,
    notesSeen: Int,
    created: Int,
    changed: Int,
    deleted: Int,
    errors: Int
)

final case class GraphNode(path: String, name: String)
final case class GraphEdge(source: String, target: String)
final case class LinkGraph(root: String, nodes: Vector[GraphNode], edges: Vector[GraphEdge])

/**
 * The read surface the HTTP layer consumes — split from [[NoteRepository]] so route tests can run
 * against a stub without a database.
 */
trait NoteQueries:

  /** List notes, filtered by folder prefix and/or normalized tag. */
  def listNotes(
      folder: Option[String],
      tag: Option[String],
      limit: Int,
      offset: Int
  ): NoteListPage

  /** The full view of one note, or None if it is not indexed. */
  def getNote(path: String): Option[NoteView]

  /** Every tag with the number of notes carrying it. */
  def tagCounts(): Vector[TagCount]

  /** Unresolved (non-embed) link targets with how many notes reference them. */
  def unresolvedTargets(): Vector[UnresolvedTarget]

  /**
   * Completed scans, newest first, at most `limit` of them.
   *
   * In-flight rows (NULL `finished_at`) are excluded, matching the metrics surface: reporting a
   * scan as finished while it is still walking would lie in the one direction that matters. An
   * empty vector therefore means "no scan has completed", never "scanning is broken".
   */
  def recentScans(limit: Int): Vector[ScanRecord]

  /** Bounded undirected BFS over resolved links, rooted at `path`. */
  def linkGraph(path: String, depth: Int, nodeCap: Int): Option[LinkGraph]
