import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://zio.dev")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "runtologist",
        "Simon Schenk",
        "simon@schenk-online.net",
        url("https://github.com/runtologist")
      ),
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    ),
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zio/interop-reactive-streams/"),
        "scm:git:git@github.com:zio/interop-reactive-streams.git"
      )
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

val zioVersion        = "1.0.0-RC18-1"
val rsVersion         = "1.0.3"
val collCompatVersion = "2.1.4"

lazy val interopReactiveStreams = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(stdSettings("zio-interop-reactivestreams"))
  .settings(buildInfoSettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .settings(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio"                     % zioVersion,
      "dev.zio"                %% "zio-streams"             % zioVersion,
      "dev.zio"                %% "zio-test"                % zioVersion % Test,
      "dev.zio"                %% "zio-test-sbt"            % zioVersion % Test,
      "org.reactivestreams"    % "reactive-streams"         % rsVersion,
      "org.reactivestreams"    % "reactive-streams-tck"     % rsVersion % Test,
      "org.scala-lang.modules" %% "scala-collection-compat" % collCompatVersion % Test
    )
  )
