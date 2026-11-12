import com.typesafe.sbt.packager.docker.Cmd

// ---------------------------------------------------------------------------
// iris — the constellation ↔ Obsidian vault bridge (Scala 3 + Apache Pekko).
//
//   core   — pure domain logic + vault parsing (ZERO Pekko deps), unit-tested.
//   server — Pekko HTTP runtime + vault scanner + Postgres index + Main + image.
//
// Version is derived from git tags by sbt-dynver (project/plugins.sbt); no
// version literal is committed. The dynver separator is Docker-tag-safe ('-').
// ---------------------------------------------------------------------------

ThisBuild / organization := "me.cference.iris"
ThisBuild / scalaVersion := "3.3.4" // Scala 3 LTS

ThisBuild / homepage := Some(url("https://github.com/vezril/iris-service"))
ThisBuild / licenses := Seq(
  "MIT" -> url("https://github.com/vezril/iris-service/blob/main/LICENSE")
)
ThisBuild / startYear := Some(2026)
ThisBuild / developers := List(
  Developer(
    id = "vezril",
    name = "Calvin Ference",
    email = "calvin.ference@proton.me",
    url = url("https://github.com/vezril")
  )
)

// sbt-dynver: no version literal committed. Use a Docker-tag-safe separator
// (git describe's default '+' is illegal in image tags).
ThisBuild / dynverSeparator := "-"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:all"
)

// The `io.codex` contract jars (Lexicon) are published to GitHub Packages, not Maven Central, so
// a resolver and a credential are both required. The packages are marked "public", but GitHub's
// Maven registry still answers 401 to an anonymous request (verified 2026-11-03) — so "public"
// does not mean "anonymously resolvable" here, and a clean machine or CI runner cannot resolve
// lexicon-grpc at all. A developer who has run `publishLocal` in the-lexicon resolves it from
// ~/.ivy2/local and never notices.
//
// GitHub Actions supplies GITHUB_ACTOR and a GITHUB_TOKEN automatically; the job needs
// `permissions: packages: read` (set in every workflow that runs sbt). No stored secret is
// required or wanted: a long-lived PAT in repo secrets would be readable by any workflow.
// Locally, export GITHUB_TOKEN with read:packages (`export GITHUB_TOKEN=$(gh auth token)`).
// The credential is read from the environment and never committed.
ThisBuild / resolvers +=
  "Lexicon (GitHub Packages)" at "https://maven.pkg.github.com/vezril/the-lexicon"

ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_ACTOR", "vezril"),
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

lazy val pekkoVersion = "1.2.0"
// the-lexicon tag v0.8.0, published to GitHub Packages. Carries the Apollo object-API stubs
// (ObjectApiClient) for the phase-2 pre-write snapshot. Pekko stays 1.2.0 across the classpath.
lazy val lexiconVersion = "0.8.0"
lazy val pekkoHttpVersion = "1.2.0"
lazy val scalaTestVersion = "3.2.19"
lazy val logbackVersion = "1.5.16"
lazy val logstashEncoderVersion = "8.0"
lazy val snakeYamlVersion = "2.3"
lazy val postgresVersion = "42.7.4"
lazy val hikariVersion = "6.2.1"
lazy val testcontainersScalaVersion = "0.43.0"

// --- root: aggregate only, not published -------------------------------------
lazy val root = (project in file("."))
  .aggregate(core, server)
  .settings(
    name := "iris",
    publish / skip := true
  )

// --- core: pure domain + vault parsing, no Pekko. -----------------------------
lazy val core = (project in file("core"))
  .settings(
    name := "iris-core",
    libraryDependencies ++= Seq(
      // Frontmatter is YAML. Parsed through SafeConstructor only — arbitrary-class
      // instantiation via YAML tags is RCE, and this parser reads every vault note.
      "org.yaml" % "snakeyaml" % snakeYamlVersion,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test
    )
  )

// --- server: Pekko runtime + scanner + index + Main + Docker image. -----------
lazy val server = (project in file("server"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(
    name := "iris-server",
    Compile / mainClass := Some("me.cference.iris.Main"),
    // Forked run: main() returns after wiring async boot; the (non-daemon) Pekko
    // threads keep the forked JVM alive. Unforked, sbt would exit the app at return.
    Compile / run / fork := true,
    // docker-java advertises Engine API 1.32 unless told otherwise, and a current Docker Engine
    // rejects anything below 1.40 — which aborts NoteRepositorySpec, IndexReconcilerSpec and
    // VaultWatcherSpec with "client version 1.32 is too old. Minimum supported API version is
    // 1.40". The lever is docker-java's `api.version` SYSTEM PROPERTY; the DOCKER_API_VERSION
    // environment variable is not read (measured in apollo-storage, which hit this first). Tests
    // must therefore be forked for the property to reach the client.
    //
    // This is the fix for the real cause. Pinning the runner to an older image also made the
    // symptom go away, by pairing an old client with an old daemon — but it only deferred this.
    Test / fork := true,
    Test / javaOptions += "-Dapi.version=1.43",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      // Postgres read model: plain JDBC + Hikari (upsert-shaped workload; the
      // vault is the source of truth and this index is rebuildable from it).
      "org.postgresql" % "postgresql" % postgresVersion,
      "com.zaxxer" % "HikariCP" % hikariVersion,
      // Apollo object-API contract (generated stubs + ObjectApiClient), published from
      // the-lexicon. No codegen and no extra module here. Used by the phase-2 pre-write
      // snapshot: every vault write saves the prior bytes to Apollo first, and refuses if it
      // cannot (fail closed — the snapshot IS the undo button, since the vault is not in git).
      "io.codex" %% "lexicon-grpc" % lexiconVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Structured JSON logs (the constellation log schema).
      "net.logstash.logback" % "logstash-logback-encoder" % logstashEncoderVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersScalaVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaVersion % Test
    ),
    // BuildInfo exposes the dynver version to the running app (health endpoint).
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "me.cference.iris.build",
    buildInfoOptions += BuildInfoOption.ToJson,
    // --- Docker image (docker.io/calvinference/iris) --------------------------
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerExposedPorts := Seq(8080),
    dockerUpdateLatest := false, // release workflow controls :latest explicitly
    Docker / packageName := "iris",
    // Image namespace. CI provides DOCKERHUB_USERNAME (single source of truth,
    // matching the workflows); DOCKER_USERNAME is honored for local overrides,
    // then a sensible default so the image builds standalone.
    dockerUsername := Some(
      sys.env
        .get("DOCKERHUB_USERNAME")
        .orElse(sys.env.get("DOCKER_USERNAME"))
        .getOrElse("calvinference")
    ),
    Docker / version := version.value.replace('+', '-'),
    dockerEnvVars := Map("HTTP_PORT" -> "8080", "LOG_FORMAT" -> "json"),
    // Non-root daemon user (process must not run as root; the vault mount is
    // read-only at the pod level regardless — defense in depth).
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonUser := "iris",
    // HEALTHCHECK uses bash's /dev/tcp so no extra packages (wget/curl) are
    // needed. bash expands the HTTP_PORT override at runtime.
    dockerCommands ++= Seq(
      Cmd(
        "HEALTHCHECK",
        "--interval=10s --timeout=3s --start-period=20s --retries=5 CMD " +
          """["bash","-c","exec 3<>/dev/tcp/127.0.0.1/${HTTP_PORT:-8080}; """ +
          """printf 'GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3; """ +
          """grep -q '200 OK' <&3"]"""
      )
    )
  )
