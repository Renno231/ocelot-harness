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

final class RedstoneInputSpec extends AnyFunSuite with Matchers {
  test("redstone input reaches the running computer and wakes a stopped one at its threshold") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-redstone-input-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      Files.createDirectories(projectDirectory)
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.RedstoneInputProbe",
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
      withClue(s"Redstone input stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("REDSTONE_BELOW_THRESHOLD_STAYS_OFF=true")
        stdout should include("REDSTONE_THRESHOLD_WAKES=true")
        stdout should include("REDSTONE_SIGNALS_COMPUTER=true")
        stdout should include("REDSTONE_BAD_REQUESTS_REFUSED=true")
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

private[runtime] object RedstoneInputProbe extends EitherValues with Matchers {
  // First boot: sets the wake threshold to 4 and shuts down. Later boots show what woke them and
  // each redstone change.
  private val Program =
    """local gpu = component.proxy(component.list("gpu")())
      |gpu.bind((component.list("screen")()))
      |local rs = component.proxy(component.list("redstone")())
      |local fs = component.proxy(computer.getBootAddress())
      |if not fs.exists("booted") then
      |  local h = fs.open("booted", "w") fs.write(h, "1") fs.close(h)
      |  rs.setWakeThreshold(4)
      |  computer.shutdown()
      |end
      |gpu.set(1, 1, "WOKEN back=" .. rs.getInput(2))
      |local line = 2
      |while true do
      |  local name, _, side, old, new = computer.pullSignal()
      |  if name == "redstone_changed" then
      |    gpu.set(1, line, ("CHANGED %d %d>%d"):format(side, old, new))
      |    line = line + 1
      |  end
      |end
      |""".stripMargin

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 3, "expected native, runtime, and project directories")
    val projectRoot = Paths.get(arguments(2)).toRealPath()
    write(projectRoot.resolve("pc").resolve("init.lua"), Program)
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

    try {
      val session = owner.openProject(projectRoot).value
      try {
        session.startMachine(computer).value
        runUntil(session, MachineReaches(computer, MachineState.Stopped))
        session.setRedstoneInput(computer, "back", 3).value shouldBe
          RedstoneInputResult("pc", "back", 3)
        session
          .run(RunRequest(ScreenContains(screen, "WOKEN"), 40, 10.seconds))
          .value
          .machines
          .find(_.id == computer)
          .map(_.state) shouldBe Some(MachineState.Stopped)
        Console.out.println("REDSTONE_BELOW_THRESHOLD_STAYS_OFF=true")

        session.setRedstoneInput(computer, "2", 4).value shouldBe RedstoneInputResult(
          "pc",
          "back",
          4
        )
        runUntil(session, ScreenContains(screen, "WOKEN back=4"))
        Console.out.println("REDSTONE_THRESHOLD_WAKES=true")

        session.setRedstoneInput(computer, "LEFT", 15).value shouldBe
          RedstoneInputResult("pc", "left", 15)
        runUntil(session, ScreenContains(screen, "CHANGED 5 0>15"))
        Console.out.println("REDSTONE_SIGNALS_COMPUTER=true")

        session.setRedstoneInput(computer, "sideways", 1).left.value.code shouldBe "invalid_input"
        session.setRedstoneInput(computer, "6", 1).left.value.code shouldBe "invalid_input"
        session.setRedstoneInput(computer, "back", 16).left.value.code shouldBe "invalid_input"
        session.setRedstoneInput(computer, "back", -1).left.value.code shouldBe "invalid_input"
        val bare = ComputerId.parse("bare").value
        session.setRedstoneInput(bare, "back", 1).left.value.message should include(
          "no redstone card"
        )
        session
          .setRedstoneInput(ComputerId.parse("missing").value, "back", 1)
          .left
          .value
          .code shouldBe "unknown_computer"
        Console.out.println("REDSTONE_BAD_REQUESTS_REFUSED=true")
      } finally session.close()
    } finally owner.close()
  }

  private val Manifest =
    """schemaVersion = 2
      |project { id = "redstone-input" }
      |workspace { kind = "manifest" }
      |runtime { clock { autoStart = false } }
      |devices {
      |  pc {
      |    kind = "computer", tier = 3
      |    inventory = [
      |      { slot = "gpu", kind = "gpu", tier = 3 },
      |      { slot = "card-1", kind = "redstone", tier = 1 },
      |      { slot = "memory-1", kind = "memory", tier = 3.5 },
      |      { slot = "disk-1", kind = "managed-hdd", tier = 1, id = "pc", label = "pc", source = "./pc", access = "read-write" },
      |      { slot = "cpu", kind = "cpu", tier = 3 },
      |      { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
      |    ]
      |  }
      |  pc-screen { kind = "screen", tier = 3, keyboard = true }
      |  bare {
      |    kind = "computer", tier = 1
      |    inventory = [
      |      { slot = "gpu", kind = "gpu", tier = 1 },
      |      { slot = "memory-1", kind = "memory", tier = 1 },
      |      { slot = "cpu", kind = "cpu", tier = 1 },
      |      { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
      |    ]
      |  }
      |}
      |connections = [
      |  { from = "pc:network", to = "pc-screen:network" }
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
