package me.cference.iris.domain

/**
 * A vault tag, as written (`raw`, without the `#`). Obsidian matches tags case-insensitively, so
 * lookups and storage use `normalized`; the raw casing is kept because it is Calvin's.
 */
final case class Tag(raw: String):
  def normalized: String = raw.toLowerCase

/** Where a tag on a note came from. */
enum TagSource:
  case FrontmatterKey
  case Inline
