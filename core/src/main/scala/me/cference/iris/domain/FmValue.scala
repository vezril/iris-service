package me.cference.iris.domain

/**
 * A parsed YAML frontmatter value, structurally. Iris types only what it reads; everything else is
 * carried opaquely so nothing Calvin put in a note is flattened or lost on its way to the index.
 */
enum FmValue:
  case Str(value: String)
  case Num(value: BigDecimal)
  case Bool(value: Boolean)
  case Null
  case Arr(values: Vector[FmValue])
  case Obj(fields: Map[String, FmValue])

object FmValue:

  /** The strings of a value that is either a scalar or a list of scalars (a common vault shape). */
  def strings(value: FmValue): Vector[String] =
    value match
      case Str(s) => Vector(s)
      case Arr(vs) => vs.collect { case Str(s) => s }
      case _ => Vector.empty
