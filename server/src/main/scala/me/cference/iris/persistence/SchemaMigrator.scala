package me.cference.iris.persistence

import me.cference.iris.config.DbConfig

import java.sql.Connection
import java.sql.DriverManager
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * A boot-time migration failure: the database stayed unreachable, or applying the schema raised a
 * database error.
 */
final case class MigrationError(message: String)

/**
 * Applies the bundled PostgreSQL schema (`schema/postgres.sql`) at startup.
 *
 * The script is idempotent (every `CREATE` uses `IF NOT EXISTS`) and is applied in a single
 * `Statement.execute` — PostgreSQL runs a multi-statement, `;`-separated DDL string atomically, so
 * a partial failure rolls back and a re-apply over an existing schema is a harmless no-op.
 */
object SchemaMigrator:

  private val SchemaResource = "/schema/postgres.sql"
  private val RetryInterval = 1.second

  /** The bundled schema DDL, read from the classpath (the exact file in the jar). */
  def schemaDdl: String =
    val stream = Option(getClass.getResourceAsStream(SchemaResource))
      .getOrElse(sys.error(s"schema resource '$SchemaResource' not found on the classpath"))
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /**
   * Apply the schema against the configured database, waiting up to `dbConfig.migrateMaxWait` for
   * the database to become reachable. Returns `Left` if the database never becomes reachable in the
   * window or the apply fails; `Right(())` once the schema is present.
   *
   * @param sleep
   *   how to pause between connection attempts (injectable for tests)
   */
  def migrate(
      dbConfig: DbConfig,
      sleep: FiniteDuration => Unit = d => Thread.sleep(d.toMillis)
  ): Either[MigrationError, Unit] =
    connectWithRetry(dbConfig, sleep).flatMap { conn =>
      try applyScript(conn)
      finally conn.close()
    }

  private def connectWithRetry(
      dbConfig: DbConfig,
      sleep: FiniteDuration => Unit
  ): Either[MigrationError, Connection] =
    val deadline = System.nanoTime() + dbConfig.migrateMaxWait.toNanos

    @annotation.tailrec
    def attempt(): Either[MigrationError, Connection] =
      val tried =
        try Right(DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password))
        catch case NonFatal(e) => Left(e)
      tried match
        case Right(conn) => Right(conn)
        case Left(e) =>
          if System.nanoTime() >= deadline then
            Left(
              MigrationError(
                s"database at ${dbConfig.jdbcUrl} unreachable after ${dbConfig.migrateMaxWait}: ${e.getMessage}"
              )
            )
          else
            sleep(RetryInterval)
            attempt()

    attempt()

  private def applyScript(conn: Connection): Either[MigrationError, Unit] =
    val stmt = conn.createStatement()
    try
      stmt.execute(schemaDdl)
      Right(())
    catch case NonFatal(e) => Left(MigrationError(s"applying the schema failed: ${e.getMessage}"))
    finally stmt.close()
