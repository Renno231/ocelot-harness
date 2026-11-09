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

final class KeyDeliverySpec extends AnyFunSuite with Matchers {
  test("typed text reaches the computer whole, however far it outruns the signal queue") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-key-delivery-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      Files.createDirectories(projectDirectory)
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.KeyDeliveryProbe",
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
      withClue(s"Key delivery stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("KEYS_ALL_DELIVERED=true")
        stdout should include("KEYS_IN_ORDER=true")
        stdout should include("PASTE_TAKEN=true")
        stdout should include("KEYS_TO_STOPPED_COMPUTER_DROPPED=true")
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

private[runtime] object KeyDeliveryProbe extends EitherValues with Matchers {
  // Counts key_down and key_up signals and keeps the typed characters, so the probe can see that
  // every key arrived, in order. A computer takes about one signal a tick; 600 characters are
  // 1200 signals, far past the 256-signal queue.
  private val Program =
    """local gpu = component.proxy(component.list("gpu")())
      |gpu.bind((component.list("screen")()))
      |gpu.set(1, 1, "READY")
      |local downs, ups, typed = 0, 0, {}
      |while true do
      |  local name, _, char = computer.pullSignal()
      |  if name == "key_down" then downs = downs + 1 typed[#typed + 1] = string.char(char) end
      |  if name == "key_up" then ups = ups + 1 end
      |  if name == "clipboard" then gpu.set(1, 5, "PASTED " .. char) end
      |  if name == "key_down" or name == "key_up" then
      |    gpu.set(1, 2, ("KEYS %d %d"):format(downs, ups))
      |    local text = table.concat(typed)
      |    gpu.set(1, 3, text:sub(1, 60))
      |    gpu.set(1, 4, text:sub(-60))
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
        runUntil(session, ScreenContains(screen, "READY"))

        val text = (0 until 600).map(i => ('a' + i % 26).toChar).mkString
        session.send(screen, UserInput.TypeText(text)).value.eventsSent shouldBe 1200
        // Typing returns once the computer has taken the keys, so the screen shows them at once.
        runUntil(session, ScreenContains(screen, "KEYS 600 600")).elapsedTicks should be <= 1
        Console.out.println("KEYS_ALL_DELIVERED=true")
        val shown = session.readScreen(screen).value.text
        shown should include(text.take(60))
        shown should include(text.takeRight(60))
        Console.out.println("KEYS_IN_ORDER=true")

        session.send(screen, UserInput.Paste("one clipboard signal")).value.eventsSent shouldBe 1
        runUntil(session, ScreenContains(screen, "PASTED one clipboard signal")).elapsedTicks should
          be <= 1
        Console.out.println("PASTE_TAKEN=true")

        session.stopMachine(computer).value
        runUntil(session, MachineReaches(computer, MachineState.Stopped))
        session.send(screen, UserInput.TypeText("x" * 300)).value.eventsSent shouldBe 600
        Console.out.println("KEYS_TO_STOPPED_COMPUTER_DROPPED=true")
      } finally session.close()
    } finally owner.close()
  }

  private val Manifest =
    """schemaVersion = 2
      |project { id = "key-delivery" }
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
      |}
      |connections = [
      |  { from = "pc:network", to = "pc-screen:network" }
      |]
      |""".stripMargin

  private def runUntil(session: HarnessSession, condition: StopCondition): RunResult = {
    val result = session.run(RunRequest(condition, maxTicks = 4000, maxWallTime = 40.seconds)).value
    withClue(
      s"screen: ${session.readScreen(ScreenId.parse("pc-screen").value).map(_.text.trim)}\n" +
        s"machines: ${result}\n"
    )(result.stopReason shouldBe RunStopReason.ConditionSatisfied)
    result
  }

  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }
}
