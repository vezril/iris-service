package me.cference.iris.scan

import me.cference.iris.domain.VaultPath
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.slf4j.LoggerFactory

import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Live vault watching: a `WatchService` registered on every vault directory (new directories are
 * registered as they appear), feeding a Pekko Streams queue that batches events per path inside the
 * debounce window — Obsidian Sync touches files repeatedly mid-sync, and the reconciler's hash
 * check makes redundant wakeups no-ops anyway.
 *
 * An `OVERFLOW` event (or queue saturation) means events were lost: the response is a full rescan,
 * not guesswork. Structure (running flag, drain future) follows the constellation's consumer-loop
 * pattern.
 */
final class VaultWatcher(
    root: Path,
    reconciler: IndexReconciler,
    debounce: FiniteDuration,
    onOverflow: () => Unit
)(using system: ActorSystem[Nothing]):

  private val log = LoggerFactory.getLogger(getClass)
  private val running = new AtomicBoolean(true)
  private val drained = Promise[Unit]()
  import system.executionContext

  private val watcher = FileSystems.getDefault.newWatchService()

  // BoundedSourceQueue: offer() answers synchronously (Enqueued / Dropped-when-full).
  private val (queue, streamDone) =
    Source
      .queue[VaultPath](4096)
      .groupedWithin(512, debounce)
      .map(_.distinct)
      .toMat(Sink.foreach { batch =>
        batch.foreach { vp =>
          reconciler.reconcilePath(vp)
          ()
        }
      })(Keep.both)
      .run()

  /** Register the whole tree and start the take-loop thread. */
  def start(): Unit =
    registerTree(root)
    val t = new Thread(() => takeLoop(), "iris-vault-watcher")
    t.setDaemon(true)
    t.start()
    log.info("vault watcher started on {}", root)

  /** Stop intake and wait for in-flight reconciles to finish. */
  def drain(): Future[Unit] =
    if running.compareAndSet(true, false) then
      try watcher.close()
      catch case NonFatal(_) => ()
      queue.complete()
      streamDone.onComplete(_ => drained.trySuccess(()))
    drained.future

  private def registerTree(dir: Path): Unit =
    if Files.isDirectory(dir) then
      Files.walk(dir).iterator().asScala.filter(Files.isDirectory(_)).foreach { d =>
        val name = d.getFileName
        val isDot = d != root && name != null && name.toString.startsWith(".")
        if !isDot then register(d)
      }

  private def register(dir: Path): Unit =
    try
      dir.register(
        watcher,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE
      )
      ()
    catch case NonFatal(e) => log.warn("could not watch {}: {}", dir, e.getMessage)

  private def takeLoop(): Unit =
    while running.get() do
      val key =
        try watcher.take()
        catch
          case _: ClosedWatchServiceException | _: InterruptedException =>
            null // shutting down
      if key != null then
        val watchedDir = key.watchable().asInstanceOf[Path]
        key.pollEvents().asScala.foreach { event =>
          event.kind() match
            case StandardWatchEventKinds.OVERFLOW =>
              log.warn("watch overflow — scheduling full rescan")
              onOverflow()
            case kind =>
              val rel = event.context().asInstanceOf[Path]
              val abs = watchedDir.resolve(rel)
              // A new directory must be watched (and its pre-existing contents reconciled).
              if kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(abs) then
                registerTree(abs)
                VaultScanner.listVault(abs).foreach(fm => offerRelative(abs, fm.path))
              else offer(abs)
        }
        if !key.reset() then log.warn("watch key for {} is no longer valid", watchedDir)

  private def offerRelative(subRoot: Path, subPath: VaultPath): Unit =
    // listVault returned paths relative to subRoot; rebase them onto the vault root.
    offer(subRoot.resolve(subPath.value))

  private def offer(abs: Path): Unit =
    val rel = root.relativize(abs).toString.replace('\\', '/')
    VaultPath.parse(rel) match
      case Right(vp) if VaultRules.shouldIndex(vp) =>
        queue.offer(vp) match
          case QueueOfferResult.Enqueued => ()
          case QueueOfferResult.Dropped =>
            log.warn("watch queue full — scheduling full rescan")
            onOverflow()
          case other =>
            log.warn("watch queue rejected {}: {}", vp.value, other)
      case _ => () // excluded or unparseable — not ours
