package me.cference.iris.domain

import java.time.Instant

/** A tag attached to a note, with its provenance. */
final case class NoteTag(tag: Tag, source: TagSource)

/**
 * A fully parsed vault note — the unit the index stores, the API serves, and events describe.
 *
 * A note with broken frontmatter is still a `Note` (with `frontmatterError` set): a YAML typo in
 * one file must never make that note invisible to the bridge.
 */
final case class Note(
    path: VaultPath,
    frontmatter: Option[Frontmatter],
    frontmatterError: Option[String],
    body: String,
    tags: Set[NoteTag],
    links: Vector[WikiLink],
    contentHash: ContentHash,
    sizeBytes: Long,
    modifiedAt: Instant
)
