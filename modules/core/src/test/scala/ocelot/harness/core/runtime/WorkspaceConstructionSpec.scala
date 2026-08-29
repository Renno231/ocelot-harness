package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import totoro.ocelot.brain.entity.traits.Entity
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.project.{CardKind, ProjectLoader}
import ocelot.harness.core.workspace.ComponentRole

final class WorkspaceConstructionSpec extends AnyFunSuite with Matchers {
  test("a forked real brain constructs logical topology and cleans partial construction") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-workspace-construction-")
    val projectDirectory = workDirectory.resolve("project")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      Files.createDirectories(projectDirectory.resolve("computer"))
      Files.write(
        projectDirectory.resolve(ProjectLoader.ManifestFileName),
        WorkspaceConstructionProbe.ValidManifest.getBytes(StandardCharsets.UTF_8)
      )

      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.WorkspaceConstructionProbe",
        workDirectory.resolve("native-libraries").toString,
        workDirectory.resolve("runtime").toString,
        projectDirectory.toString
      )
        .redirectOutput(stdoutFile.toFile)
        .redirectError(stderrFile.toFile)
        .start()

      val exited = process.waitFor(30L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }

      val stdout = new String(Files.readAllBytes(stdoutFile), StandardCharsets.UTF_8)
      val stderr = new String(Files.readAllBytes(stderrFile), StandardCharsets.UTF_8)
      withClue(s"Forked construction stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout should include("WORKSPACE_LOGICAL_TOPOLOGY=true")
        stdout should include("WORKSPACE_PARTIAL_CLEANUP=true")
        stdout should include("WORKSPACE_RUNTIME_SHUTDOWN=true")
      }
    } finally deleteRecursively(workDirectory)
  }

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

private[runtime] object WorkspaceConstructionProbe {
  val ValidManifest: String =
    """schemaVersion = 1
      |project { id = "demo" }
      |runtime { }
      |computers {
      |  main {
      |    caseTier = 3
      |    hardware {
      |      cpu = { tier = 3 }
      |      memory = [{ tier = 3 }, { tier = 3.5 }]
      |      gpu = { tier = 3 }
      |      eeprom = { builtin = "lua-bios" }
      |      disks {
      |        project {
      |          kind = "hdd"
      |          tier = 3
      |          label = "project"
      |          source = "./computer"
      |          access = "read-write"
      |        }
      |      }
      |      cards = [{ kind = "network", tier = 2 }]
      |    }
      |  }
      |}
      |screens { main { tier = 3, keyboard = true, aspectRatio = [1, 1] } }
      |connections = [{ from = "computer:main", to = "screen:main" }]
      |""".stripMargin

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
      .fold(error => throw new IllegalStateException(s"${error.code}: ${error.message}"), identity)

    try {
      val session = owner
        .openProject(projectRoot)
        .fold(
          error => throw new IllegalStateException(s"${error.code}: ${error.message}"),
          identity
        )
      try assertDescription(session.describe(), projectRoot)
      finally session.close()
      Console.out.println("WORKSPACE_LOGICAL_TOPOLOGY=true")

      val project = ProjectLoader
        .load(projectRoot)
        .fold(errors => throw new IllegalStateException(errors.toString), identity)
      val workspace = new FailingWorkspace(projectRoot, failOnAdd = 2)
      try {
        HardwareCatalog.construct(project, workspace)
        throw new IllegalStateException("construction unexpectedly succeeded")
      } catch {
        case error: IllegalStateException
            if error.getMessage == "deliberate construction failure" =>
      }
      require(workspace.getEntitiesIter.isEmpty, "partial workspace entities were not removed")
      require(workspace.removedCount == 1, "the added computer was not removed exactly once")
      Console.out.println("WORKSPACE_PARTIAL_CLEANUP=true")
    } finally {
      owner.close()
      Console.out.println("WORKSPACE_RUNTIME_SHUTDOWN=true")
    }
  }

  private def assertDescription(
      description: ocelot.harness.core.workspace.WorkspaceDescription,
      projectRoot: Path
  ): Unit = {
    require(description.projectId.value == "demo")
    require(description.computers.size == 1)
    require(description.screens.size == 1)
    val computer = description.computers.head
    val screen = description.screens.head
    require(computer.id.value == "main")
    require(computer.caseTier == 3)
    require(computer.runtimeAddress.nonEmpty)
    require(
      computer.components.map(_.role) == Vector(
        ComponentRole.Cpu,
        ComponentRole.Memory,
        ComponentRole.Memory,
        ComponentRole.Gpu,
        ComponentRole.Eeprom
      )
    )
    require(computer.components.map(_.tier) == Vector("3", "3", "3.5", "3", "builtin"))
    require(computer.components.flatMap(_.runtimeAddress).nonEmpty)
    require(computer.disks.size == 1)
    require(computer.disks.head.id.value == "project")
    require(computer.disks.head.label == "project")
    require(computer.disks.head.source == projectRoot.resolve("computer").toRealPath())
    require(computer.disks.head.runtimeAddress.nonEmpty)
    require(computer.cards.size == 1)
    require(computer.cards.head.kind == CardKind.Network)
    require(computer.cards.head.tier == 2)
    require(computer.cards.head.runtimeAddress.nonEmpty)
    require(screen.id.value == "main")
    require(screen.tier == 3)
    require(screen.runtimeAddress.nonEmpty)
    require(screen.keyboardRuntimeAddress.exists(_.nonEmpty))
    require(
      description.connections.map(connection => connection.from.value -> connection.to.value) ==
        Vector("computer:main" -> "screen:main")
    )
    require(!description.toString.contains("totoro.ocelot"))
  }

  private final class FailingWorkspace(path: Path, failOnAdd: Int) extends Workspace(path) {
    private var addCount = 0
    var removedCount = 0

    override def add[T <: Entity](entity: T): T = {
      addCount += 1
      if (addCount == failOnAdd) {
        throw new IllegalStateException("deliberate construction failure")
      }
      super.add(entity)
    }

    override def remove[T <: Entity](entity: T): T = {
      removedCount += 1
      super.remove(entity)
    }
  }
}
