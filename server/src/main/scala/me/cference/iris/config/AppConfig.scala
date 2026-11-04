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

/**
 * How long boot waits for the vault mirror to stop growing before the initial scan. Guards the
 * first-boot watcher race (see [[me.cference.iris.scan.VaultQuiesce]]).
 */
final case class QuiesceConfig(
    enabled: Boolean,
    pollInterval: FiniteDuration,
    stableSamples: Int,
    maxWait: FiniteDuration
)

/** Watch/rescan cadence. */
final case class ScanConfig(
    watchEnabled: Boolean,
    debounce: FiniteDuration,
    rescanInterval: FiniteDuration,
    quiesce: QuiesceConfig
)

/**
 * The phase-2 write surface. `enabled` defaults to FALSE and gates whether the PATCH route is
 * MOUNTED AT ALL — a route that does not exist is a stronger guarantee than one that checks a flag.
 *
 * @param tokens
 *   service -> write token, from a k8s Secret via `IRIS_WRITE_TOKENS` (`Svc=tok,Svc2=tok2`). Never
 *   in config files. An empty map authenticates nobody, so every write refuses.
 * @param apolloBucket
 *   where pre-write snapshots land. Must be lowercase (Apollo's BucketName rule).
 */
final case class WriteConfig(
    enabled: Boolean,
    tokens: Map[String, String],
    apolloHost: String,
    apolloPort: Int,
    apolloBucket: String,
    apolloTimeout: FiniteDuration
)

/** The change-feed switch (real publishing waits on the lexicon contract + topics). */
final case class HermesConfig(enabled: Boolean)

final case class AppConfig(
    http: HttpConfig,
    vault: VaultConfig,
    db: DbConfig,
    scan: ScanConfig,
    hermes: HermesConfig,
    write: WriteConfig
)

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
        rescanInterval = scan.getDuration("rescan-interval").toMillis.millis,
        quiesce = QuiesceConfig(
          enabled = scan.getBoolean("quiesce.enabled"),
          pollInterval = scan.getDuration("quiesce.poll-interval").toMillis.millis,
          stableSamples = scan.getInt("quiesce.stable-samples"),
          maxWait = scan.getDuration("quiesce.max-wait").toMillis.millis
        )
      ),
      HermesConfig(enabled = raw.getBoolean("iris.hermes.enabled")), {
        val w = raw.getConfig("iris.write")
        WriteConfig(
          enabled = w.getBoolean("enabled"),
          tokens = parseTokens(w.getString("tokens")),
          apolloHost = w.getString("apollo.host"),
          apolloPort = w.getInt("apollo.port"),
          apolloBucket = w.getString("apollo.bucket"),
          apolloTimeout = w.getDuration("apollo.timeout").toMillis.millis
        )
      }
    )

  /**
   * Parse `Svc=token,Svc2=token2`.
   *
   * Entries that do not split cleanly on a single `=` are DROPPED rather than guessed at, and a
   * dropped entry means that service cannot write — the fail-closed direction. A malformed map
   * never widens access; at worst it narrows it to nothing.
   */
  private[config] def parseTokens(raw: String): Map[String, String] =
    raw
      .split(',')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { entry =>
        entry.split('=') match
          case Array(service, token) if service.trim.nonEmpty && token.trim.nonEmpty =>
            Some(service.trim -> token.trim)
          case _ => None
      }
      .toMap
