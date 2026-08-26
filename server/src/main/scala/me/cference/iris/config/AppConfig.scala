package me.cference.iris.config

import com.typesafe.config.Config

import java.nio.file.Path
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

/** Typed view over the `iris.http` config block. */
final case class HttpConfig(host: String, port: Int)

/**
 * Where the vault mirror lives. In the cluster this is the PVC the obsidian-sync sidecar pulls
 * into, mounted read-only; in local dev it is Calvin's real vault (also opened read-only — phase 1
 * never writes, anywhere).
 */
final case class VaultConfig(root: Path)

/** The Postgres read model (rebuildable; the vault stays the source of truth). */
final case class DbConfig(
    jdbcUrl: String,
    user: String,
    password: String,
    migrateMaxWait: FiniteDuration
)

/** Watch/rescan cadence. */
final case class ScanConfig(
    watchEnabled: Boolean,
    debounce: FiniteDuration,
    rescanInterval: FiniteDuration
)

final case class AppConfig(http: HttpConfig, vault: VaultConfig, db: DbConfig, scan: ScanConfig)

object AppConfig:

  /** Read + type the operational config. Fails fast (Typesafe Config throws) on a missing key. */
  def load(raw: Config): AppConfig =
    val http = raw.getConfig("iris.http")
    val vault = raw.getConfig("iris.vault")
    val db = raw.getConfig("iris.db")
    val scan = raw.getConfig("iris.scan")
    AppConfig(
      HttpConfig(http.getString("host"), http.getInt("port")),
      VaultConfig(Path.of(vault.getString("root"))),
      DbConfig(
        jdbcUrl =
          s"jdbc:postgresql://${db.getString("host")}:${db.getInt("port")}/${db.getString("database")}",
        user = db.getString("user"),
        password = db.getString("password"),
        migrateMaxWait = db.getDuration("migrate-max-wait").toMillis.millis
      ),
      ScanConfig(
        watchEnabled = scan.getBoolean("watch-enabled"),
        debounce = scan.getDuration("debounce").toMillis.millis,
        rescanInterval = scan.getDuration("rescan-interval").toMillis.millis
      )
    )
