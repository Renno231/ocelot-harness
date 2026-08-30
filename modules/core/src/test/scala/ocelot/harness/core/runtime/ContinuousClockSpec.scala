package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.project.{ComputerId, ScreenId}
import ocelot.harness.core.workspace._

final class ContinuousClockSpec extends AnyFunSuite with Matchers {
  test("a schema-v2 session continuously ticks and provides exact clock barriers") {
    val work = Files.createTempDirectory("ocelot-harness-continuous-clock-")
    val project = work.resolve("project")
    val stdout = work.resolve("stdout.log")
    val stderr = work.resolve("stderr.log")
    try {
      copyDirectory(fixtureDirectory, project)
      val manifest = project.resolve("ocelot-harness.conf")
      val updated = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
        .replace(
          "schemaVersion = 1",
          "schemaVersion = 2\nworkspace { kind = \"manifest\" }"
        )
        .replace("tickRate = 20", "tickRate = 20\n  clock { autoStart = true }")
      Files.write(manifest, updated.getBytes(StandardCharsets.UTF_8))

      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.ContinuousClockProbe",
        work.resolve("native").toString,
        work.resolve("runtime").toString,
        project.toString
      ).redirectOutput(stdout.toFile).redirectError(stderr.toFile).start()

      val exited = process.waitFor(90L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      val out = readText(stdout)
      val err = readText(stderr)
      withClue(s"clock probe stdout:\n$out\nstderr:\n$err\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        out should include("CLOCK_AUTOSTART=true")
        out should include("CLOCK_CONTINUOUS=true")
        out should include("CLOCK_PAUSE_STEP=true")
        out should include("CLOCK_RATE=true")
        out should include("CLOCK_BARRIERS=true")
        out should include("CLOCK_CLEANUP=true")
      }
    } finally deleteRecursively(work)
  }

  private def fixtureDirectory: Path = {
    val candidates = Vector(
      Paths.get("fixtures", "vertical-spike"),
      Paths.get("..", "..", "fixtures", "vertical-spike")
    ).map(_.toAbsolutePath.normalize())
    candidates.find(Files.isDirectory(_)).getOrElse(fail("vertical-spike fixture is missing"))
  }

  private def copyDirectory(source: Path, destination: Path): Unit = {
    val paths = Files.walk(source)
    try
      paths.iterator().asScala.foreach { path =>
        val target = destination.resolve(source.relativize(path).toString)
        if (Files.isDirectory(path)) Files.createDirectories(target)
        else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
      }
    finally paths.close()
  }

  private def readText(path: Path): String =
    if (Files.exists(path)) new String(Files.readAllBytes(path), StandardCharsets.UTF_8) else ""

  private def deleteRecursively(root: Path): Unit =
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }

  private def javaExecutable: Path = {
    val executable =
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }
}

private[runtime] object ContinuousClockProbe extends EitherValues with Matchers {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 3, "expected native, runtime, and project directories")
    val owner = RuntimeOwner
      .start(
        RuntimeConfig(
          Paths.get(arguments(1)).toAbsolutePath.normalize(),
          Paths.get(arguments(0)).toAbsolutePath.normalize()
        )
      )
      .value
    val session = owner.openProject(Paths.get(arguments(2))).value
    val computer = ComputerId.parse("main").value
    val screen = ScreenId.parse("main").value
    try {
      val initial = session.clockStatus().value
      initial.state shouldBe SimulationClockState.Running
      initial.targetTps shouldBe 20
      Console.out.println("CLOCK_AUTOSTART=true")

      session.startMachine(computer).value
      waitUntil(10.seconds) {
        session.readScreen(screen).value.text.contains("READY")
      }
      val before = session.clockStatus().value
      waitUntil(3.seconds) {
        session.clockStatus().value.totalTicks >= before.totalTicks + 3L
      }
      session.clockStatus().value.measuredTps should be > 0.0
      Console.out.println("CLOCK_CONTINUOUS=true")

      val paused = session.pauseClock().value
      paused.state shouldBe SimulationClockState.Paused
      val frozenTick = session.readScreen(screen).value.captureTick
      val frozenTotal = paused.totalTicks
      Thread.sleep(150L)
      session.readScreen(screen).value.captureTick shouldBe frozenTick
      session.clockStatus().value.totalTicks shouldBe frozenTotal

      val stepped = session.stepClock(3).value
      stepped.totalTicks shouldBe frozenTotal + 3L
      session.readScreen(screen).value.captureTick shouldBe frozenTick + 3L
      session.stepClock(0).left.value.code shouldBe "invalid_clock_request"
      Console.out.println("CLOCK_PAUSE_STEP=true")

      session.setClockRate(100).value.targetTps shouldBe 100
      val resumed = session.resumeClock().value
      resumed.state shouldBe SimulationClockState.Running
      val rateStart = resumed.totalTicks
      waitUntil(2.seconds) {
        session.clockStatus().value.totalTicks >= rateStart + 10L
      }
      val atRate = session.clockStatus().value
      atRate.targetTps shouldBe 100
      atRate.measuredTps should be > 20.0
      session.startClock(Some(1001)).left.value.code shouldBe "invalid_clock_request"
      Console.out.println("CLOCK_RATE=true")

      val snapshot = SnapshotName.parse("clock-barrier").value
      session.saveSnapshot(SnapshotRequest(snapshot)).value
      session.loadSnapshot(snapshot).value
      session.clockStatus().value.state shouldBe SimulationClockState.Running
      session
        .run(
          RunRequest(
            ScreenContains(screen, "READY"),
            maxTicks = 10,
            maxWallTime = 2.seconds
          )
        )
        .value
      session.clockStatus().value.state shouldBe SimulationClockState.Running
      Console.out.println("CLOCK_BARRIERS=true")
    } finally {
      session.close()
      owner.close()
      val live = Thread.getAllStackTraces
        .keySet()
        .asScala
        .count(thread =>
          thread.isAlive && !thread.isDaemon && thread.getName.startsWith("ocelot-harness-")
        )
      live shouldBe 0
      Console.out.println("CLOCK_CLEANUP=true")
    }
  }

  private def waitUntil(limit: FiniteDuration)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + limit.toNanos
    var satisfied = condition
    while (!satisfied && System.nanoTime() < deadline) {
      Thread.sleep(10L)
      satisfied = condition
    }
    satisfied shouldBe true
  }
}
