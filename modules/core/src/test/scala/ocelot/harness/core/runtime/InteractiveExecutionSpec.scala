package ocelot.harness.core.runtime

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.util.concurrent.{Executors, TimeUnit}
import java.util.zip.ZipInputStream

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.artifact.{ScreenArtifactFormat, ScreenArtifactRequest}
import ocelot.harness.core.project.{ComputerId, ScreenId}
import ocelot.harness.core.workspace._

final class InteractiveExecutionSpec extends AnyFunSuite with Matchers {
  test("fixture copies isolate host edits between project runs") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-fixture-isolation-")
    val first = workDirectory.resolve("first")
    val second = workDirectory.resolve("second")
    try {
      copyDirectory(fixtureDirectory, first)
      copyDirectory(fixtureDirectory, second)
      val firstProgram = first.resolve("computer").resolve("main.lua")
      val secondProgram = second.resolve("computer").resolve("main.lua")
      Files.write(
        firstProgram,
        readText(firstProgram).replace("READY", "ISOLATED").getBytes(StandardCharsets.UTF_8)
      )

      readText(firstProgram) should include("ISOLATED")
      readText(secondProgram) should include("READY")
      readText(fixtureDirectory.resolve("computer").resolve("main.lua")) should include("READY")
    } finally deleteRecursively(workDirectory)
  }

  test("a host-backed project can boot, accept input, expose immutable state, and reboot edits") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-interactive-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      copyDirectory(fixtureDirectory, projectDirectory)
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.InteractiveExecutionProbe",
        workDirectory.resolve("native-libraries").toString,
        workDirectory.resolve("runtime").toString,
        projectDirectory.toString
      )
        .redirectOutput(stdoutFile.toFile)
        .redirectError(stderrFile.toFile)
        .start()

      val exited = process.waitFor(90L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }

      val stdout = readText(stdoutFile)
      val stderr = readText(stderrFile)
      withClue(s"Interactive probe stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("INTERACTIVE_READY=true")
        stdout should include("INTERACTIVE_PNG=true")
        stdout should include("INTERACTIVE_SNAPSHOT=true")
        stdout should include("INTERACTIVE_RESTORE_NON_DESTRUCTIVE=true")
        stdout should include("INTERACTIVE_TOUCH=true")
        stdout should include("INTERACTIVE_PASTE=true")
        stdout should include("INTERACTIVE_DRAG=true")
        stdout should include("INTERACTIVE_KEY=true")
        stdout should include("INTERACTIVE_HOST_EDIT=true")
        stdout should include("INTERACTIVE_BOUNDS=true")
        stdout should include("INTERACTIVE_IMMUTABLE=true")
        stdout should include("INTERACTIVE_EVENTS_BOUNDED=true")
        stdout should include("INTERACTIVE_DIAGNOSTICS=true")
        stdout should include("INTERACTIVE_CLEANUP=true")
      }
    } finally deleteRecursively(workDirectory)
  }

  private def fixtureDirectory: Path = {
    val candidates = Vector(
      Paths.get("fixtures", "vertical-spike"),
      Paths.get("..", "..", "fixtures", "vertical-spike")
    ).map(_.toAbsolutePath.normalize())
    candidates
      .find(Files.isDirectory(_))
      .getOrElse(
        fail(s"vertical-spike fixture not found in ${candidates.mkString(", ")}")
      )
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

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  private def javaExecutable: Path = {
    val executable =
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }
}

private[runtime] object InteractiveExecutionProbe extends EitherValues with Matchers {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 3, "expected native, runtime, and project directories")
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
      session.startMachine(computerId).value.state shouldBe MachineState.Running
      val ready = runUntil(session, ScreenContains(screenId, "READY"))
      ready.stopReason shouldBe RunStopReason.ConditionSatisfied
      Console.out.println("INTERACTIVE_READY=true")

      val textArtifact = session
        .captureScreen(
          screenId,
          ScreenArtifactRequest("screens/main.txt", ScreenArtifactFormat.Text)
        )
        .value
      textArtifact.mediaType shouldBe "text/plain; charset=utf-8"
      readText(projectRoot.resolve("artifacts/screens/main.txt")) should include("READY")
      val cellsArtifact = session
        .captureScreen(
          screenId,
          ScreenArtifactRequest("screens/main.cells.json", ScreenArtifactFormat.CellsJson)
        )
        .value
      cellsArtifact.mediaType shouldBe "application/json"
      readText(projectRoot.resolve("artifacts/screens/main.cells.json")) should include(
        "\"width\":40"
      )
      val png = session
        .captureScreen(
          screenId,
          ScreenArtifactRequest("screens/main.png", ScreenArtifactFormat.Png)
        )
        .value
      png.mediaType shouldBe "image/png"
      png.size should be > 0L
      png.sha256 should fullyMatch regex "[0-9a-f]{64}"
      Files.isRegularFile(projectRoot.resolve("artifacts/screens/main.png")) shouldBe true
      Console.out.println("INTERACTIVE_PNG=true")

      val readyDescription = session.describe()
      val readyScreen = session.readScreen(screenId).value
      val readyName = SnapshotName.parse("ready").value
      val incompatibleName = SnapshotName.parse("incompatible").value
      val corruptName = SnapshotName.parse("corrupt").value
      val copiedName = SnapshotName.parse("copied").value
      val tooSmallName = SnapshotName.parse("too-small").value
      val readySnapshot = session.saveSnapshot(SnapshotRequest(readyName)).value
      readySnapshot.hostDisks shouldBe HostDiskSnapshotPolicy.ReferenceOnly
      readySnapshot.captureTick shouldBe readyScreen.captureTick
      readySnapshot.sha256 should fullyMatch regex "[0-9a-f]{64}"
      session
        .saveSnapshot(SnapshotRequest(tooSmallName, maxBytes = readySnapshot.size))
        .left
        .value
        .code shouldBe "snapshot_limit_exceeded"
      Files.exists(projectRoot.resolve("snapshots").resolve("too-small")) shouldBe false
      session.saveSnapshot(SnapshotRequest(incompatibleName)).value
      session.saveSnapshot(SnapshotRequest(corruptName)).value
      val copiedSnapshot = session
        .saveSnapshot(
          SnapshotRequest(copiedName, hostDisks = HostDiskSnapshotPolicy.Copy)
        )
        .value
      copiedSnapshot.hostDisks shouldBe HostDiskSnapshotPolicy.Copy
      copiedSnapshot.copiedDiskBytes should be > 0L
      Files.isRegularFile(
        projectRoot
          .resolve("snapshots")
          .resolve("copied")
          .resolve("disks")
          .resolve("main")
          .resolve("project")
          .resolve("computer")
          .resolve("main.lua")
      ) shouldBe true
      Console.out.println("INTERACTIVE_SNAPSHOT=true")

      session
        .run(
          RunRequest(
            ScreenContains(screenId, null),
            maxTicks = 1,
            maxWallTime = 1.second
          )
        )
        .left
        .value
        .code shouldBe "invalid_run_request"
      session.send(screenId, UserInput.TypeText(null)).left.value.code shouldBe "invalid_input"

      val original = session.readScreen(screenId).value
      original.text should include("READY")
      original.text should include("UNICODE:λ")
      original.width shouldBe 40
      original.height shouldBe 8
      original.cells.size shouldBe original.width * original.height
      original.palette.size shouldBe 16
      original.colorDepth shouldBe 8
      original.runtimeAddress.exists(_.nonEmpty) shouldBe true
      original.precisionMode shouldBe false
      original.cells.head.foreground shouldBe 0xffffff
      original.cells.head.background shouldBe 0x000000

      session
        .send(screenId, UserInput.Touch(1.5, 1.0))
        .left
        .value
        .code shouldBe "input_unavailable"
      session.send(screenId, UserInput.Touch(1, 1)).value.eventsSent shouldBe 1
      runUntil(session, ScreenContains(screenId, "TOUCHED")).stopReason shouldBe
        RunStopReason.ConditionSatisfied
      Console.out.println("INTERACTIVE_TOUCH=true")

      val incompatibleMetadata = projectRoot
        .resolve("snapshots")
        .resolve("incompatible")
        .resolve("metadata.conf")
      Files.write(
        incompatibleMetadata,
        readText(incompatibleMetadata)
          .replace("formatVersion=1", "formatVersion=99")
          .getBytes(StandardCharsets.UTF_8)
      )
      val incompatibleError = session.loadSnapshot(incompatibleName).left.value
      withClue(incompatibleError.message) {
        incompatibleError.code shouldBe "snapshot_incompatible"
      }
      session.readScreen(screenId).value.text should include("TOUCHED")
      Files.write(
        projectRoot.resolve("snapshots").resolve("corrupt").resolve("workspace.nbt.gz"),
        Array[Byte](1, 2, 3)
      )
      session.loadSnapshot(corruptName).left.value.code shouldBe "snapshot_corrupt"
      session.readScreen(screenId).value.text should include("TOUCHED")
      session.loadSnapshot(readyName).value shouldBe readyDescription
      val restored = session.readScreen(screenId).value
      restored.text should include("READY")
      restored.text should not include "TOUCHED"
      restored.captureTick shouldBe readyScreen.captureTick
      Console.out.println("INTERACTIVE_RESTORE_NON_DESTRUCTIVE=true")

      session.send(screenId, UserInput.Paste("PASTED-VALUE")).value.eventsSent shouldBe 1
      runUntil(session, ScreenContains(screenId, "PASTED-VALUE")).stopReason shouldBe
        RunStopReason.ConditionSatisfied
      Console.out.println("INTERACTIVE_PASTE=true")

      session.send(screenId, UserInput.Drag(1, 1, 4, 2, steps = 3)).value.eventsSent shouldBe 5
      runUntil(session, ScreenContains(screenId, "DRAGGED")).stopReason shouldBe
        RunStopReason.ConditionSatisfied
      Console.out.println("INTERACTIVE_DRAG=true")

      session.send(screenId, UserInput.TypeText("a")).value.eventsSent shouldBe 2
      runUntil(session, ScreenContains(screenId, "KEY:a:30")).stopReason shouldBe
        RunStopReason.ConditionSatisfied
      Console.out.println("INTERACTIVE_KEY=true")

      val changedSnapshot = session.readScreen(screenId).value
      changedSnapshot.revision should be > original.revision
      original.text should include("READY")
      original.text should not include "TOUCHED"
      Console.out.println("INTERACTIVE_IMMUTABLE=true")

      val mainFile = projectRoot.resolve("computer").resolve("main.lua")
      val changedProgram = readText(mainFile).replace(
        "local marker = \"READY\"",
        "local marker = \"CHANGED\""
      )
      Files.write(mainFile, changedProgram.getBytes(StandardCharsets.UTF_8))
      session.resetMachine(computerId).value.state shouldBe MachineState.Running
      runUntil(session, ScreenContains(screenId, "CHANGED")).stopReason shouldBe
        RunStopReason.ConditionSatisfied
      Console.out.println("INTERACTIVE_HOST_EDIT=true")

      val tickTimeout = session
        .run(
          RunRequest(
            ScreenContains(screenId, "NEVER"),
            maxTicks = 1,
            maxWallTime = 5.seconds
          )
        )
        .value
      tickTimeout.stopReason shouldBe RunStopReason.ConditionTimedOut("max_ticks")
      tickTimeout.elapsedTicks shouldBe 1
      tickTimeout.elapsedWallTime should be >= Duration.Zero
      tickTimeout.screenRevisions(screenId) shouldBe tickTimeout.screens(screenId).revision
      tickTimeout.screens(screenId).text should include("CHANGED")
      tickTimeout.machines.map(_.id) should contain(computerId)
      tickTimeout.timeline should not be empty
      tickTimeout.timeline.size should be <= 256

      val wallTimeout = session
        .run(
          RunRequest(
            ScreenContains(screenId, "NEVER"),
            maxTicks = 100000,
            maxWallTime = 2.millis
          )
        )
        .value
      wallTimeout.stopReason shouldBe RunStopReason.ConditionTimedOut("max_wall_time")

      val diagnosticTimeout = session
        .run(
          RunRequest(
            ScreenContains(screenId, "NEVER"),
            maxTicks = 300,
            maxWallTime = 5.seconds
          )
        )
        .value
      diagnosticTimeout.timeline.size shouldBe 256
      diagnosticTimeout.timelineDroppedCount should be > 0L

      val cancellation = RunCancellation.create()
      val scheduler = Executors.newSingleThreadScheduledExecutor()
      val cancellationStartedAt = System.nanoTime()
      try {
        scheduler.schedule(
          new Runnable {
            override def run(): Unit = cancellation.cancel()
          },
          50L,
          TimeUnit.MILLISECONDS
        )
        val cancelled = session
          .run(
            RunRequest(
              ScreenContains(screenId, "NEVER"),
              maxTicks = 100,
              maxWallTime = 5.seconds,
              pace = TickPace.FixedDelay(5.seconds),
              cancellation = cancellation
            )
          )
          .value
        cancelled.stopReason shouldBe RunStopReason.Cancelled
        (System.nanoTime() - cancellationStartedAt).nanos should be < 1.second
      } finally {
        scheduler.shutdownNow()
        scheduler.awaitTermination(5L, TimeUnit.SECONDS)
      }
      session.readScreen(screenId).value.text should include("CHANGED")
      Console.out.println("INTERACTIVE_BOUNDS=true")

      val events = session.recentEvents().value
      events.events.size should be <= 16
      events.droppedCount should be > 0L
      Console.out.println("INTERACTIVE_EVENTS_BOUNDED=true")

      val diagnostics = session
        .diagnostics(
          DiagnosticRequest(
            "diagnostics/interactive.zip",
            failure = Some(
              DiagnosticFailure(
                "probe_failure",
                s"probe failed at $projectRoot",
                Some(s"trace from $projectRoot")
              )
            )
          )
        )
        .value
      diagnostics.artifact.mediaType shouldBe "application/zip"
      diagnostics.artifact.size should be > 0L
      diagnostics.artifact.size should be <= 16L * 1024L * 1024L
      Vector(
        "manifest.conf",
        "versions.txt",
        "runtime.txt",
        "topology.txt",
        "events.txt",
        "timeline.txt",
        "error.txt",
        "screens/main.txt",
        "screens/main.cells.json",
        "screens/main.png",
        "checksums.txt"
      ).foreach(entry => diagnostics.entries should contain(entry))
      val diagnosticBytes = Files.readAllBytes(
        projectRoot.resolve("artifacts").resolve("diagnostics").resolve("interactive.zip")
      )
      val diagnosticEntries = readZip(diagnosticBytes)
      val diagnosticRuntime = new String(
        diagnosticEntries("runtime.txt"),
        StandardCharsets.UTF_8
      )
      diagnosticRuntime should include("clockState=paused")
      diagnosticRuntime should include("clockTargetTps=20")
      val diagnosticManifest = new String(
        diagnosticEntries("manifest.conf"),
        StandardCharsets.UTF_8
      )
      diagnosticManifest should not include projectRoot.toString
      diagnosticManifest should include("<redacted>")
      val diagnosticError = new String(
        diagnosticEntries("error.txt"),
        StandardCharsets.UTF_8
      )
      diagnosticError should include("code=probe_failure")
      diagnosticError should include("<project-root>")
      diagnosticError should not include projectRoot.toString
      val declaredChecksums = new String(
        diagnosticEntries("checksums.txt"),
        StandardCharsets.UTF_8
      ).linesIterator
        .filter(_.nonEmpty)
        .map { line =>
          val separator = line.indexOf("  ")
          require(separator > 0, s"invalid checksum declaration: $line")
          line.substring(separator + 2) -> line.substring(0, separator)
        }
        .toMap
      diagnosticEntries.filterNot(_._1 == "checksums.txt").foreach { case (name, bytes) =>
        declaredChecksums(name) shouldBe sha256(bytes)
      }
      Console.out.println("INTERACTIVE_DIAGNOSTICS=true")

      session.stopMachine(computerId).value.state shouldBe MachineState.Stopped
    } finally {
      session.close()
      session.readScreen(screenId).left.value.code shouldBe "session_closed"
      owner.close()
      Console.out.println("INTERACTIVE_CLEANUP=true")
    }
  }

  private def runUntil(session: HarnessSession, condition: StopCondition): RunResult =
    session
      .run(
        RunRequest(
          condition,
          maxTicks = 2000,
          maxWallTime = 10.seconds
        )
      )
      .value

  private def readText(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def readZip(bytes: Array[Byte]): Map[String, Array[Byte]] = {
    val input = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)
    val entries = Map.newBuilder[String, Array[Byte]]
    try {
      var entry = input.getNextEntry
      while (entry != null) {
        val output = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        var read = input.read(buffer)
        while (read >= 0) {
          if (read > 0) output.write(buffer, 0, read)
          read = input.read(buffer)
        }
        entries += entry.getName -> output.toByteArray
        input.closeEntry()
        entry = input.getNextEntry
      }
    } finally input.close()
    entries.result()
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString
}
