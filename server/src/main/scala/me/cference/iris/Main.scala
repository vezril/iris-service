package me.cference.iris

import me.cference.iris.build.BuildInfo
import me.cference.iris.config.AppConfig
import me.cference.iris.http.{HealthRoutes, HttpServer, NoteRoutes, RequestTracing}
import me.cference.iris.persistence.{Db, NoteRepository, SchemaMigrator}
import me.cference.iris.scan.{IndexReconciler, VaultQuiesce, VaultWatcher}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * Entry point. Loads configuration, binds the HTTP surface, and wires Pekko Coordinated Shutdown
 * (withdraw readiness -> unbind -> drain -> terminate). A bind failure (e.g. an occupied port) logs
 * clearly and exits non-zero.
 *
 * Phase 1 is read-only by construction: nothing in this process ever opens a vault file for
 * writing. In the cluster the vault mount is additionally read-only at the pod level.
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val raw = ConfigFactory.load()
    val cfg = AppConfig.load(raw)

    // Schema first, fail-fast: a service that cannot reach its read model has nothing to serve.
    SchemaMigrator.migrate(cfg.db) match
      case Left(err) =>
        log.error("boot aborted: {}", err.message)
        System.exit(1)
      case Right(()) => log.info("schema present at {}", cfg.db.jdbcUrl)

    given system: ActorSystem[Nothing] =
      ActorSystem[Nothing](Behaviors.empty[Nothing], "iris", raw)
    import system.executionContext

    // A missing vault root is a misconfiguration worth failing loudly over later (the scanner
    // needs it), but at boot we only warn: the sidecar may still be running its first sync.
    if !Files.isDirectory(cfg.vault.root) then
      log.warn("vault root {} does not exist (yet) — waiting on the sync sidecar?", cfg.vault.root)

    val pool = Db.pool(cfg.db)
    val repo = NoteRepository(pool)
    // Until the lexicon contract + topics exist, "enabled" logs the wire shape instead of sending.
    val publisher =
      if cfg.hermes.enabled then hermes.VaultEventPublisher.Logging()
      else hermes.VaultEventPublisher.NoOp
    val reconciler = IndexReconciler(cfg.vault.root, repo, publisher)

    // Every blocking vault operation — the quiesce wait, the initial scan, rescans, on-demand
    // reindexes — runs HERE, never on the default dispatcher the HTTP server shares. The quiesce
    // gate alone can park a thread for 15 minutes; parking one of the default dispatcher's few
    // threads for that long would starve request handling.
    val vaultIo: ExecutionContext =
      system.dispatchers.lookup(DispatcherSelector.fromConfig("iris.vault-io-dispatcher"))

    // Readiness flips UP once the server is bound; withdrawn first on shutdown. Queries serve the
    // PRIOR index while the initial scan runs — health does not wait on the vault.
    val readiness = new AtomicBoolean(false)
    // The reconciler serializes nothing yet because phase 1 has exactly one writer of the index
    // (this process); the dispatcher's small pool is what keeps concurrent scans bounded.
    val triggerReindex: () => Unit = () =>
      Future {
        try
          reconciler.fullScan()
          ()
        catch case NonFatal(e) => log.error(s"reindex failed: ${e.getMessage}", e)
      }(vaultIo)
      ()
    val routes = RequestTracing.withCorrelationId(
      HealthRoutes(BuildInfo.version, () => readiness.get()) ~
        NoteRoutes(repo, triggerReindex).routes
    )

    HttpServer.bind(routes, cfg.http.host, cfg.http.port).onComplete {
      case Success(binding: ServerBinding) =>
        HttpServer.wireShutdown(binding, readiness)
        readiness.set(true)
        log.info(
          "iris {} bound HTTP :{} — vault root {} — readiness UP",
          BuildInfo.version,
          Integer.valueOf(binding.localAddress.getPort),
          cfg.vault.root
        )
        // Initial full scan on the vault-io dispatcher (blocking, and the quiesce gate inside it
        // can park for minutes); the watcher and the periodic rescan start once the initial pass
        // has the index caught up.
        Future {
          try
            // Wait out the sidecar's first download before scanning OR registering the watcher —
            // both are unreliable against a tree still being written. First-boot only; the
            // periodic rescan remains the net for later bulk landings.
            if cfg.scan.quiesce.enabled then quiesceVault(cfg)
            val s = reconciler.fullScan()
            log.info(
              "index holds {} notes after initial scan",
              java.lang.Long.valueOf(repo.noteCount())
            )
            s
          catch
            case NonFatal(e) =>
              log.error(s"initial scan failed: ${e.getMessage}", e)
        }(vaultIo).foreach { _ =>
          if cfg.scan.watchEnabled then
            val watcher =
              VaultWatcher(cfg.vault.root, reconciler, cfg.scan.debounce, triggerReindex)
            watcher.start()
            org.apache.pekko.actor
              .CoordinatedShutdown(system)
              .addTask(
                org.apache.pekko.actor.CoordinatedShutdown.PhaseServiceRequestsDone,
                "drain-vault-watcher"
              ) { () =>
                watcher.drain().map(_ => org.apache.pekko.Done)
              }
          system.scheduler
            .scheduleWithFixedDelay(cfg.scan.rescanInterval, cfg.scan.rescanInterval) { () =>
              triggerReindex()
            }
          ()
        }
      case Failure(ex) =>
        log.error(s"Failed to bind HTTP ${cfg.http.host}:${cfg.http.port} — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }

  /** Log whichever way the quiesce gate resolved; a timeout is a warning, never a failure. */
  private def quiesceVault(cfg: AppConfig): Unit =
    VaultQuiesce.awaitStable(cfg.vault.root, cfg.scan.quiesce) match
      case VaultQuiesce.Outcome.Stable(fp, waited) =>
        log.info(
          "vault mirror settled after {}s: {} notes on disk — starting initial scan",
          java.lang.Long.valueOf(waited.toSeconds),
          Integer.valueOf(fp.fileCount)
        )
      case VaultQuiesce.Outcome.TimedOut(fp, waited) =>
        log.warn(
          "vault mirror still changing after {}s ({} notes on disk) — scanning anyway; the {} "
            + "rescan heals a short index",
          java.lang.Long.valueOf(waited.toSeconds),
          Integer.valueOf(fp.fileCount),
          cfg.scan.rescanInterval
        )
