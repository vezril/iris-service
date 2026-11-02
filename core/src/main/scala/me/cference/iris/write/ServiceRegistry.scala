package me.cference.iris.write

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Maps a presented token to the service it proves, and thence to that service's one writable
 * folder.
 *
 * The direction matters and is the whole point: the caller presents a **token**, never a service
 * name. A service can therefore *prove* an identity but never *claim* one — the access model's
 * requirement that "the mapping is explicit configuration, not derived from a caller-supplied
 * name". Nothing in the write path accepts a service name from the wire.
 *
 * Tokens come from k8s Secrets (one per consuming service) and never from chart values or config
 * files. This type holds them in memory and is the only thing that sees them; errors never echo a
 * presented token back to the caller.
 */
final class ServiceRegistry private (entries: Vector[(Array[Byte], WriteScope)]):

  /** How many identities are configured. For a boot log line — never logs the tokens. */
  def size: Int = entries.size

  /** The services this registry can authenticate, for a boot log line. */
  def services: Vector[String] = entries.map(_._2.service).sorted

  /**
   * Resolve a presented token to its scope.
   *
   * `None` is [[ConfinementError.NoIdentity]] and an unmatched token is
   * [[ConfinementError.UnknownService]] — distinguished for the log, both a refusal.
   *
   * Every configured entry is compared with [[MessageDigest.isEqual]] and the loop does not break
   * early, so the work done is independent of which token matched or how far it got. A map lookup
   * would be shorter and would leak comparison timing; with a handful of services the constant-time
   * sweep costs nothing worth measuring.
   */
  def resolve(presentedToken: Option[String]): Either[ConfinementError, WriteScope] =
    presentedToken match
      case None => Left(ConfinementError.NoIdentity)
      case Some(token) if token.isEmpty => Left(ConfinementError.NoIdentity)
      case Some(token) =>
        val presented = token.getBytes(StandardCharsets.UTF_8)
        var found: Option[WriteScope] = None
        entries.foreach { case (candidate, scope) =>
          if MessageDigest.isEqual(candidate, presented) then found = Some(scope)
        }
        found.toRight(ConfinementError.UnknownService)

  /**
   * Resolve an identity and confine a path in one step — the only entry point writers should use.
   */
  def confine(
      presentedToken: Option[String],
      rawPath: String
  ): Either[ConfinementError, ConfinedPath] =
    resolve(presentedToken).flatMap(scope => ConfinedPath.confine(scope, rawPath))

object ServiceRegistry:

  /**
   * An empty registry authenticates nobody, so every write refuses. The correct phase-1 default.
   */
  val empty: ServiceRegistry = new ServiceRegistry(Vector.empty)

  /**
   * Build from a `service -> token` configuration.
   *
   * Refuses rather than repairs, because every failure here would otherwise widen access silently:
   *   - an illegal service name (it could not safely name a folder),
   *   - an empty token (it would authenticate a caller that presented nothing),
   *   - a token shared by two services (one service would write as another; which one it got would
   *     depend on map ordering).
   *
   * A registry that cannot be built is a boot failure, not a warning — there is no safe degraded
   * mode for "some services can write to the wrong folder".
   */
  def build(config: Map[String, String]): Either[RegistryError, ServiceRegistry] =
    val entries = Vector.newBuilder[(Array[Byte], WriteScope)]
    var error: Option[RegistryError] = None
    // token -> the service that claimed it first, so a collision can name both sides.
    val tokenOwner = scala.collection.mutable.Map.empty[String, String]

    config.toVector.sortBy(_._1).foreach { case (service, token) =>
      if error.isEmpty then
        if !WriteScope.LegalServiceName.matches(service) then
          error = Some(RegistryError.IllegalServiceName(service))
        else if token.isEmpty then error = Some(RegistryError.EmptyToken(service))
        else
          tokenOwner.get(token) match
            case Some(owner) => error = Some(RegistryError.DuplicateToken(service, owner))
            case None =>
              tokenOwner(token) = service
              WriteScope.forService(service) match
                case Left(_) => error = Some(RegistryError.IllegalServiceName(service))
                case Right(scope) =>
                  entries += (token.getBytes(StandardCharsets.UTF_8) -> scope)
    }

    error.toLeft(new ServiceRegistry(entries.result()))
