package me.cference.iris.parse

import me.cference.iris.domain.{Tag, WikiLink}

/**
 * Extracts `[[wikilinks]]` and inline `#tags` from a note body.
 *
 * Deliberately not a markdown AST: phase 1 needs exactly these two token kinds, and a line scanner
 * that knows about code fences and inline code spans (where links and tags must NOT match) covers
 * the vault honestly at a fraction of a full parser's weight. Revisit only if a consumer needs
 * structural markdown (headings, blocks).
 */
object InlineScanner:

  final case class Scanned(links: Vector[WikiLink], tags: Vector[Tag])

  // [[target]], [[target#header]], [[target|alias]], [[target#header|alias]], ![[embed]]
  private val LinkRe =
    """(!?)\[\[([^\]\[|#]+)(?:#([^\]\[|]*))?(?:\|([^\]\[]*))?\]\]""".r

  // Obsidian tags: letters/digits/_/-//, at least one non-digit; preceded by start or whitespace.
  private val TagRe =
    """(?<=^|[\s(])#([\p{L}\p{N}_/-]*[\p{L}_/-][\p{L}\p{N}_/-]*)""".r

  // A fence line opens/closes a fenced code block (leading indent tolerated).
  private def isFence(line: String): Boolean =
    val t = line.trim
    t.startsWith("```") || t.startsWith("~~~")

  def scan(body: String): Scanned =
    val links = Vector.newBuilder[WikiLink]
    val tags = Vector.newBuilder[Tag]
    var inFence = false

    body.linesIterator.foreach { line =>
      if isFence(line) then inFence = !inFence
      else if !inFence then
        // Blank inline code spans first so their contents can't match.
        val noCode = blankInlineCode(line)
        // Extract links, then blank them so [[Note#Header]] can't also match as a #tag.
        val afterLinks = LinkRe.replaceAllIn(
          noCode,
          m =>
            links += WikiLink(
              rawTarget = m.group(2).trim,
              header = Option(m.group(3)).map(_.trim).filter(_.nonEmpty),
              alias = Option(m.group(4)).map(_.trim).filter(_.nonEmpty),
              embed = m.group(1).nonEmpty
            )
            " " * (m.end - m.start)
        )
        TagRe.findAllMatchIn(afterLinks).foreach(m => tags += Tag(m.group(1)))
    }
    Scanned(links.result(), tags.result())

  /** Replace `inline code` spans with spaces, preserving offsets. Unclosed backticks stay text. */
  private def blankInlineCode(line: String): String =
    val sb = new StringBuilder(line)
    var i = 0
    while i < sb.length do
      if sb.charAt(i) == '`' then
        val close = sb.indexOf("`", i + 1)
        if close > i then
          (i to close).foreach(j => sb.setCharAt(j, ' '))
          i = close + 1
        else i = sb.length
      else i += 1
    sb.toString
