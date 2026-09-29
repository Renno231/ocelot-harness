package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import totoro.ocelot.brain.entity.NetworkCard
import totoro.ocelot.brain.entity.traits.Environment
import totoro.ocelot.brain.network.{Message, Network, Node, Packet, Visibility}
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.project.{ComputerId, ProjectLoader, ScreenId, ServicePolicy}
import ocelot.harness.core.workspace._

final class MultiDeviceExecutionSpec extends AnyFunSuite with Matchers {
  test("multiple real-brain computers keep isolated disks, screens, input, and diagnostics") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-multi-device-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      Files.createDirectories(projectDirectory)
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.MultiDeviceExecutionProbe",
        workDirectory.resolve("native-libraries").toString,
        workDirectory.resolve("runtime").toString,
        projectDirectory.toString,
        fixtureDirectory.toString
      ).redirectOutput(stdoutFile.toFile).redirectError(stderrFile.toFile).start()

      val exited = process.waitFor(120L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      val stdout = readText(stdoutFile)
      val stderr = readText(stderrFile)
      withClue(s"Multi-device stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("MULTI_INDEPENDENT=true")
        stdout should include("MULTI_TARGETED_INPUT=true")
        stdout should include("MULTI_DIAGNOSTICS=true")
        stdout should include("MULTI_NETWORK_CONNECTIVITY=true")
        stdout should include("MULTI_REOPEN_REMOVAL=true")
        stdout should include("MULTI_CLEANUP=true")
      }
    } finally deleteRecursively(workDirectory)
  }

  private def fixtureDirectory: Path = {
    val candidates = Vector(
      Paths.get("fixtures", "vertical-spike"),
      Paths.get("..", "..", "fixtures", "vertical-spike")
    ).map(_.toAbsolutePath.normalize())
    candidates.find(Files.isDirectory(_)).getOrElse(fail("vertical-spike fixture is missing"))
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

private[runtime] object MultiDeviceExecutionProbe extends EitherValues with Matchers {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 4, "expected native, runtime, project, and fixture directories")
    val projectRoot = Paths.get(arguments(2)).toRealPath()
    val fixture = Paths.get(arguments(3)).toRealPath()
    prepareDisk(fixture, projectRoot.resolve("alpha"), "ALPHA")
    prepareDisk(fixture, projectRoot.resolve("beta"), "BETA")
    prepareDisk(fixture, projectRoot.resolve("gamma"), "GAMMA")
    writeManifest(projectRoot, includeBeta = true)

    val owner = RuntimeOwner
      .start(
        RuntimeConfig(
          Paths.get(arguments(1)).toAbsolutePath.normalize(),
          Paths.get(arguments(0)).toAbsolutePath.normalize()
        )
      )
      .value
    val alphaComputer = ComputerId.parse("alpha").value
    val betaComputer = ComputerId.parse("beta").value
    val alphaScreen = ScreenId.parse("alpha").value
    val betaScreen = ScreenId.parse("beta").value

    try {
      val session = owner
        .openProject(
          projectRoot,
          ServicePolicy(maxComputers = 3, maxScreens = 3, maxConnections = 3, maxManagedDisks = 3)
        )
        .value
      try {
        session.startMachine(alphaComputer).value
        session.startMachine(betaComputer).value
        runUntil(session, ScreenContains(alphaScreen, "ALPHA"))
        runUntil(session, ScreenContains(betaScreen, "BETA"))
        session.readScreen(alphaScreen).value.text should include("ALPHA")
        session.readScreen(betaScreen).value.text should include("BETA")
        Console.out.println("MULTI_INDEPENDENT=true")

        session.send(alphaScreen, UserInput.Touch(1, 1)).value.eventsSent shouldBe 1
        runUntil(session, ScreenContains(alphaScreen, "TOUCHED"))
        session.readScreen(betaScreen).value.text should include("BETA")
        session.readScreen(betaScreen).value.text should not include "TOUCHED"
        Console.out.println("MULTI_TARGETED_INPUT=true")

        val diagnostics = session.diagnostics(DiagnosticRequest("diagnostics/multi.zip")).value
        Vector(
          "screens/alpha.txt",
          "screens/alpha.cells.json",
          "screens/alpha.png",
          "screens/beta.txt",
          "screens/beta.cells.json",
          "screens/beta.png",
          "screens/gamma.txt",
          "screens/gamma.cells.json",
          "screens/gamma.png"
        ).foreach(diagnostics.entries should contain(_))
        Console.out.println("MULTI_DIAGNOSTICS=true")
      } finally session.close()

      verifyConfiguredNetwork(projectRoot, alphaComputer, betaComputer, betaScreen)

      writeManifest(projectRoot, includeBeta = false)
      val reopened = owner.openProject(projectRoot).value
      try {
        reopened.describe().computers.map(_.id.value) shouldBe Vector("alpha")
        reopened.describe().screens.map(_.id.value) shouldBe Vector("alpha")
        reopened.readScreen(betaScreen).left.value.code shouldBe "unknown_screen"
        Console.out.println("MULTI_REOPEN_REMOVAL=true")
      } finally reopened.close()
    } finally {
      owner.close()
      Console.out.println("MULTI_CLEANUP=true")
    }
  }

  private def verifyConfiguredNetwork(
      projectRoot: Path,
      alphaId: ComputerId,
      betaId: ComputerId,
      betaScreenId: ScreenId
  ): Unit = {
    writeManifest(projectRoot, includeBeta = true, connectNetworks = true)
    val project = ProjectLoader.load(projectRoot).value
    val workspace = new Workspace(projectRoot)
    try {
      val topology = HardwareCatalog.construct(project, workspace)
      val alpha = topology.computers(alphaId)
      val beta = topology.computers(betaId)
      val alphaCard = alpha.inventory.entities.collectFirst { case card: NetworkCard => card }.get
      val betaCard = beta.inventory.entities.collectFirst { case card: NetworkCard => card }.get
      (alphaCard.node.network eq betaCard.node.network) shouldBe true
      val observer = new PacketObserver
      beta.connect(observer)
      val packet = Network.newPacket(
        alphaCard.node.address,
        null,
        4242,
        Array[AnyRef]("configured-path")
      )
      alphaCard.node.sendToReachable("network.message", packet)
      observer.received shouldBe 1

      alpha.disconnect(topology.screens(betaScreenId))
      (alphaCard.node.network eq betaCard.node.network) shouldBe false
      alphaCard.node.sendToReachable("network.message", packet)
      observer.received shouldBe 1
      observer.node.remove()
      Console.out.println("MULTI_NETWORK_CONNECTIVITY=true")
    } finally workspace.getEntitiesIter.toVector.reverse.foreach(workspace.remove)
  }

  private final class PacketObserver extends Environment {
    override val node: Node = Network
      .newNode(this, Visibility.Network)
      .withComponent("packet_observer", Visibility.None)
      .create()
    var received = 0

    override def onMessage(message: Message): Unit =
      if (message.name == "network.message" && message.data.exists(_.isInstanceOf[Packet])) {
        received += 1
      }
  }

  private def runUntil(session: HarnessSession, condition: StopCondition): RunResult =
    session.run(RunRequest(condition, maxTicks = 2000, maxWallTime = 10.seconds)).value

  private def prepareDisk(source: Path, destination: Path, marker: String): Unit = {
    Files.createDirectories(destination)
    Vector("init.lua", "firmware", "computer").foreach { name =>
      val from = source.resolve(name)
      val to = destination.resolve(name)
      if (Files.isDirectory(from)) copyDirectory(from, to)
      else Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
    val program = destination.resolve("computer").resolve("main.lua")
    Files.write(
      program,
      readText(program)
        .replace("local marker = \"READY\"", s"local marker = \"$marker\"")
        .getBytes(StandardCharsets.UTF_8)
    )
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

  private def writeManifest(
      root: Path,
      includeBeta: Boolean,
      connectNetworks: Boolean = false
  ): Unit = {
    val additionalComputers = if (includeBeta) computer("beta") + computer("gamma") else ""
    val additionalScreens =
      if (includeBeta)
        "  beta { tier = 2, keyboard = true }\n  gamma { tier = 1, keyboard = true }\n"
      else ""
    val additionalConnections =
      if (includeBeta)
        ", { from = \"computer:beta\", to = \"screen:beta\" }, { from = \"computer:gamma\", to = \"screen:gamma\" }"
      else ""
    val networkConnection =
      if (includeBeta && connectNetworks)
        ", { from = \"computer:alpha\", to = \"screen:beta\" }"
      else ""
    val manifest =
      s"""schemaVersion = 1
         |project { id = "multi-device" }
         |runtime { limits { eventBufferSize = 32 } }
         |computers {
         |${computer("alpha")}$additionalComputers}
         |screens {
         |  alpha { tier = 3, keyboard = true }
         |$additionalScreens}
         |connections = [{ from = "computer:alpha", to = "screen:alpha" }$additionalConnections$networkConnection]
         |""".stripMargin
    Files.write(
      root.resolve(ProjectLoader.ManifestFileName),
      manifest.getBytes(StandardCharsets.UTF_8)
    )
  }

  private def computer(id: String): String = {
    val tier = id match {
      case "gamma" => 1
      case "beta"  => 2
      case _       => 3
    }
    val memory = tier match {
      case 1 => "[{ tier = 1.5 }]"
      case 2 => "[{ tier = 2 }, { tier = 2.5 }]"
      case _ => "[{ tier = 3 }, { tier = 3.5 }]"
    }
    s"""  $id {
       |    caseTier = $tier
       |    hardware {
       |      cpu = { tier = $tier }
       |      memory = $memory
       |      gpu = { tier = $tier }
       |      eeprom = { builtin = "lua-bios" }
       |      disks {
       |        project {
       |          kind = "hdd"
       |          tier = $tier
       |          label = "$id"
       |          source = "./$id"
       |          access = "${if (id == "beta") "read-only" else "read-write"}"
       |        }
       |      }
       |      cards = [{ kind = "network", tier = 1 }]
       |    }
       |  }
       |""".stripMargin
  }

  private def readText(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
}
