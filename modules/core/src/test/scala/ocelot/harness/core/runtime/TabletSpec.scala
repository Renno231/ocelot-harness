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

final class TabletSpec extends AnyFunSuite with Matchers {
  test("a tablet computer has the tablet component, through a snapshot, and others do not") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-tablet-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      Files.createDirectories(projectDirectory)
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.TabletProbe",
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
      withClue(s"Tablet stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("TABLET_COMPONENT=true")
        stdout should include("DESK_HAS_NO_TABLET=true")
        stdout should include("TABLET_AFTER_SNAPSHOT=true")
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

private[runtime] object TabletProbe extends EitherValues with Matchers {
  // Shows whether the computer has a tablet and what it reads, at boot and on each key.
  private val Program =
    """local gpu = component.proxy(component.list("gpu")())
      |gpu.bind((component.list("screen")()))
      |local function show(line, tag)
      |  local address = component.list("tablet")()
      |  if address then
      |    local tablet = component.proxy(address)
      |    gpu.set(1, line, ("%s tablet pitch=%s"):format(tag, tablet.getPitch()))
      |  else
      |    gpu.set(1, line, tag .. " no tablet")
      |  end
      |end
      |show(1, "BOOT")
      |local line = 2
      |while true do
      |  if computer.pullSignal() == "key_down" then
      |    show(line, "KEY")
      |    line = line + 1
      |  end
      |end
      |""".stripMargin

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 3, "expected native, runtime, and project directories")
    val projectRoot = Paths.get(arguments(2)).toRealPath()
    write(projectRoot.resolve("pad").resolve("init.lua"), Program)
    write(projectRoot.resolve("desk").resolve("init.lua"), Program)
    write(projectRoot.resolve(ProjectLoader.ManifestFileName), Manifest)

    val owner = RuntimeOwner
      .start(
        RuntimeConfig(
          Paths.get(arguments(1)).toAbsolutePath.normalize(),
          Paths.get(arguments(0)).toAbsolutePath.normalize()
        )
      )
      .value
    val pad = ComputerId.parse("pad").value
    val desk = ComputerId.parse("desk").value
    val padScreen = ScreenId.parse("pad-screen").value
    val deskScreen = ScreenId.parse("desk-screen").value

    try {
      val session = owner.openProject(projectRoot).value
      try {
        session.startMachine(pad).value
        session.startMachine(desk).value
        runUntil(session, padScreen, ScreenContains(padScreen, "BOOT tablet pitch=0"))
        Console.out.println("TABLET_COMPONENT=true")
        runUntil(session, deskScreen, ScreenContains(deskScreen, "BOOT no tablet"))
        Console.out.println("DESK_HAS_NO_TABLET=true")

        val name = SnapshotName.parse("tablet").value
        session.saveSnapshot(SnapshotRequest(name)).value
        session.loadSnapshot(name).value
        session.send(padScreen, UserInput.TypeText("x")).value
        runUntil(session, padScreen, ScreenContains(padScreen, "KEY tablet pitch=0"))
        Console.out.println("TABLET_AFTER_SNAPSHOT=true")
      } finally session.close()
    } finally owner.close()
  }

  private val Manifest =
    """schemaVersion = 2
      |project { id = "tablet" }
      |workspace { kind = "manifest" }
      |runtime { clock { autoStart = false } }
      |devices {
      |  pad {
      |    kind = "computer", tier = 2, tablet = true
      |    inventory = [
      |      { slot = "gpu", kind = "gpu", tier = 2 },
      |      { slot = "memory-1", kind = "memory", tier = 2 },
      |      { slot = "disk-1", kind = "managed-hdd", tier = 1, id = "pad", label = "pad", source = "./pad", access = "read-write" },
      |      { slot = "cpu", kind = "cpu", tier = 2 },
      |      { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
      |    ]
      |  }
      |  pad-screen { kind = "screen", tier = 2, keyboard = true }
      |  desk {
      |    kind = "computer", tier = 2
      |    inventory = [
      |      { slot = "gpu", kind = "gpu", tier = 2 },
      |      { slot = "memory-1", kind = "memory", tier = 2 },
      |      { slot = "disk-1", kind = "managed-hdd", tier = 1, id = "desk", label = "desk", source = "./desk", access = "read-write" },
      |      { slot = "cpu", kind = "cpu", tier = 2 },
      |      { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
      |    ]
      |  }
      |  desk-screen { kind = "screen", tier = 2 }
      |}
      |connections = [
      |  { from = "pad:network", to = "pad-screen:network" },
      |  { from = "desk:network", to = "desk-screen:network" }
      |]
      |""".stripMargin

  private def runUntil(
      session: HarnessSession,
      screen: ScreenId,
      condition: StopCondition
  ): Unit = {
    val result = session.run(RunRequest(condition, maxTicks = 2000, maxWallTime = 20.seconds)).value
    withClue(
      s"screen: ${session.readScreen(screen).map(_.text.trim)}\n" +
        s"run: $result\nevents: ${session.recentEvents().map(_.events.takeRight(10))}\n"
    )(result.stopReason shouldBe RunStopReason.ConditionSatisfied)
  }

  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }
}
