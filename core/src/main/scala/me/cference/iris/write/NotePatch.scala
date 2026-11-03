package me.cference.iris.write

/** One edit a service may make to a note. Nothing here can rewrite a note wholesale. */
enum NoteEdit:

  /**
   * Set a top-level frontmatter key, adding it if absent. The value is always emitted as a
   * double-quoted YAML scalar (see [[NotePatch.encodeScalar]]) — not for looks, but because an
   * unquoted value containing a newline or `: ` would inject sibling keys into Calvin's
   * frontmatter.
   */
  case SetFrontmatterKey(key: String, value: String)

  /** Remove a top-level frontmatter key. Absent is success — the end state is what was asked. */
  case RemoveFrontmatterKey(key: String)

  /**
   * Replace the content between this service's managed markers in the body, appending the block if
   * absent. HTML comments, so the markers are invisible in Obsidian's rendered view.
   */
  case ReplaceManagedSection(service: String, content: String)

/**
 * Why a patch was refused. Every case leaves the note byte-identical: when the patcher is not
 * certain what a note means, it does not edit it.
 */
enum PatchError(val message: String):

  /** The frontmatter did not parse. Iris will not edit a note it cannot read. */
  case UnreadableFrontmatter(detail: String)
      extends PatchError(s"refusing to patch: frontmatter did not parse ($detail)")

  /** The same top-level key twice: which one to edit is a guess, so refuse. */
  case AmbiguousKey(key: String)
      extends PatchError(s"refusing to patch: '$key' appears more than once at the top level")

  case IllegalKey(key: String) extends PatchError(s"illegal frontmatter key: '$key'")

  /** Content carrying a marker would break the section's boundaries. */
  case MarkerInContent(service: String)
      extends PatchError(s"content for '$service' contains an iris managed-section marker")

  /** A begin without an end (or vice versa): the note's existing block is malformed. */
  case MalformedManagedSection(service: String, detail: String)
      extends PatchError(s"managed section for '$service' is malformed: $detail")

  case IllegalServiceMarker(service: String)
      extends PatchError(s"illegal service name in marker: '$service'")

/**
 * Applies edits to a note's **text**, preserving every byte the edit does not name.
 *
 * This is where the governing rule lives: *the vault is Calvin's source of truth and a write must
 * never corrupt or silently overwrite a note.* So the patcher is textual, never a re-render.
 * `FrontmatterParser` keeps the raw block verbatim for exactly this reason — round-tripping through
 * SnakeYAML would reorder keys, strip comments, normalize quoting and reflow lists, every one of
 * which is corruption of prose Calvin wrote even though the YAML would still "mean" the same thing.
 *
 * Guarantees, each pinned by a spec:
 *   - unnamed frontmatter keys, their comments, their order and their quoting survive byte-for-byte
 *   - body text outside the managed section survives byte-for-byte
 *   - line terminators survive, including a mixed or CRLF file
 *   - applying the same edits twice is a no-op on the second pass
 *   - anything ambiguous is refused, not guessed
 */
object NotePatch:

  private val OpenFence = "---"
  private val CloseFences = Set("---", "...")
  private val LegalKey = "^[A-Za-z0-9][A-Za-z0-9_-]*$".r
  private val LegalService = "^[A-Za-z0-9][A-Za-z0-9_-]*$".r

  def beginMarker(service: String): String = s"<!-- iris:begin $service -->"
  def endMarker(service: String): String = s"<!-- iris:end $service -->"

  /** A line plus the terminator it actually carried, so joining cannot normalize CRLF to LF. */
  final private case class Line(text: String, terminator: String):
    def render: String = text + terminator

  private def splitLines(s: String): Vector[Line] =
    val out = Vector.newBuilder[Line]
    var start = 0
    var i = 0
    while i < s.length do
      if s.charAt(i) == '\n' then
        val isCrLf = i > start && s.charAt(i - 1) == '\r'
        val end = if isCrLf then i - 1 else i
        out += Line(s.substring(start, end), if isCrLf then "\r\n" else "\n")
        start = i + 1
      i += 1
    // Trailing content with no terminator (a file not ending in a newline). When `start` has
    // reached the end, the text ended ON a terminator and there is no final line to add.
    if start < s.length || s.isEmpty then out += Line(s.substring(start), "")
    out.result()

  private def join(lines: Vector[Line]): String =
    val sb = new StringBuilder
    lines.foreach(l => sb.append(l.render))
    sb.toString

  /** The terminator to use for lines this patcher introduces: whatever the note already uses. */
  private def dominantTerminator(lines: Vector[Line]): String =
    lines.map(_.terminator).find(_.nonEmpty).getOrElse("\n")

  /**
   * Apply every edit in order.
   *
   * @param frontmatterError
   *   pass `Some(detail)` when the note's frontmatter failed to parse; any frontmatter edit is then
   *   refused. Managed-section edits touch only the body and stay allowed.
   */
  def apply(
      original: String,
      edits: Seq[NoteEdit],
      frontmatterError: Option[String] = None
  ): Either[PatchError, String] =
    edits.foldLeft[Either[PatchError, String]](Right(original)) { (acc, edit) =>
      acc.flatMap(text => applyOne(text, edit, frontmatterError))
    }

  private def applyOne(
      text: String,
      edit: NoteEdit,
      frontmatterError: Option[String]
  ): Either[PatchError, String] =
    edit match
      case NoteEdit.SetFrontmatterKey(key, value) =>
        frontmatterError
          .map(d => Left(PatchError.UnreadableFrontmatter(d)))
          .getOrElse(setKey(text, key, value))
      case NoteEdit.RemoveFrontmatterKey(key) =>
        frontmatterError
          .map(d => Left(PatchError.UnreadableFrontmatter(d)))
          .getOrElse(removeKey(text, key))
      case NoteEdit.ReplaceManagedSection(service, content) =>
        replaceSection(text, service, content)

  // --- frontmatter ----------------------------------------------------------------

  /** Index of the closing fence, when the note opens with frontmatter. */
  private def frontmatterRange(lines: Vector[Line]): Option[(Int, Int)] =
    if lines.isEmpty || lines(0).text != OpenFence then None
    else
      lines.indexWhere(l => CloseFences.contains(l.text), 1) match
        case -1 => None // unclosed fence: not frontmatter, per Obsidian's rule
        case close => Some((1, close))

  /** Does this line start a new top-level key? Used to bound a key's value span. */
  private def startsTopLevelKey(text: String): Boolean =
    text.nonEmpty && !text.startsWith(" ") && !text.startsWith("\t") &&
      !text.startsWith("-") && !text.startsWith("#") && text.contains(':')

  private def keyAt(text: String, key: String): Boolean =
    startsTopLevelKey(text) && text.substring(0, text.indexOf(':')).trim == key

  /**
   * The lines belonging to `key`: its own line plus continuations. A continuation is anything that
   * is not a new top-level key, a top-level comment, or a fence — which keeps an indented block, a
   * nested mapping and a `- ` list (at any indent) with their key.
   *
   * A top-level `#` comment ENDS the span, so a comment following a value is preserved rather than
   * swallowed into the replacement.
   */
  private def valueSpan(lines: Vector[Line], keyIdx: Int, fmEnd: Int): Int =
    var i = keyIdx + 1
    while i < fmEnd && !startsTopLevelKey(lines(i).text) && !lines(i).text.startsWith("#") do i += 1
    i // exclusive

  private def setKey(text: String, key: String, value: String): Either[PatchError, String] =
    if !LegalKey.matches(key) then Left(PatchError.IllegalKey(key))
    else
      val lines = splitLines(text)
      val term = dominantTerminator(lines)
      val newLine = Line(s"$key: ${encodeScalar(value)}", term)

      frontmatterRange(lines) match
        case None =>
          // No frontmatter: create the block above the existing body, which is left untouched.
          Right(
            join(
              Vector(Line(OpenFence, term), newLine, Line(OpenFence, term)) ++ lines
            )
          )
        case Some((fmStart, fmEnd)) =>
          val matches = (fmStart until fmEnd).filter(i => keyAt(lines(i).text, key))
          if matches.sizeIs > 1 then Left(PatchError.AmbiguousKey(key))
          else if matches.isEmpty then
            // Append as the last frontmatter line; every existing line keeps its position.
            Right(join(lines.patch(fmEnd, Vector(newLine), 0)))
          else
            val keyIdx = matches.head
            val spanEnd = valueSpan(lines, keyIdx, fmEnd)
            // Preserve the ORIGINAL line's terminator, not the dominant one, in a mixed file.
            val replacement = Vector(newLine.copy(terminator = lines(keyIdx).terminator))
            Right(join(lines.patch(keyIdx, replacement, spanEnd - keyIdx)))

  private def removeKey(text: String, key: String): Either[PatchError, String] =
    if !LegalKey.matches(key) then Left(PatchError.IllegalKey(key))
    else
      val lines = splitLines(text)
      frontmatterRange(lines) match
        case None => Right(text) // nothing to remove; the note is already in the end state
        case Some((fmStart, fmEnd)) =>
          val matches = (fmStart until fmEnd).filter(i => keyAt(lines(i).text, key))
          if matches.sizeIs > 1 then Left(PatchError.AmbiguousKey(key))
          else if matches.isEmpty then Right(text)
          else
            val keyIdx = matches.head
            val spanEnd = valueSpan(lines, keyIdx, fmEnd)
            Right(join(lines.patch(keyIdx, Vector.empty, spanEnd - keyIdx)))

  /**
   * Encode a value as a double-quoted YAML scalar.
   *
   * Always quoted, deliberately. An unquoted value containing a newline, a `: `, or a leading `-`
   * would be parsed as structure rather than as text — a service could set one key and inject
   * siblings into Calvin's frontmatter. Quoting one of Iris's own keys costs nothing; the
   * alternative is an injection surface on the file the whole design exists to protect.
   */
  private[write] def encodeScalar(value: String): String =
    val sb = new StringBuilder("\"")
    value.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c => sb.append(c)
    }
    sb.append('"').toString

  // --- managed section ------------------------------------------------------------

  private def replaceSection(
      text: String,
      service: String,
      content: String
  ): Either[PatchError, String] =
    if !LegalService.matches(service) then Left(PatchError.IllegalServiceMarker(service))
    else
      val begin = beginMarker(service)
      val end = endMarker(service)
      if content.contains("<!-- iris:begin ") || content.contains("<!-- iris:end ") then
        Left(PatchError.MarkerInContent(service))
      else
        val lines = splitLines(text)
        val term = dominantTerminator(lines)
        val begins = lines.indices.filter(i => lines(i).text.trim == begin)
        val ends = lines.indices.filter(i => lines(i).text.trim == end)

        val contentLines =
          if content.isEmpty then Vector.empty
          else splitLines(content).map(l => l.copy(terminator = term))

        (begins.size, ends.size) match
          case (0, 0) =>
            // Append the block at the end, after ensuring the body ends with a terminator so the
            // marker cannot land on the same line as Calvin's last sentence.
            val padded =
              if lines.isEmpty then lines
              else if lines.last.terminator.isEmpty && lines.last.text.nonEmpty then
                lines.init :+ lines.last.copy(terminator = term)
              else lines
            Right(
              join(
                padded ++ (Line(begin, term) +: contentLines :+ Line(end, term))
              )
            )
          case (1, 1) if begins.head < ends.head =>
            Right(
              join(
                lines.patch(begins.head + 1, contentLines, ends.head - begins.head - 1)
              )
            )
          case (1, 1) =>
            Left(PatchError.MalformedManagedSection(service, "end marker precedes begin marker"))
          case (b, e) =>
            Left(
              PatchError.MalformedManagedSection(
                service,
                s"expected one begin and one end marker, found $b and $e"
              )
            )
