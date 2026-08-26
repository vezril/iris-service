package me.cference.iris.parse

import me.cference.iris.domain.*

import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Assembles a full [[Note]] from raw file bytes. Pure: all I/O (reading the file, stat-ing mtime)
 * happens in the server's scanner; this function cannot fail — a note that parses badly is still a
 * note, with its trouble recorded on it.
 */
object NoteParser:

  def parse(path: VaultPath, bytes: Array[Byte], modifiedAt: Instant): Note =
    val hash = ContentHash.ofBytes(bytes)
    // Invalid UTF-8 sequences decode to U+FFFD rather than failing: the hash is over the true
    // bytes, so nothing is lost, and the note stays visible.
    val text = new String(bytes, StandardCharsets.UTF_8)
    val split = FrontmatterParser.split(text)
    val scanned = InlineScanner.scan(split.body)

    val fmTags = split.frontmatter
      .map(_.tags)
      .getOrElse(Vector.empty)
      .map(t => NoteTag(Tag(t), TagSource.FrontmatterKey))
    val inlineTags = scanned.tags.map(t => NoteTag(t, TagSource.Inline))

    Note(
      path = path,
      frontmatter = split.frontmatter,
      frontmatterError = split.frontmatterError,
      body = split.body,
      tags = (fmTags ++ inlineTags).toSet,
      links = scanned.links,
      contentHash = hash,
      sizeBytes = bytes.length.toLong,
      modifiedAt = modifiedAt
    )
