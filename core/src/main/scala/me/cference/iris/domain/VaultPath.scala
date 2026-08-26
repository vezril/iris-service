package me.cference.iris.domain

import java.text.Normalizer

/** Why a raw string was rejected as a vault path. */
enum PathError(val message: String):
  case Empty extends PathError("path is empty")
  case Absolute extends PathError("path must be vault-relative (no leading '/')")
  case Traversal extends PathError("path must not contain '..' segments")
  case Backslash extends PathError("path must use '/' separators (no '\\')")
  case EmptySegment extends PathError("path must not contain empty segments ('//')")

/**
 * A validated, vault-relative note path — the canonical identity of a note everywhere in Iris (the
 * Postgres index, the REST API, Hermes events, and phase 2's optimistic lock all key on it).
 *
 * Canonical form: forward slashes, no leading/trailing slash, and **Unicode NFC**. The vault lives
 * on macOS (which hands out NFD file names) and is mirrored onto a Linux PVC (which stores whatever
 * bytes Sync writes); comparing paths under one normalization is what keeps wikilink resolution
 * from silently missing notes whose names carry accents or apostrophes.
 */
final case class VaultPath private (value: String):

  /** The containing folder, vault-relative ("" for a note at the vault root). */
  def folder: String =
    value.lastIndexOf('/') match
      case -1 => ""
      case i => value.substring(0, i)

  /** File name without its folder, extension included. */
  def fileName: String =
    value.substring(value.lastIndexOf('/') + 1)

  /** Note name: the file name without its final extension ("Home" for "Home.md"). */
  def name: String =
    val f = fileName
    f.lastIndexOf('.') match
      case -1 | 0 => f
      case i => f.substring(0, i)

  /** Lowercased final extension without the dot ("md"), or "" when there is none. */
  def extension: String =
    val f = fileName
    f.lastIndexOf('.') match
      case -1 | 0 => ""
      case i => f.substring(i + 1).toLowerCase

  override def toString: String = value

object VaultPath:

  /**
   * Parse and canonicalize a raw path (as read from the filesystem walk or a URL). Trailing slashes
   * are tolerated and stripped; everything else that deviates from canonical form is an error
   * rather than a silent fix — a path that needs repair is a bug upstream.
   */
  def parse(raw: String): Either[PathError, VaultPath] =
    val trimmed = raw.stripSuffix("/")
    if trimmed.isEmpty then Left(PathError.Empty)
    else if trimmed.contains('\\') then Left(PathError.Backslash)
    else if trimmed.startsWith("/") then Left(PathError.Absolute)
    else
      val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
      val segments = normalized.split('/')
      if segments.exists(_.isEmpty) then Left(PathError.EmptySegment)
      else if segments.exists(_ == "..") then Left(PathError.Traversal)
      else Right(VaultPath(normalized))
