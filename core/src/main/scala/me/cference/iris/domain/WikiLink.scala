package me.cference.iris.domain

/**
 * One `[[wikilink]]` occurrence in a note body, as written.
 *
 * @param rawTarget
 *   the link target sans decorations — `Target` in `[[Target#Header|Alias]]`. May name a note
 *   (usually without `.md`), an attachment, or nothing that exists (unresolved links are data here,
 *   not errors).
 * @param header
 *   the `#Header` fragment, without the `#`
 * @param alias
 *   the `|Alias` display text
 * @param embed
 *   true for `![[...]]` embeds (images, transclusions)
 */
final case class WikiLink(
    rawTarget: String,
    header: Option[String],
    alias: Option[String],
    embed: Boolean
)
