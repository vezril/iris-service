package me.cference.iris.persistence

import me.cference.iris.domain.*

import java.sql.{Connection, Timestamp}
import java.time.Instant
import javax.sql.DataSource
import scala.collection.mutable

/** What the scanner needs to know about an indexed note to decide "changed?". */
final case class IndexedMeta(
    path: String,
    sizeBytes: Long,
    modifiedAt: Instant,
    contentHash: String
)

/** One completed scan's bookkeeping row. */
final case class ScanSummary(
    kind: String,
    startedAt: Instant,
    finishedAt: Instant,
    notesSeen: Int,
    created: Int,
    changed: Int,
    deleted: Int,
    errors: Int
)

/**
 * The Postgres read model, plain JDBC. The workload is upsert-shaped: `INSERT ... ON CONFLICT DO
 * UPDATE` for notes, delete-and-reinsert for tags and links, all inside one transaction per note so
 * the index never shows a half-updated note.
 *
 * Wikilink resolution lives here because it is index-relative: exact vault path first (with or
 * without `.md`), else a unique case-insensitive basename match, else unresolved (`NULL` — data,
 * not an error). Upserting or deleting a note re-resolves the links that could point at it.
 */
final class NoteRepository(ds: DataSource):

  /** All indexed note metadata, for the scanner's cheap change comparison. */
  def allMeta(): Map[String, IndexedMeta] =
    withConnection { conn =>
      val stmt =
        conn.prepareStatement("SELECT path, size_bytes, modified_at, content_hash FROM notes")
      try
        val rs = stmt.executeQuery()
        val out = mutable.Map.empty[String, IndexedMeta]
        while rs.next() do
          val m = IndexedMeta(
            rs.getString("path"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("modified_at").toInstant,
            rs.getString("content_hash").trim
          )
          out.update(m.path, m)
        out.toMap
      finally stmt.close()
    }

  /** Upsert a parsed note and re-resolve links affected by its (re)appearance. */
  def upsert(note: Note): Unit =
    inTransaction { conn =>
      upsertNoteRow(conn, note)
      replaceTags(conn, note)
      replaceLinks(conn, note)
      reResolveLinksTargeting(conn, note.path)
    }

  /** Remove a deleted note and unresolve/re-resolve links that pointed at it. */
  def delete(path: VaultPath): Unit =
    inTransaction { conn =>
      execUpdate(conn, "DELETE FROM notes WHERE path = ?")(_.setString(1, path.value))
      reResolveLinksTargeting(conn, path)
    }

  def recordScan(s: ScanSummary): Unit =
    withConnection { conn =>
      execUpdate(
        conn,
        """INSERT INTO scans (kind, started_at, finished_at, notes_seen, created, changed, deleted, errors)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      ) { ps =>
        ps.setString(1, s.kind)
        ps.setTimestamp(2, Timestamp.from(s.startedAt))
        ps.setTimestamp(3, Timestamp.from(s.finishedAt))
        ps.setInt(4, s.notesSeen)
        ps.setInt(5, s.created)
        ps.setInt(6, s.changed)
        ps.setInt(7, s.deleted)
        ps.setInt(8, s.errors)
      }
    }

  /** Count of indexed notes (health/verification surface). */
  def noteCount(): Long =
    withConnection { conn =>
      val stmt = conn.prepareStatement("SELECT count(*) FROM notes")
      try
        val rs = stmt.executeQuery()
        rs.next()
        rs.getLong(1)
      finally stmt.close()
    }

  // --- note row -----------------------------------------------------------------

  private def upsertNoteRow(conn: Connection, note: Note): Unit =
    execUpdate(
      conn,
      """INSERT INTO notes (path, folder, name, content_hash, size_bytes, modified_at,
        |                   frontmatter, frontmatter_raw, frontmatter_error, body, indexed_at)
        |VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
        |ON CONFLICT (path) DO UPDATE SET
        |  folder = EXCLUDED.folder,
        |  name = EXCLUDED.name,
        |  content_hash = EXCLUDED.content_hash,
        |  size_bytes = EXCLUDED.size_bytes,
        |  modified_at = EXCLUDED.modified_at,
        |  frontmatter = EXCLUDED.frontmatter,
        |  frontmatter_raw = EXCLUDED.frontmatter_raw,
        |  frontmatter_error = EXCLUDED.frontmatter_error,
        |  body = EXCLUDED.body,
        |  indexed_at = now()""".stripMargin
    ) { ps =>
      ps.setString(1, note.path.value)
      ps.setString(2, note.path.folder)
      ps.setString(3, note.path.name)
      ps.setString(4, note.contentHash.value)
      ps.setLong(5, note.sizeBytes)
      ps.setTimestamp(6, Timestamp.from(note.modifiedAt))
      note.frontmatter.map(fm => FmJson.renderFields(fm.fields)) match
        case Some(json) => ps.setString(7, json)
        case None => ps.setNull(7, java.sql.Types.OTHER)
      note.frontmatter.map(_.raw) match
        case Some(raw) => ps.setString(8, raw)
        case None => ps.setNull(8, java.sql.Types.VARCHAR)
      note.frontmatterError match
        case Some(err) => ps.setString(9, err)
        case None => ps.setNull(9, java.sql.Types.VARCHAR)
      ps.setString(10, note.body)
    }

  private def replaceTags(conn: Connection, note: Note): Unit =
    execUpdate(conn, "DELETE FROM note_tags WHERE path = ?")(_.setString(1, note.path.value))
    val ps = conn.prepareStatement(
      "INSERT INTO note_tags (path, tag, raw, source) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING"
    )
    try
      note.tags.foreach { nt =>
        ps.setString(1, note.path.value)
        ps.setString(2, nt.tag.normalized)
        ps.setString(3, nt.tag.raw)
        ps.setString(4, sourceLabel(nt.source))
        ps.addBatch()
      }
      ps.executeBatch()
      ()
    finally ps.close()

  private def replaceLinks(conn: Connection, note: Note): Unit =
    execUpdate(conn, "DELETE FROM note_links WHERE source_path = ?")(
      _.setString(1, note.path.value)
    )
    val ps = conn.prepareStatement(
      """INSERT INTO note_links (source_path, ordinal, raw_target, header, alias, embed, resolved_path)
        |VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      note.links.zipWithIndex.foreach { case (link, i) =>
        ps.setString(1, note.path.value)
        ps.setInt(2, i)
        ps.setString(3, link.rawTarget)
        link.header match
          case Some(h) => ps.setString(4, h)
          case None => ps.setNull(4, java.sql.Types.VARCHAR)
        link.alias match
          case Some(a) => ps.setString(5, a)
          case None => ps.setNull(5, java.sql.Types.VARCHAR)
        ps.setBoolean(6, link.embed)
        resolveTarget(conn, link.rawTarget) match
          case Some(p) => ps.setString(7, p)
          case None => ps.setNull(7, java.sql.Types.VARCHAR)
        ps.addBatch()
      }
      ps.executeBatch()
      ()
    finally ps.close()

  // --- link resolution ----------------------------------------------------------

  /**
   * Obsidian's resolution rules, index-relative: an exact vault-path match (as written, then with
   * `.md` appended) wins; otherwise a case-insensitive basename match that is UNIQUE resolves;
   * ambiguous or missing stays NULL.
   */
  private def resolveTarget(conn: Connection, rawTarget: String): Option[String] =
    val target = rawTarget.trim
    if target.isEmpty then None
    else
      exactPath(conn, target)
        .orElse(exactPath(conn, s"$target.md"))
        .orElse(uniqueByName(conn, target))

  private def exactPath(conn: Connection, candidate: String): Option[String] =
    val ps = conn.prepareStatement("SELECT path FROM notes WHERE path = ?")
    try
      ps.setString(1, candidate)
      val rs = ps.executeQuery()
      if rs.next() then Some(rs.getString(1)) else None
    finally ps.close()

  private def uniqueByName(conn: Connection, target: String): Option[String] =
    val ps = conn.prepareStatement("SELECT path FROM notes WHERE lower(name) = lower(?) LIMIT 2")
    try
      ps.setString(1, target)
      val rs = ps.executeQuery()
      if !rs.next() then None
      else
        val first = rs.getString(1)
        if rs.next() then None // ambiguous — refuse to guess
        else Some(first)
    finally ps.close()

  /**
   * Re-resolve every link whose raw target could refer to `path` — after that note is created
   * (links may now resolve, or become ambiguous) or deleted (links must unresolve or fall back).
   */
  private def reResolveLinksTargeting(conn: Connection, path: VaultPath): Unit =
    val ps = conn.prepareStatement(
      """SELECT DISTINCT source_path, ordinal, raw_target FROM note_links
        |WHERE lower(raw_target) = lower(?) OR raw_target = ? OR raw_target = ?""".stripMargin
    )
    val affected =
      try
        ps.setString(1, path.name)
        ps.setString(2, path.value)
        ps.setString(3, path.value.stripSuffix(".md"))
        val rs = ps.executeQuery()
        val rows = mutable.ArrayBuffer.empty[(String, Int, String)]
        while rs.next() do rows += ((rs.getString(1), rs.getInt(2), rs.getString(3)))
        rows.toVector
      finally ps.close()

    affected.foreach { case (sourcePath, ordinal, rawTarget) =>
      execUpdate(
        conn,
        "UPDATE note_links SET resolved_path = ? WHERE source_path = ? AND ordinal = ?"
      ) { up =>
        resolveTarget(conn, rawTarget) match
          case Some(p) => up.setString(1, p)
          case None => up.setNull(1, java.sql.Types.VARCHAR)
        up.setString(2, sourcePath)
        up.setInt(3, ordinal)
      }
    }

  // --- plumbing -----------------------------------------------------------------

  private def sourceLabel(s: TagSource): String =
    s match
      case TagSource.FrontmatterKey => "frontmatter"
      case TagSource.Inline => "inline"

  private def execUpdate(conn: Connection, sql: String)(
      bind: java.sql.PreparedStatement => Unit
  ): Unit =
    val ps = conn.prepareStatement(sql)
    try
      bind(ps)
      ps.executeUpdate()
      ()
    finally ps.close()

  private def withConnection[A](f: Connection => A): A =
    val conn = ds.getConnection
    try f(conn)
    finally conn.close()

  private def inTransaction[A](f: Connection => A): A =
    val conn = ds.getConnection
    try
      conn.setAutoCommit(false)
      try
        val out = f(conn)
        conn.commit()
        out
      catch
        case e: Throwable =>
          conn.rollback()
          throw e
    finally
      conn.setAutoCommit(true)
      conn.close()
