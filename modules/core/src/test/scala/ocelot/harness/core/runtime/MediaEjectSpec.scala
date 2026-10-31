package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.project.{ComputerId, ProjectLoader, ScreenId}
import ocelot.harness.core.workspace._

final class MediaEjectSpec extends AnyFunSuite with Matchers {
  test(
    "ejecting a floppy removes it from the running computer and blocks snapshots until one loads"
  ) {
    val workDirectory = Files.createTempDirectory("ocelot-harness-media-eject-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      Files.createDirectories(projectDirectory)
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.MediaEjectProbe",
        workDirectory.resolve("native-libraries").toString,
        workDirectory.resolve("runtime").toString,
        projectDirectory.toString
      ).redirectOutput(stdoutFile.toFile).redirectError(stderrFile.toFile).start()

      val exited = process.waitFor(120L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      val stdout = readText(stdoutFile)
      val stderr = readText(stderrFile)
      withClue(s"Media eject stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("EJECT_SIGNALS_COMPUTER=true")
        stdout should include("EJECT_EMPTY_AND_UNKNOWN=true")
        stdout should include("EJECT_BLOCKS_SNAPSHOT=true")
        stdout should include("EJECT_SNAPSHOT_RESTORES_MEDIA=true")
      }
    } finally deleteRecursively(workDirectory)
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

private[runtime] object MediaEjectProbe extends EitherValues with Matchers {
  // Shows whether a floppy is present, then marks the screen when that floppy's component goes.
  private val Program =
    """local gpu = component.proxy(component.list("gpu")())
      |gpu.bind((component.list("screen")()))
      |local floppy
      |for address in component.list("filesystem") do
      |  if address ~= computer.getBootAddress() and address ~= computer.tmpAddress() then floppy = address end
      |end
      |gpu.set(1, 1, "WAITING " .. tostring(floppy ~= nil))
      |while true do
      |  local name, address = computer.pullSignal()
      |  if name == "component_removed" and address == floppy then gpu.set(1, 2, "REMOVED") end
      |end
      |""".stripMargin

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 3, "expected native, runtime, and project directories")
    val projectRoot = Paths.get(arguments(2)).toRealPath()
    write(projectRoot.resolve("pc").resolve("init.lua"), Program)
    write(projectRoot.resolve("floppy").resolve("data.txt"), "floppy")
    write(projectRoot.resolve(ProjectLoader.ManifestFileName), Manifest)

    val owner = RuntimeOwner
      .start(
        RuntimeConfig(
          Paths.get(arguments(1)).toAbsolutePath.normalize(),
          Paths.get(arguments(0)).toAbsolutePath.normalize()
        )
      )
      .value
    val computer = ComputerId.parse("pc").value
    val screen = ScreenId.parse("pc-screen").value
    val before = SnapshotName.parse("before").value

    try {
      val session = owner.openProject(projectRoot).value
      try {
        session.startMachine(computer).value
        runUntil(session, ScreenContains(screen, "WAITING true"))
        session.saveSnapshot(SnapshotRequest(before)).value
        session.ejectMedia("drive").value shouldBe MediaEjectResult("drive", ejected = true)
        runUntil(session, ScreenContains(screen, "REMOVED"))
        Console.out.println("EJECT_SIGNALS_COMPUTER=true")

        session.ejectMedia("drive").value shouldBe MediaEjectResult("drive", ejected = false)
        session.ejectMedia("pc").left.value.code shouldBe "unknown_drive"
        session.ejectMedia("missing").left.value.code shouldBe "unknown_drive"
        Console.out.println("EJECT_EMPTY_AND_UNKNOWN=true")

        val refused = session.saveSnapshot(SnapshotRequest(SnapshotName.parse("after").value))
        refused.left.value.code shouldBe "snapshot_invalid"
        refused.left.value.message should include("ejected")
        Console.out.println("EJECT_BLOCKS_SNAPSHOT=true")

        session.loadSnapshot(before).value
        session.saveSnapshot(SnapshotRequest(SnapshotName.parse("again").value)).value
        session.ejectMedia("drive").value shouldBe MediaEjectResult("drive", ejected = true)
        Console.out.println("EJECT_SNAPSHOT_RESTORES_MEDIA=true")
      } finally session.close()
    } finally owner.close()
  }

  private val Manifest =
    """schemaVersion = 2
      |project { id = "media-eject" }
      |workspace { kind = "manifest" }
      |runtime { clock { autoStart = false } }
      |devices {
      |  pc {
      |    kind = "computer", tier = 3
      |    inventory = [
      |      { slot = "gpu", kind = "gpu", tier = 3 },
      |      { slot = "memory-1", kind = "memory", tier = 3.5 },
      |      { slot = "disk-1", kind = "managed-hdd", tier = 1, id = "pc", label = "pc", source = "./pc", access = "read-write" },
      |      { slot = "cpu", kind = "cpu", tier = 3 },
      |      { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
      |    ]
      |  }
      |  pc-screen { kind = "screen", tier = 3, keyboard = true }
      |  drive {
      |    kind = "disk-drive"
      |    inventory = [{ slot = "floppy", kind = "managed-floppy", id = "media", label = "media", source = "./floppy", access = "read-write" }]
      |  }
      |}
      |connections = [
      |  { from = "pc:network", to = "pc-screen:network" },
      |  { from = "pc:network", to = "drive:network" }
      |]
      |""".stripMargin

  private def runUntil(session: HarnessSession, condition: StopCondition): RunResult = {
    val result = session.run(RunRequest(condition, maxTicks = 2000, maxWallTime = 20.seconds)).value
    withClue(
      s"screen: ${session.readScreen(ScreenId.parse("pc-screen").value).map(_.text.trim)}\n" +
        s"machines: ${result}\nevents: ${session.recentEvents().map(_.events.takeRight(10))}\n"
    )(result.stopReason shouldBe RunStopReason.ConditionSatisfied)
    result
  }

  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }
}
