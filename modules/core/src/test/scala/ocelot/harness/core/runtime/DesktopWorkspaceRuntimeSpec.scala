package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import totoro.ocelot.brain.entity.Relay
import totoro.ocelot.brain.entity.traits.DiskRealPathAware
import totoro.ocelot.brain.nbt.{CompressedStreamTools, NBT, NBTBase, NBTTagCompound}
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.project.DesktopWorkspaceProjects
import ocelot.harness.core.workspace.{
  HostDiskSnapshotPolicy,
  RunRequest,
  ScreenContains,
  SnapshotName,
  SnapshotRequest
}

final class DesktopWorkspaceRuntimeSpec extends AnyFunSuite with Matchers {
  test("a forked runtime controls and restores every discovered Desktop computer and screen") {
    val work = Files.createTempDirectory("ocelot-harness-desktop-runtime-")
    val stdout = work.resolve("stdout.log")
    val stderr = work.resolve("stderr.log")
    try {
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.DesktopWorkspaceRuntimeProbe",
        work.toString
      ).redirectOutput(stdout.toFile).redirectError(stderr.toFile).start()
      val exited = process.waitFor(45L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      val out = read(stdout)
      val err = read(stderr)
      withClue(s"Desktop runtime stdout:\n$out\nstderr:\n$err\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        out should include("DESKTOP_DISCOVERED=true")
        out should include("DESKTOP_CONTROLLED=true")
        out should include("DESKTOP_DISKS_ARCHIVED=true")
        out should include("DESKTOP_GRAPH_PRESERVED=true")
        out should include("DESKTOP_FAILED_RESTORE_NONDESTRUCTIVE=true")
        out should include("DESKTOP_IDENTITIES_STABLE=true")
        out should include("DESKTOP_SOURCE_UNCHANGED=true")
        out should include("DESKTOP_RUNTIME_SHUTDOWN=true")
      }
    } finally deleteRecursively(work)
  }

  private def read(path: Path): String =
    if (Files.exists(path)) new String(Files.readAllBytes(path), StandardCharsets.UTF_8) else ""

  private def javaExecutable: Path = {
    val executable =
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}

private[runtime] object DesktopWorkspaceRuntimeProbe {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected work directory")
    val work = Paths.get(arguments(0)).toAbsolutePath.normalize()
    val source = Files.createDirectories(work.resolve("desktop-source"))
    val projectCandidates = Vector(
      Paths.get("examples", "two-computers"),
      Paths.get("..", "..", "examples", "two-computers")
    ).map(_.toAbsolutePath.normalize())
    val projectRoot = projectCandidates
      .find(Files.isDirectory(_))
      .getOrElse(
        throw new IllegalStateException("checked two-computer example is unavailable")
      )
    val owner = RuntimeOwner
      .start(
        RuntimeConfig(
          work.resolve("runtime"),
          work.resolve("native")
        )
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    try {
      val project = ocelot.harness.core.project.ProjectLoader
        .load(projectRoot)
        .fold(errors => sys.error(errors.toString), identity)
      val sourceWorkspace = new Workspace(source)
      val topology = HardwareCatalog.construct(project, sourceWorkspace)
      topology.computers.toVector.sortBy(_._1.value).foreach { case (id, computer) =>
        val marker = s"IMPORTED ${id.value.toUpperCase}"
        val program =
          s"local gpu=component.proxy(component.list('gpu')()); local screen=component.list('screen')(); gpu.bind(screen); gpu.set(1,1,'$marker'); while true do computer.pullSignal() end\n"
        val copiedDisk = Files.createDirectories(source.resolve(id.value))
        Files.write(copiedDisk.resolve("init.lua"), program.getBytes(StandardCharsets.UTF_8))
        computer.inventory.entities
          .collect { case disk: DiskRealPathAware => disk }
          .foreach(_.customRealPath = Some(copiedDisk))
      }
      sourceWorkspace.add(new Relay())
      val back = new NBTTagCompound()
      sourceWorkspace.save(back)
      val front = new NBTTagCompound()
      front.setTagList(
        "nodes",
        (topology.description.computers.map(value =>
          frontNode(value.runtimeAddress, s"Imported ${value.id.value}")
        ) ++ topology.description.screens.map(value =>
          frontNode(value.runtimeAddress, s"${value.id.value} display")
        )).map(value => value: NBTBase).asJava
      )
      front.setTagList("connections", Vector.empty[NBTBase].asJava)
      val desktopRoot = new NBTTagCompound()
      desktopRoot.setTag("back", back)
      desktopRoot.setTag("front", front)
      Files.write(source.resolve("workspace.nbt"), CompressedStreamTools.write(desktopRoot))
      sourceWorkspace.getEntitiesIter.toVector.reverse.foreach(sourceWorkspace.remove)
      val original = Files.readAllBytes(source.resolve("workspace.nbt"))

      val imported = work.resolve("imported-project")
      DesktopWorkspaceProjects
        .importProject(source, imported)
        .fold(errors => sys.error(errors.toString), identity)
      val session = owner.openProject(imported).fold(error => sys.error(error.message), identity)
      try {
        val description = session.describe()
        require(
          description.computers.map(_.id.value) == Vector("imported-alpha", "imported-beta")
        )
        require(description.screens.map(_.id.value) == Vector("alpha-display", "beta-display"))
        Console.out.println("DESKTOP_DISCOVERED=true")

        description.computers.foreach(value =>
          session.startMachine(value.id).fold(error => sys.error(error.message), identity)
        )
        description.screens.zip(Vector("IMPORTED ALPHA", "IMPORTED BETA")).foreach {
          case (screen, marker) =>
            val result = session
              .run(RunRequest(ScreenContains(screen.id, marker), 2000, 10.seconds))
              .fold(error => sys.error(error.message), identity)
            require(result.screens(screen.id).text.contains(marker))
        }
        Console.out.println("DESKTOP_CONTROLLED=true")

        session
          .saveSnapshot(SnapshotRequest(SnapshotName.parse("before").toOption.get))
          .fold(error => sys.error(error.message), identity)
        val archived = session
          .saveSnapshot(
            SnapshotRequest(
              SnapshotName.parse("archived").toOption.get,
              HostDiskSnapshotPolicy.Copy
            )
          )
          .fold(error => sys.error(error.message), identity)
        require(archived.copiedDiskBytes > 0L)
        val archivedFiles = Files.walk(
          imported.resolve(".ocelot-harness/snapshots/archived/disks")
        )
        try require(archivedFiles.iterator().asScala.exists(_.getFileName.toString == "init.lua"))
        finally archivedFiles.close()
        Console.out.println("DESKTOP_DISKS_ARCHIVED=true")
        session
          .saveSnapshot(SnapshotRequest(SnapshotName.parse("broken").toOption.get))
          .fold(error => sys.error(error.message), identity)
        corruptSnapshotCandidate(imported, "broken")
        require(session.loadSnapshot(SnapshotName.parse("broken").toOption.get).isLeft)
        require(
          session
            .readScreen(description.screens.head.id)
            .toOption
            .exists(_.text.contains("IMPORTED ALPHA"))
        )
        Console.out.println("DESKTOP_FAILED_RESTORE_NONDESTRUCTIVE=true")

        session
          .loadSnapshot(SnapshotName.parse("before").toOption.get)
          .fold(
            error => sys.error(error.message),
            identity
          )
        session
          .saveSnapshot(SnapshotRequest(SnapshotName.parse("after").toOption.get))
          .fold(error => sys.error(error.message), identity)
        val before = snapshotCounts(imported, "before")
        val after = snapshotCounts(imported, "after")
        require(before == after && before._1 == 7 && before._2 >= 4)
        Console.out.println("DESKTOP_GRAPH_PRESERVED=true")
      } finally session.close()

      val reopened = owner.openProject(imported).fold(error => sys.error(error.message), identity)
      try {
        require(
          reopened.describe().computers.map(_.id.value) == Vector("imported-alpha", "imported-beta")
        )
        require(
          reopened.describe().screens.map(_.id.value) == Vector("alpha-display", "beta-display")
        )
      } finally reopened.close()
      Console.out.println("DESKTOP_IDENTITIES_STABLE=true")

      require(
        java.util.Arrays.equals(original, Files.readAllBytes(source.resolve("workspace.nbt")))
      )
      Console.out.println("DESKTOP_SOURCE_UNCHANGED=true")
    } finally {
      owner.close()
      Console.out.println("DESKTOP_RUNTIME_SHUTDOWN=true")
    }
  }

  private def frontNode(address: String, label: String): NBTTagCompound = {
    val node = new NBTTagCompound()
    node.setString("address", address)
    node.setString("label", label)
    node.setString("class", "ocelot.desktop.node.TestNode")
    node
  }

  private def corruptSnapshotCandidate(project: Path, name: String): Unit = {
    val directory = project.resolve(".ocelot-harness/snapshots").resolve(name)
    val invalid = new NBTTagCompound()
    invalid.setInteger("time", 0)
    invalid.setBoolean("time_paused", false)
    invalid.setTagList("entities", Vector.empty[NBTBase].asJava)
    invalid.setTagList("edges", Vector.empty[NBTBase].asJava)
    val bytes = CompressedStreamTools.write(invalid)
    Files.write(directory.resolve("workspace.nbt.gz"), bytes)
    val metadataPath = directory.resolve("metadata.conf")
    val metadata = new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8)
      .replaceAll("workspaceSize=[0-9]+", s"workspaceSize=${bytes.length}")
      .replaceAll(
        "workspaceSha256=\\\"[0-9a-f]{64}\\\"",
        s"workspaceSha256=\\\"${DesktopWorkspaceProjects.sha256(bytes)}\\\""
      )
    Files.write(metadataPath, metadata.getBytes(StandardCharsets.UTF_8))
  }

  private def snapshotCounts(project: Path, name: String): (Int, Int) = {
    val bytes = Files.readAllBytes(
      project.resolve(".ocelot-harness/snapshots").resolve(name).resolve("workspace.nbt.gz")
    )
    val nbt = CompressedStreamTools.read(
      bytes,
      new totoro.ocelot.brain.nbt.NBTReadLimiter(64L * 1024 * 1024)
    )
    nbt.getTagList("entities", NBT.TAG_COMPOUND).tagCount() ->
      nbt.getTagList("edges", NBT.TAG_COMPOUND).tagCount()
  }
}
