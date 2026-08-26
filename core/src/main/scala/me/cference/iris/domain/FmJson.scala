package me.cference.iris.domain

/**
 * Canonical JSON rendering of frontmatter values — the shape stored in the index's JSONB column and
 * served by the API. Hand-rolled (and tiny) so `core` stays dependency-free.
 */
object FmJson:

  def render(value: FmValue): String =
    value match
      case FmValue.Null => "null"
      case FmValue.Bool(b) => b.toString
      case FmValue.Num(n) => n.bigDecimal.toPlainString
      case FmValue.Str(s) => quote(s)
      case FmValue.Arr(vs) => vs.map(render).mkString("[", ",", "]")
      case FmValue.Obj(fields) =>
        fields.map { case (k, v) => s"${quote(k)}:${render(v)}" }.mkString("{", ",", "}")

  def renderFields(fields: Map[String, FmValue]): String =
    render(FmValue.Obj(fields))

  private def quote(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    s.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\b' => sb.append("\\b")
      case '\f' => sb.append("\\f")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"')
    sb.toString
