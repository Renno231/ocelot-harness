import sbt._
import Keys._

ThisBuild / organization := "org.ocelot-harness"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.10"

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
  scalafmtOnCompile := false
)

lazy val ocelotBrain = RootProject(file("lib/ocelot-brain"))

lazy val harnessCore = (project in file("modules/core"))
  .dependsOn(ocelotBrain)
  .settings(commonSettings)
  .settings(
    name := "harness-core",
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.4",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
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
