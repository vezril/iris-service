package me.cference.iris.write

import me.cference.iris.domain.{PathError, VaultPath}

/**
 * Why a write was refused.
 *
 * Every case is a refusal. There is deliberately no permissive case and no "unscoped" fallback —
 * property 4 of the write-access model (`docs/vault-access-model.md`): an unknown service, an
 * absent identity, or an unparseable path refuses the write.
 */
enum ConfinementError(val message: String):

  /** No token presented. For writes this is the deny-by-default path, surfaced as 401. */
  case NoIdentity extends ConfinementError("no service identity presented")

  /** A token that maps to nothing. Never name the claimed token back to the caller. */
  case UnknownService extends ConfinementError("unrecognized service identity")

  /** The service name is not a single safe path segment — it could not name a folder safely. */
  case IllegalServiceName(service: String)
      extends ConfinementError(s"illegal service name: '$service'")

  case UnparseablePath(cause: PathError)
      extends ConfinementError(s"unparseable vault path: ${cause.message}")

  /**
   * A `.` segment: legal for `VaultPath` but not canonical, so not something to confine against.
   */
  case NonCanonicalPath extends ConfinementError("path must not contain '.' segments")

  /** The path is outside the service's one writable folder. The common refusal. */
  case OutsideScope(path: String, scopeRoot: String)
      extends ConfinementError(s"'$path' is outside the writable scope '$scopeRoot/'")

  /** The path IS the scope folder. A service writes notes in its folder, not the folder itself. */
  case ScopeRootItself(scopeRoot: String)
      extends ConfinementError(s"'$scopeRoot' is the scope root, not a note within it")

/**
 * Why a [[ServiceRegistry]] could not be built from configuration.
 *
 * Separate from [[ConfinementError]] on purpose: these are boot-time faults read by an operator
 * from a crash log, not request-time refusals returned to a caller. Each one would silently widen
 * write access if it were tolerated, so each is fatal at boot — there is no safe degraded mode for
 * "some services can write to the wrong folder".
 */
enum RegistryError(val message: String):

  case IllegalServiceName(service: String)
      extends RegistryError(
        s"service '$service' is not a single safe path segment ([A-Za-z0-9][A-Za-z0-9_-]*)"
      )

  /** An empty token would authenticate a caller that presented nothing. */
  case EmptyToken(service: String)
      extends RegistryError(s"service '$service' has an empty write token")

  /** Two services sharing a token: one would write as the other, chosen by map ordering. */
  case DuplicateToken(service: String, collidesWith: String)
      extends RegistryError(
        s"service '$service' shares its write token with '$collidesWith'"
      )

/**
 * The one folder a service may write to: `Atlas/Olympus/<Service>/`.
 *
 * Constructed only through [[WriteScope.forService]], which validates the service name — a name
 * like `..` or `a/b` would otherwise name a folder outside the base and quietly widen the scope it
 * is supposed to narrow.
 */
final case class WriteScope private (service: String, root: String):

  private[write] val rootSegments: Vector[String] = root.split('/').toVector

  /**
   * True when `path` lies strictly inside this scope.
   *
   * Segment comparison, not string comparison — property 3: `Atlas/Olympus/Dionysus2/x.md` must not
   * match the scope `Atlas/Olympus/Dionysus`, which is exactly what a naive `startsWith` would do.
   *
   * Strictly inside: `length > rootSegments.length` rules out the scope folder itself.
   *
   * Case-SENSITIVE, intentionally. The vault lives on a case-insensitive macOS filesystem and is
   * mirrored onto a case-sensitive Linux volume, so the two disagree about whether `dionysus` and
   * `Dionysus` are the same folder. Exact matching refuses the ambiguous cases rather than
   * guessing, which is the fail-closed direction.
   */
  def contains(path: VaultPath): Boolean =
    val segments = path.value.split('/').toVector
    segments.length > rootSegments.length &&
    segments.take(rootSegments.length) == rootSegments

object WriteScope:

  /** Every service folder lives here. Not configurable: it is Calvin's binding rule, not a knob. */
  val Base: String = "Atlas/Olympus"

  /**
   * A service name must be one safe path segment. Anything else — a slash, a dot segment, an empty
   * string — could escape `Base` when concatenated, so it is refused rather than sanitized.
   */
  private[write] val LegalServiceName = "^[A-Za-z0-9][A-Za-z0-9_-]*$".r

  def forService(service: String): Either[ConfinementError, WriteScope] =
    if LegalServiceName.matches(service) then Right(WriteScope(service, s"$Base/$service"))
    else Left(ConfinementError.IllegalServiceName(service))

/**
 * A vault path **proven** to lie inside a service's writable folder.
 *
 * The constructor is private and the only way to obtain one is [[ConfinedPath.confine]], so a
 * writer that accepts `ConfinedPath` cannot be called with an unchecked path. That is property 5 of
 * the access model — "enforced in `core/`, not at the HTTP edge" — taken as far as the type system
 * allows: no future call path (a Hermes consumer, a batch job, a REPL) can reach a writer that
 * skipped the check, because it could not construct the argument.
 */
final case class ConfinedPath private (path: VaultPath, scope: WriteScope)

object ConfinedPath:

  /**
   * The gate. Parse, reject non-canonical forms, then segment-check against the scope.
   *
   * `VaultPath.parse` has already rejected absolute paths, `..` traversal, backslashes and empty
   * segments, so by this point the value is canonical except for `.` segments, which are refused
   * here — property 1: the confinement check is a segment test on an already-canonical value, never
   * on raw caller input.
   *
   * **What this does NOT do:** resolve symlinks. A symlink inside a service folder pointing
   * elsewhere escapes confinement, and detecting that needs the filesystem, which `core/` has no
   * business touching. Property 2 is therefore the writer's obligation and is enforced there — see
   * the scaladoc on the write path. A `ConfinedPath` means "this path is textually inside the
   * scope", which is necessary and not sufficient.
   */
  def confine(scope: WriteScope, rawPath: String): Either[ConfinementError, ConfinedPath] =
    VaultPath.parse(rawPath) match
      case Left(err) => Left(ConfinementError.UnparseablePath(err))
      case Right(vp) =>
        if vp.value.split('/').contains(".") then Left(ConfinementError.NonCanonicalPath)
        else if vp.value == scope.root then Left(ConfinementError.ScopeRootItself(scope.root))
        else if !scope.contains(vp) then Left(ConfinementError.OutsideScope(vp.value, scope.root))
        else Right(ConfinedPath(vp, scope))
