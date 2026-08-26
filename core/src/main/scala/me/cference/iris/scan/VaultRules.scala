package me.cference.iris.scan

import me.cference.iris.domain.VaultPath

/**
 * The gate every path passes before Iris will even OPEN the file. These rules are the first line of
 * the never-harm principle: what the bridge does not read, it cannot leak or mangle.
 */
object VaultRules:

  /**
   * Extensions that must never be read, even if a future rule change widens indexing. `.mdenc`
   * files are encrypted secrets (recovery codes) — their ciphertext has no business in the index,
   * and their plaintext even less.
   */
  private val ForbiddenExtensions = Set("mdenc")

  /** Only markdown notes are indexed in phase 1. */
  private val IndexedExtensions = Set("md")

  /** True when the path should be parsed and indexed. */
  def shouldIndex(path: VaultPath): Boolean =
    !isExcluded(path) && IndexedExtensions.contains(path.extension)

  /**
   * True when the path must not be opened at all: anything under a dot-directory (`.obsidian`,
   * `.smart-env`, `.trash`), any dot-file, and the forbidden extensions — checked independently of
   * the indexing rule so `.mdenc` stays excluded under any future rule change.
   */
  def isExcluded(path: VaultPath): Boolean =
    path.value.split('/').exists(_.startsWith(".")) ||
      ForbiddenExtensions.contains(path.extension)
