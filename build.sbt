import ScalazBuild._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage := Some(url("https://zio.dev")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

pgpPublicRing := file("/tmp/public.asc")
pgpSecretRing := file("/tmp/secret.asc")
releaseEarlyWith := SonatypePublisher
scmInfo := Some(
  ScmInfo(
    url("https://github.com/zio/interop-reactive-streams/"),
    "scm:git:git@github.com:zio/interop-reactive-streams.git"
  )
)

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

lazy val reactiveStreams = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(stdSettings("zio-interop-reactiveStreams"))
  .settings(buildInfoSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio"             %% "zio"                 % "1.0.0-RC12",
      "dev.zio"             %% "zio-streams"         % "1.0.0-RC12",
      "org.reactivestreams" % "reactive-streams"     % "1.0.3",
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.3" % Test,
      "org.scalatest"       %% "scalatest"           % "3.0.8" % Test,
      "org.specs2"          %% "specs2-core"         % "4.7.0" % Test,
      "com.typesafe.akka"   %% "akka-stream"         % "2.5.25" % Test,
      "com.typesafe.akka"   %% "akka-stream-testkit" % "2.5.25" % Test
    )
  )
