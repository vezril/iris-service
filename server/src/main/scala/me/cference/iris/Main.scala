package me.cference.iris

import me.cference.iris.build.BuildInfo
import me.cference.iris.config.AppConfig
import me.cference.iris.http.{HealthRoutes, HttpServer}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
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

    given system: ActorSystem[Nothing] =
      ActorSystem[Nothing](Behaviors.empty[Nothing], "iris", raw)
    import system.executionContext

    // A missing vault root is a misconfiguration worth failing loudly over later (the scanner
    // needs it), but at boot we only warn: the sidecar may still be running its first sync.
    if !Files.isDirectory(cfg.vault.root) then
      log.warn("vault root {} does not exist (yet) — waiting on the sync sidecar?", cfg.vault.root)

    // Readiness flips UP once the server is bound; withdrawn first on shutdown.
    val readiness = new AtomicBoolean(false)
    val routes = HealthRoutes(BuildInfo.version, () => readiness.get())

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
      case Failure(ex) =>
        log.error(s"Failed to bind HTTP ${cfg.http.host}:${cfg.http.port} — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }
