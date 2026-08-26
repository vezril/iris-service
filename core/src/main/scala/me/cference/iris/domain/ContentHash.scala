package me.cference.iris.domain

import java.security.MessageDigest

/**
 * SHA-256 of a note's **raw file bytes** (not the decoded string), as 64 lowercase hex chars.
 *
 * This is the value phase 2's optimistic lock will compare — a write that presents a stale hash is
 * refused — so it is defined once, here, and computed nowhere else. Hashing bytes rather than text
 * means an encoding-level change (BOM, line endings) is honestly a change.
 */
final case class ContentHash private (value: String):
  override def toString: String = value

object ContentHash:

  private val HexLength = 64
  private val HexChars = "0123456789abcdef".toSet

  /** Compute the hash of raw note bytes. */
  def ofBytes(bytes: Array[Byte]): ContentHash =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = new StringBuilder(HexLength)
    digest.foreach(b => sb.append(f"${b & 0xff}%02x"))
    ContentHash(sb.toString)

  /** Re-validate a hash arriving from outside (the database, an API caller). */
  def parse(raw: String): Either[String, ContentHash] =
    if raw.length != HexLength then Left(s"content hash must be $HexLength hex chars")
    else if !raw.forall(HexChars) then Left("content hash must be lowercase hex")
    else Right(ContentHash(raw))
