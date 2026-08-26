package me.cference.iris.config

import com.typesafe.config.Config

import java.nio.file.Path

/** Typed view over the `iris.http` config block. */
final case class HttpConfig(host: String, port: Int)

/**
 * Where the vault mirror lives. In the cluster this is the PVC the obsidian-sync sidecar pulls
 * into, mounted read-only; in local dev it is Calvin's real vault (also opened read-only — phase 1
 * never writes, anywhere).
 */
final case class VaultConfig(root: Path)

final case class AppConfig(http: HttpConfig, vault: VaultConfig)

object AppConfig:

  /** Read + type the operational config. Fails fast (Typesafe Config throws) on a missing key. */
  def load(raw: Config): AppConfig =
    val http = raw.getConfig("iris.http")
    val vault = raw.getConfig("iris.vault")
    AppConfig(
      HttpConfig(http.getString("host"), http.getInt("port")),
      VaultConfig(Path.of(vault.getString("root")))
    )
