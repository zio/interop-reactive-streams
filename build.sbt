import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage     := Some(url("https://zio.dev")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
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

Global / onChangedBuildSource := ReloadOnSourceChanges

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

val zioVersion        = "1.0.11"
val rsVersion         = "1.0.3"
val collCompatVersion = "2.5.0"

lazy val interopReactiveStreams = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(buildInfoSettings("zio.interop.reactivestreams"))
  .settings(stdSettings("zio-interop-reactivestreams"))
  .settings(dottySettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"            %% "zio"                  % zioVersion,
      "dev.zio"            %% "zio-streams"          % zioVersion,
      "dev.zio"            %% "zio-test"             % zioVersion % Test,
      "dev.zio"            %% "zio-test-sbt"         % zioVersion % Test,
      "org.reactivestreams" % "reactive-streams"     % rsVersion,
      "org.reactivestreams" % "reactive-streams-tck" % rsVersion  % Test
    ),
    libraryDependencies ++= {
      if (scalaVersion.value == ScalaDotty)
        Seq()
      else
        Seq("org.scala-lang.modules" %% "scala-collection-compat" % collCompatVersion % Test)
    }
  )
