import sbt._
import Keys._
import explicitdeps.ExplicitDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtbuildinfo._
import BuildInfoKeys._
import scalafix.sbt.ScalafixPlugin.autoImport._

object BuildHelper {
  val Scala211   = "2.11.12"
  val Scala212   = "2.12.13"
  val Scala213   = "2.13.5"
  val ScalaDotty = "3.0.0"

  private val stdOptions = Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked"
  )

  private val std2xOptions = Seq(
    "-language:higherKinds",
    "-language:existentials",
    "-explaintypes",
    "-Yrangepos",
    "-Xlint:_,-missing-interpolator,-type-parameter-shadow",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  ) ++ customOptions

  private def optimizerOptions(optimize: Boolean) =
    if (optimize)
      Seq(
        "-opt:l:inline",
        "-opt-inline-from:zio.internal.**"
      )
    else Nil

  private def propertyFlag(property: String, default: Boolean) =
    sys.props.get(property).map(_.toBoolean).getOrElse(default)

  private def customOptions =
    if (propertyFlag("fatal.warnings", true)) {
      Seq("-Xfatal-warnings")
    } else {
      Nil
    }

  def buildInfoSettings(packageName: String) =
    Seq(
      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := packageName,
      buildInfoObject := "BuildInfo"
    )

  val dottySettings = Seq(
    // Keep this consistent with the version in .circleci/config.yml
    crossScalaVersions += ScalaDotty,
    scalacOptions ++= {
      if (scalaVersion.value == ScalaDotty)
        Seq("-noindent")
      else
        Seq()
    },
    Compile / doc / sources := {
      val old = (Compile / doc / sources).value
      if (scalaVersion.value == ScalaDotty) {
        Nil
      } else {
        old
      }
    },
    Test / parallelExecution := {
      val old = (Test / parallelExecution).value
      if (scalaVersion.value == ScalaDotty) {
        false
      } else {
        old
      }
    }
  )

  def extraOptions(scalaVersion: String, optimize: Boolean) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, _)) =>
        Seq(
          "-language:implicitConversions",
          "-Xignore-scala2-macros"
        )
      case Some((2, 13)) =>
        Seq(
          "-Ywarn-unused:params,-implicits"
        ) ++ std2xOptions ++ optimizerOptions(optimize)
      case Some((2, 12)) =>
        Seq(
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused:_,imports",
          "-Ywarn-unused:imports",
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Ywarn-unused:params,-implicits",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions ++ optimizerOptions(optimize)
      case Some((2, 11)) =>
        Seq(
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Xexperimental",
          "-Ywarn-unused-import",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions
      case _ => Seq.empty
    }

  def stdSettings(prjName: String) = Seq(
    name := s"$prjName",
    crossScalaVersions := Seq(Scala211, Scala212, Scala213),
    ThisBuild / scalaVersion := Scala213,
    scalacOptions := stdOptions ++ extraOptions(scalaVersion.value, optimize = !isSnapshot.value),
    semanticdbEnabled := !(scalaVersion.value == ScalaDotty), // enable SemanticDB
    semanticdbOptions += "-P:semanticdb:synthetics:on",
    semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
    ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
    ThisBuild / scalafixDependencies ++= List(
      "com.github.liancheng" %% "organize-imports" % "0.4.3",
      "com.github.vovapolu"  %% "scaluzzi"         % "0.1.15"
    ),
    Test / parallelExecution := true,
    incOptions ~= (_.withLogRecompileOnMacro(false)),
    autoAPIMappings := true,
    unusedCompileDependenciesFilter -= moduleFilter("org.scala-js", "scalajs-library"),
    Compile / unmanagedSourceDirectories ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, x)) if x <= 11 =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.11")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.11")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-2.11")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.x")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.11-2.12"))
          ).flatten
        case Some((2, x)) if x == 12 =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.12")),
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.x")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.12-2.13")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.11-2.12"))
          ).flatten
        case Some((2, x)) if x >= 13 =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.12")),
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.x")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.12-2.13")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.13+"))
          ).flatten
        case Some((3, _)) =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.12")),
            Seq(file(sourceDirectory.value.getPath + "/main/scala-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-dotty")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.13+"))
          ).flatten
        case _ =>
          Nil
      }
    },
    Test / unmanagedSourceDirectories ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, x)) if x <= 11 =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/test/scala-2.11")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-2.x"))
          ).flatten
        case Some((2, x)) if x >= 12 =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/test/scala-2.12")),
            Seq(file(sourceDirectory.value.getPath + "/test/scala-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-2.x"))
          ).flatten
        case Some((3, _)) =>
          Seq(
            Seq(file(sourceDirectory.value.getPath + "/test/scala-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-2.12+")),
            CrossType.Full.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + "-dotty"))
          ).flatten
        case _ =>
          Nil
      }

    }
  )

  implicit class ModuleHelper(p: Project) {
    def module: Project = p.in(file(p.id)).settings(stdSettings(p.id))
  }
}
