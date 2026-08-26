package me.cference.iris.parse

import me.cference.iris.domain.{FmValue, Frontmatter}
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Splits a note into frontmatter and body, and parses the frontmatter YAML.
 *
 * YAML is loaded through `SafeConstructor`, which restricts it to plain scalars, sequences and
 * maps. SnakeYAML's default constructor instantiates arbitrary JVM classes named by YAML tags —
 * remote code execution in a service that parses every note in the vault.
 *
 * Malformed YAML never drops a note: the split still happens, the raw text is preserved, and the
 * error travels with the note instead of hiding it from the bridge.
 */
object FrontmatterParser:

  /** The outcome of splitting one note's text. */
  final case class Split(
      frontmatter: Option[Frontmatter],
      frontmatterError: Option[String],
      body: String
  )

  private val OpenFence = "---"
  private val CloseFences = Set("---", "...")

  /**
   * Obsidian's rule: frontmatter exists iff the **first line** is exactly `---`, closed by a later
   * `---` (or YAML's `...`). Anything else is body from byte zero.
   */
  def split(text: String): Split =
    val lines = text.split("\n", -1)
    if lines.isEmpty || lines(0).stripLineEnd != OpenFence then Split(None, None, text)
    else
      lines.indexWhere(l => CloseFences.contains(l.stripLineEnd), 1) match
        case -1 => Split(None, None, text) // an unclosed fence is not frontmatter
        case close =>
          val raw = lines.slice(1, close).mkString("\n")
          val body = lines.drop(close + 1).mkString("\n")
          parseYaml(raw) match
            case Right(fields) => Split(Some(Frontmatter(raw, fields)), None, body)
            case Left(error) => Split(None, Some(error), body)

  private def parseYaml(raw: String): Either[String, Map[String, FmValue]] =
    try
      val yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
      Option(yaml.load[Any](raw)) match
        case None => Right(Map.empty) // empty frontmatter block is legal
        case Some(m: java.util.Map[?, ?]) =>
          Right(m.asScala.toMap.map { case (k, v) => String.valueOf(k) -> convert(v) })
        case Some(_) => Left("frontmatter is not a YAML mapping")
    catch case NonFatal(e) => Left(s"unreadable YAML (${e.getMessage})")

  /** Structural conversion of SafeConstructor output. Anything scalar-ish becomes its string. */
  private def convert(value: Any): FmValue =
    value match
      case null => FmValue.Null
      case s: String => FmValue.Str(s)
      case b: java.lang.Boolean => FmValue.Bool(b)
      case n: java.lang.Integer => FmValue.Num(BigDecimal(n.intValue))
      case n: java.lang.Long => FmValue.Num(BigDecimal(n.longValue))
      case n: java.math.BigInteger => FmValue.Num(BigDecimal(n))
      case n: java.lang.Double => FmValue.Num(BigDecimal(n.doubleValue))
      case d: java.util.Date => FmValue.Str(d.toInstant.toString)
      case l: java.util.List[?] => FmValue.Arr(l.asScala.toVector.map(convert))
      case m: java.util.Map[?, ?] =>
        FmValue.Obj(m.asScala.toMap.map { case (k, v) => String.valueOf(k) -> convert(v) })
      case other => FmValue.Str(String.valueOf(other))
