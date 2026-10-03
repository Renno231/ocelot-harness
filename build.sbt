import sbt._
import Keys._

import scala.sys.process._

val supportedScalaVersion = "2.13.16"

ThisBuild / organization := "org.ocelot-harness"
ThisBuild / version := "0.1.1"
ThisBuild / scalaVersion := supportedScalaVersion

lazy val commonSettings = Seq(
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Werror",
    "-release:8"
  ),
  Test / fork := true,
  Test / parallelExecution := false,
  scalafmtOnCompile := false,
  // ocelot-brain resolves Log4j 2.25.1, which has known advisories; pin the fixed release.
  dependencyOverrides ++= Seq(
    "org.apache.logging.log4j" % "log4j-api" % "2.25.5",
    "org.apache.logging.log4j" % "log4j-core" % "2.25.5"
  )
)

lazy val buildIdentityResource = Def.task {
  val repository = (LocalRootProject / baseDirectory).value
  val output = (Compile / resourceManaged).value / "ocelot-harness-build.properties"
  val commit = Process(Seq("git", "rev-parse", "HEAD"), repository).!!.trim
  val dirty = Process(Seq("git", "status", "--porcelain", "--untracked-files=all"), repository).!!.trim.nonEmpty
  IO.write(output, s"harness.commit=$commit\nharness.dirty=$dirty\n")
  Seq(output)
}

lazy val releaseMetadataResources = Def.task {
  val repository = (LocalRootProject / baseDirectory).value
  val output = (Compile / resourceManaged).value / "META-INF" / "ocelot-harness"
  val resources = Vector(
    repository / "LICENSE" -> (output / "licenses" / "ocelot-harness-MIT.txt"),
    repository / "THIRD_PARTY_NOTICES.md" -> (output / "THIRD_PARTY_NOTICES.md"),
    repository / "lib" / "ocelot-brain" / "LICENSE" -> (output / "licenses" / "ocelot-brain-MIT.txt"),
    repository / "lib" / "ocelot-brain" / "LICENSE-oc" -> (output / "licenses" / "OpenComputers-resources.txt"),
    repository / "lib" / "ocelot-brain" / "LICENSE-unifont" -> (output / "licenses" / "unifont-OFL-1.1.txt")
  )
  resources.foreach { case (source, target) => IO.copyFile(source, target) }
  resources.map(_._2)
}

lazy val ocelotBrain = RootProject(file("lib/ocelot-brain"))

// The external build's explicit settings win initial loading, so reapply compatibility pins afterward.
Global / onLoad := {
  val previous = (Global / onLoad).value
  state => {
    val loaded = previous(state)
    val extracted = Project.extract(loaded)
    if (extracted.get(ocelotBrain / scalaVersion) == supportedScalaVersion) loaded
    else {
      extracted.appendWithSession(
        Seq(
          ocelotBrain / scalaVersion := supportedScalaVersion,
          ocelotBrain / Compile / javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
          ocelotBrain / Compile / scalacOptions += "-release:8"
        ),
        loaded
      )
    }
  }
}

lazy val harnessCore = (project in file("modules/core"))
  .dependsOn(ocelotBrain)
  .settings(commonSettings)
  .settings(
    name := "harness-core",
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.4",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    Compile / resourceGenerators += buildIdentityResource.taskValue
  )

lazy val harnessApp = (project in file("modules/app"))
  .dependsOn(harnessCore)
  .settings(commonSettings)
  .settings(
    name := "harness-app",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "3.3.1",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    Compile / mainClass := Some("ocelot.harness.app.HarnessDaemon"),
    Compile / resourceGenerators += releaseMetadataResources.taskValue,
    assembly / mainClass := (Compile / mainClass).value,
    assembly / assemblyJarName := "ocelot-harness.jar",
    assembly / assemblyOutputPath := baseDirectory.value / "target" / (assembly / assemblyJarName).value,
    assembly / assemblyMergeStrategy := {
      case path if path == "module-info.class" || path.endsWith("/module-info.class") =>
        MergeStrategy.discard
      case path
          if path.startsWith("assets/opencomputers/lib/libjnlua") &&
            path.endsWith("-windows-x86.dll") =>
        MergeStrategy.preferProject
      case path =>
        val defaultStrategy = (assembly / assemblyMergeStrategy).value
        defaultStrategy(path)
    },
    assembly / test := {}
  )

lazy val root = (project in file("."))
  .aggregate(harnessCore, harnessApp)
  .settings(
    name := "ocelot-harness",
    publish / skip := true
  )
