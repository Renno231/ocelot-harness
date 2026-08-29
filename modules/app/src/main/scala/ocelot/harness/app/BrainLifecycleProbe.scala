package ocelot.harness.app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import ocelot.harness.core.HarnessError
import ocelot.harness.core.project.{ComputerId, ScreenId}
import ocelot.harness.core.runtime.{RuntimeConfig, RuntimeOwner}
import ocelot.harness.core.workspace._

object BrainLifecycleProbe {
  def main(arguments: Array[String]): Unit = {
    require(
      arguments.length == 3,
      "expected native-library, runtime, and project directory arguments"
    )

    val nativeLibraryDirectory = Paths.get(arguments(0)).toAbsolutePath.normalize()
    val runtimeDirectory = Paths.get(arguments(1)).toAbsolutePath.normalize()
    val projectDirectory = Paths.get(arguments(2)).toAbsolutePath.normalize()
    Files.createDirectories(projectDirectory)
    val manifest = projectDirectory.resolve("ocelot-harness.conf")
    if (!Files.exists(manifest)) {
      Files.write(manifest, ProbeManifest.getBytes(StandardCharsets.UTF_8))
    }

    val owner = RuntimeOwner
      .start(RuntimeConfig(runtimeDirectory, nativeLibraryDirectory))
      .fold(error => throw new IllegalStateException(s"${error.code}: ${error.message}"), identity)

    try {
      Console.out.println(s"BRAIN_LIFECYCLE_INITIALIZED version=${owner.brainVersion}")
      require(owner.nativeLuaAvailable, "no compatible native Lua library was loaded")
      Console.out.println("BRAIN_NATIVE_LUA_AVAILABLE=true")

      val session = owner
        .openProject(projectDirectory)
        .fold(
          error => throw new IllegalStateException(s"${error.code}: ${error.message}"),
          identity
        )
      Console.out.println("BRAIN_PROJECT_SESSION_OPENED")
      require(session.describe().computers.nonEmpty, "project topology is empty")
      if (Files.isRegularFile(projectDirectory.resolve("expected").resolve("markers.txt"))) {
        runVerticalSmoke(session, projectDirectory)
      }
      session.close()
      Console.out.println("BRAIN_PROJECT_SESSION_CLOSED")
    } finally {
      owner.close()
      Console.out.println("BRAIN_LIFECYCLE_SHUTDOWN")
    }

    val currentThread = Thread.currentThread()
    val liveHarnessThreads = Thread.getAllStackTraces
      .keySet()
      .asScala
      .count(thread =>
        (thread ne currentThread) && thread.isAlive && !thread.isDaemon && thread.getName
          .startsWith("ocelot-harness-")
      )
    Console.out.println(s"HARNESS_NON_DAEMON_THREADS=$liveHarnessThreads")
  }

  private def runVerticalSmoke(
      session: HarnessSession,
      projectDirectory: java.nio.file.Path
  ): Unit = {
    val computerId = parseOrThrow(ComputerId.parse("main"), "computer ID")
    val screenId = parseOrThrow(ScreenId.parse("main"), "screen ID")
    valueOrThrow(session.startMachine(computerId), "start machine")
    runUntil(session, ScreenContains(screenId, "READY"))
    Console.out.println("BRAIN_VERTICAL_READY=true")

    valueOrThrow(session.send(screenId, UserInput.Touch(1, 1)), "touch screen")
    runUntil(session, ScreenContains(screenId, "TOUCHED"))
    Console.out.println("BRAIN_VERTICAL_TOUCH=true")

    valueOrThrow(session.send(screenId, UserInput.Paste("PACKAGED-SMOKE")), "paste text")
    runUntil(session, ScreenContains(screenId, "PACKAGED-SMOKE"))
    Console.out.println("BRAIN_VERTICAL_PASTE=true")

    val mainFile = projectDirectory.resolve("computer").resolve("main.lua")
    val changed = new String(Files.readAllBytes(mainFile), StandardCharsets.UTF_8).replace(
      "local marker = \"READY\"",
      "local marker = \"PACKAGED-EDIT\""
    )
    Files.write(mainFile, changed.getBytes(StandardCharsets.UTF_8))
    valueOrThrow(session.resetMachine(computerId), "reset machine")
    runUntil(session, ScreenContains(screenId, "PACKAGED-EDIT"))
    Console.out.println("BRAIN_VERTICAL_HOST_EDIT=true")
  }

  private def runUntil(session: HarnessSession, condition: StopCondition): Unit = {
    val result = valueOrThrow(
      session.run(RunRequest(condition, maxTicks = 2000, maxWallTime = 10.seconds)),
      "run condition"
    )
    require(
      result.stopReason == RunStopReason.ConditionSatisfied,
      s"condition did not complete: ${result.stopReason}"
    )
  }

  private def valueOrThrow[A](value: Either[HarnessError, A], operation: String): A =
    value.fold(
      error =>
        throw new IllegalStateException(s"$operation failed: ${error.code}: ${error.message}"),
      identity
    )

  private def parseOrThrow[A](value: Either[String, A], operation: String): A =
    value.fold(error => throw new IllegalStateException(s"$operation failed: $error"), identity)

  private val ProbeManifest =
    """schemaVersion = 1
      |project { id = "lifecycle-probe" }
      |runtime { }
      |computers {
      |  main {
      |    caseTier = 3
      |    hardware {
      |      cpu = { tier = 3 }
      |      memory = [{ tier = 3 }]
      |      gpu = { tier = 3 }
      |      eeprom = { builtin = "lua-bios" }
      |    }
      |  }
      |}
      |screens { main { tier = 3, keyboard = false } }
      |connections = [{ from = "computer:main", to = "screen:main" }]
      |""".stripMargin
}
