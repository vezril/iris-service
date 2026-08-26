package me.cference.iris.domain

/**
 * A note's YAML frontmatter: the exact `raw` text between the fences (kept verbatim — phase 2's
 * round-trip guarantee starts with never re-rendering what we didn't change) plus the parsed
 * fields.
 *
 * Typed accessors exist only for the keys Iris itself reads; unknown keys ride along in `fields`
 * untouched.
 */
final case class Frontmatter(raw: String, fields: Map[String, FmValue]):

  /** The vault's `up` navigation link, as written (usually a quoted wikilink). */
  def up: Option[String] =
    fields.get("up").collect { case FmValue.Str(s) => s }

  /** Note aliases (`aliases:` — scalar or list). */
  def aliases: Vector[String] =
    fields.get("aliases").map(FmValue.strings).getOrElse(Vector.empty)

  /**
   * Frontmatter tags (`tags:` — scalar or list; a scalar may be comma-separated per Obsidian's
   * legacy form). Leading `#` is tolerated and stripped.
   */
  def tags: Vector[String] =
    fields
      .get("tags")
      .map(FmValue.strings)
      .getOrElse(Vector.empty)
      .flatMap(_.split(','))
      .map(_.trim.stripPrefix("#"))
      .filter(_.nonEmpty)
