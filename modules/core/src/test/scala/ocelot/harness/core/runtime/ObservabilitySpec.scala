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

final class ObservabilitySpec extends AnyFunSuite with Matchers {
  test("a real computer can be traced, metered, profiled, debugged, recorded and replayed") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-observability-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")
    try {
      copyDirectory(fixtureDirectory, projectDirectory)
      Files.write(
        projectDirectory.resolve("computer").resolve("main.lua"),
        ObservabilityProbe.Program.getBytes(StandardCharsets.UTF_8)
      )
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.ObservabilityProbe",
        workDirectory.resolve("native-libraries").toString,
        workDirectory.resolve("runtime").toString,
        projectDirectory.toString
      )
        .redirectOutput(stdoutFile.toFile)
        .redirectError(stderrFile.toFile)
        .start()
      val exited = process.waitFor(180L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      val stdout = readText(stdoutFile)
      val stderr = readText(stderrFile)
      withClue(s"Observability probe stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        Vector(
          "TRACE",
          "METRICS",
          "PROFILE",
          "DEBUG",
          "RECORD",
          "REPLAY",
          "DIVERGE",
          "SWEEP",
          "DIFF",
          "JFR",
          "BUNDLE"
        )
          .foreach(marker => stdout should include(s"OBSERVABILITY_$marker=true"))
      }
    } finally deleteRecursively(workDirectory)
  }

  private def fixtureDirectory: Path = {
    val candidates = Vector(
      Paths.get("fixtures", "vertical-spike"),
      Paths.get("..", "..", "fixtures", "vertical-spike")
    ).map(_.toAbsolutePath.normalize())
    candidates.find(Files.isDirectory(_)).getOrElse(fail("vertical-spike fixture not found"))
  }

  private def copyDirectory(source: Path, destination: Path): Unit = {
    val paths = Files.walk(source)
    try {
      paths.iterator().asScala.foreach { path =>
        val target = destination.resolve(source.relativize(path).toString)
        if (Files.isDirectory(path)) Files.createDirectories(target)
        else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally paths.close()
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

private[runtime] object ObservabilityProbe extends EitherValues with Matchers {
  // Line 14 does the heavy work; the debugger test breaks there.
  val Program: String =
    """local gpu = component.proxy(component.list("gpu")())
      |gpu.bind((component.list("screen")()))
      |gpu.setResolution(40, 8)
      |local function work(n)
      |  local total = 0
      |  for i = 1, n do total = total + (i * 7) % 13 end
      |  return total
      |end
      |local count = 0
      |gpu.set(1, 1, "READY")
      |while true do
      |  local name, _, x = computer.pullSignal(0.05)
      |  count = count + 1
      |  local value = work(20000)
      |  gpu.set(1, 2, "COUNT " .. count .. " " .. value .. "   ")
      |  if name == "touch" then
      |    local disk = component.proxy(computer.getBootAddress())
      |    local handle = disk.open("/computer/note.txt")
      |    local note = handle and disk.read(handle, 64) or "none"
      |    if handle then disk.close(handle) end
      |    gpu.set(1, 3, "TOUCH " .. tostring(x) .. " " .. note)
      |  end
      |end
      |""".stripMargin

  def main(arguments: Array[String]): Unit = {
    val projectRoot = Paths.get(arguments(2)).toRealPath()
    val owner = RuntimeOwner
      .start(
        RuntimeConfig(
          Paths.get(arguments(1)).toAbsolutePath.normalize(),
          Paths.get(arguments(0)).toAbsolutePath.normalize()
        )
      )
      .value
    val session = owner.openProject(projectRoot).value
    val computerId = ComputerId.parse("main").value
    val screenId = ScreenId.parse("main").value
    try {
      // Flight recorder: the computer's calls with their arguments and a delivered signal.
      session.startMachine(computerId).value
      val booted = runUntil(session, ScreenContains(screenId, "READY"))
      withClue(
        s"${session.machineStatus(computerId)}\n${session.readScreen(screenId).value.text}\n" +
          session.readTrace(TraceQuery(limit = 50)).value.events.mkString("\n")
      ) {
        booted.stopReason shouldBe RunStopReason.ConditionSatisfied
      }
      session.send(screenId, UserInput.Touch(5, 1, 0, "tester")).value
      runUntil(session, ScreenContains(screenId, "TOUCH 5")).stopReason shouldBe
        RunStopReason.ConditionSatisfied
      val trace = session.readTrace(TraceQuery(limit = 5000)).value.events
      val ready = trace.find(event => event.kind == "call" && event.name == "gpu.set")
      ready.map(_.arguments) shouldBe Some(Vector("1", "1", "\"READY\""))
      trace.exists(event => event.kind == "signal_queued" && event.name == "touch") shouldBe true
      trace.exists(event => event.kind == "signal_taken" && event.name == "touch") shouldBe true
      trace.exists(event => event.kind == "input" && event.name == "Touch") shouldBe true
      trace.exists(event => event.kind == "started") shouldBe true
      session
        .readTrace(TraceQuery(kinds = Set("signal_taken"), limit = 10))
        .value
        .events
        .map(_.kind)
        .toSet shouldBe Set("signal_taken")
      Console.out.println("OBSERVABILITY_TRACE=true")

      // Meters.
      val metrics = session.machineMetrics().value.find(_.computerId == "main").get
      metrics.slices should be > 0L
      metrics.memoryUsedBytes.exists(_ > 0L) shouldBe true
      metrics.calls.exists(call => call.name == "gpu.set" && call.count > 0L) shouldBe true
      metrics.signalsTaken should be >= 1L
      session.metricsHistory(Some(computerId), 0L).value should not be empty
      Console.out.println("OBSERVABILITY_METRICS=true")

      // Profiler: the hot loop shows up, and the flame graph is written.
      session.startProfile(Some(computerId)).value
      runTicks(session, 60)
      val (report, artifacts) = session.stopProfile(Some("profiles/main")).value
      withClue(
        report.toString + session
          .readTrace(TraceQuery(kinds = Set("note"), limit = 20))
          .value
          .events
      ) { report.samples should be > 0L }
      report.hottest.headOption.map(_._1) shouldBe Some("/computer/main.lua:6")
      // Time inside the kernel (gpu.set, pullSignal) counts at the program's own calling line.
      withClue(report.hottest)(
        report.hottest.forall(_._1.startsWith("/computer/main.lua:")) shouldBe true
      )
      report.folded.exists(
        _.stack.exists(_.startsWith("work (/computer/main.lua:4)"))
      ) shouldBe true
      artifacts.map(_.relativePath).toSet shouldBe Set(
        "profiles/main.folded.txt",
        "profiles/main.svg"
      )
      new String(
        Files.readAllBytes(projectRoot.resolve("artifacts/profiles/main.svg")),
        StandardCharsets.UTF_8
      ) should include("work (/computer/main.lua:4)")
      Console.out.println("OBSERVABILITY_PROFILE=true")

      // Debugger, with the clock running as a person would have it.
      val program = projectRoot.resolve("computer").resolve("main.lua")
      session.setBreakpoints(program, Vector(14)).value shouldBe
        Vector(DebugBreakpoint(program.toString, 14))
      val before = session.debugState(None, 0L).value.pauseSequence
      session.startClock(None).value
      val paused = session.debugState(Some(before), 10000L).value
      paused.paused shouldBe true
      paused.computerId shouldBe Some("main")
      session.clockStatus().value.state shouldBe SimulationClockState.Paused
      val frames = session.debugFrames().value
      withClue(session.readTrace(TraceQuery(kinds = Set("note"), limit = 20)).value.events) {
        frames should not be empty
      }
      frames.head.line shouldBe 14
      frames.head.source shouldBe "/computer/main.lua"
      frames.head.hostPath shouldBe Some(program.toString)
      val locals = session.debugVariables(s"${frames.head.index}:L").value
      val count = locals.find(_.name == "count").get
      count.kind shouldBe "number"
      val countValue = count.value.toInt
      session.setBreakpoints(program, Vector.empty).value shouldBe empty
      session.debugResume("over").value
      val stepped = session.debugState(Some(paused.pauseSequence), 10000L).value
      stepped.paused shouldBe true
      session.debugFrames().value.head.line shouldBe 15
      session.debugVariables("0:L").value.find(_.name == "value").map(_.kind) shouldBe Some(
        "number"
      )
      session.debugResume("continue").value.paused shouldBe false
      session.clockStatus().value.state shouldBe SimulationClockState.Running
      session.pauseClock().value
      countValue should be > 0
      Console.out.println("OBSERVABILITY_DEBUG=true")

      // Deterministic recording.
      val note = projectRoot.resolve("computer").resolve("note.txt")
      Files.write(note, "before".getBytes(StandardCharsets.UTF_8))
      session.setExecutionMode(ExecutionMode.Deterministic(20)).value
      val recordingName = SnapshotName.parse("lagged").value
      session.startRecording(recordingName).value.active shouldBe true
      val recordingFrom = session.readTrace(TraceQuery(limit = 1)).value.latestSequence
      runTicks(session, 40)
      session.send(screenId, UserInput.Touch(9, 1, 0, "tester")).value
      session.setExecutionMode(ExecutionMode.Deterministic(7)).value
      runTicks(session, 60)
      val recorded = session.stopRecording().value
      recorded.ticks should be >= 100L
      recorded.inputs shouldBe 2
      val recordedScreen = session.readScreen(screenId).value.text
      Console.out.println("OBSERVABILITY_RECORD=true")

      // Replay reproduces every tick and ends on the same screen.
      val replayFrom = session.readTrace(TraceQuery(limit = 1)).value.latestSequence
      val replayed = session
        .replay(ReplayRequest(recordingName, None, 60.seconds))
        .value
      def firstEvents(after: Long) =
        session
          .readTrace(TraceQuery(afterSequence = after, limit = 12))
          .value
          .events
          .map(e => s"${e.tick} ${e.kind} ${e.name} ${e.arguments} ${e.results} ${e.note}")
          .mkString("\n  ", "\n  ", "")
      withClue(
        s"$replayed\nrecorded:${firstEvents(recordingFrom)}\nreplayed:${firstEvents(replayFrom)}\n"
      ) {
        replayed.divergedAtTick shouldBe None
        replayed.replayedTicks shouldBe recorded.ticks
      }
      session.readScreen(screenId).value.text shouldBe recordedScreen
      val bisected = session
        .replay(ReplayRequest(recordingName, Some(ScreenContains(screenId, "TOUCH 9")), 60.seconds))
        .value
      bisected.divergedAtTick shouldBe None
      bisected.replayedTicks should (be > 40L and be < recorded.ticks)
      Console.out.println("OBSERVABILITY_REPLAY=true")

      // A host file the program reads changed since the recording: replay finds the tick.
      Files.write(note, "after".getBytes(StandardCharsets.UTF_8))
      val diverged = session.replay(ReplayRequest(recordingName, None, 60.seconds)).value
      withClue(diverged.toString) {
        diverged.divergedAtTick shouldBe Some(bisected.stoppedAtTick)
        diverged.divergence.exists(_.contains("calls, signals or inputs differ")) shouldBe true
      }
      Console.out.println("OBSERVABILITY_DIVERGE=true")

      // Lag sweep from the recording's save state.
      val sweep = session
        .sweep(
          SweepRequest(
            recordingName,
            ScreenContains(screenId, "COUNT 150 "),
            runs = 3,
            maxTicks = 600,
            minServerTps = 5,
            maxServerTps = 20,
            seed = 42L,
            maxWallTime = 60.seconds
          )
        )
        .value
      sweep.runs.map(_.seed) shouldBe Vector(42L, 43L, 44L)
      withClue(sweep.toString)(sweep.runs.foreach(_.passed shouldBe true))
      sweep.failingSeeds shouldBe empty
      Console.out.println("OBSERVABILITY_SWEEP=true")

      // Two save states with their host-folder files, compared by device names and screen lines.
      session.setExecutionMode(ExecutionMode.Threaded).value
      val firstState = SnapshotName.parse("diff-a").value
      val secondState = SnapshotName.parse("diff-b").value
      Files.write(note, "first".getBytes(StandardCharsets.UTF_8))
      session.saveSnapshot(SnapshotRequest(firstState, HostDiskSnapshotPolicy.Copy)).value
      runTicks(session, 20)
      Files.write(note, "second".getBytes(StandardCharsets.UTF_8))
      session.saveSnapshot(SnapshotRequest(secondState, HostDiskSnapshotPolicy.Copy)).value
      val comparison = session.diffSnapshots(firstState, secondState).value
      val changed = comparison.changes.map(change => change.path -> change).toMap
      withClue(comparison.changes.map(_.path)) {
        changed("screen main/text line 2").after.exists(_.startsWith("COUNT ")) shouldBe true
        changed.keys.exists(path =>
          path.startsWith("host disk files/") && path.endsWith("computer/note.txt")
        ) shouldBe true
        changed.keys.exists(_.startsWith("computer main/")) shouldBe true
        changed("world time (ticks)").after.forall(_.forall(_.isDigit)) shouldBe true
      }
      comparison.toTick should be > comparison.fromTick
      comparison.notes shouldBe empty
      Console.out.println("OBSERVABILITY_DIFF=true")

      // The service JVM's own flight recording.
      session.startJvmRecording().value
      runTicks(session, 20)
      session.stopJvmRecording("jvm/run.jfr").value.size should be > 0L
      Console.out.println("OBSERVABILITY_JFR=true")

      // A diagnostics bundle carries the flight recorder and the meters.
      session
        .diagnostics(DiagnosticRequest("diagnostics/run.zip"))
        .value
        .entries should contain allOf (
        "trace.txt",
        "metrics.txt"
      )
      Console.out.println("OBSERVABILITY_BUNDLE=true")

      // The harness layer itself never faulted inside the computer.
      session
        .readTrace(TraceQuery(kinds = Set("note"), nameContains = Some("instrumentation")))
        .value
        .events shouldBe empty
    } finally {
      session.close()
      owner.close()
    }
  }

  private def runUntil(session: HarnessSession, condition: StopCondition): RunResult =
    session.run(RunRequest(condition, maxTicks = 2000, maxWallTime = 20.seconds)).value

  private def runTicks(session: HarnessSession, ticks: Int): Unit =
    session
      .run(
        RunRequest(
          ScreenContains(ScreenId.parse("main").value, "NEVER-SHOWN"),
          maxTicks = ticks,
          maxWallTime = 30.seconds
        )
      )
      .value
      .elapsedTicks shouldBe ticks
}
