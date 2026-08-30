package ocelot.harness.core.project

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.UUID

import scala.jdk.CollectionConverters._

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import totoro.ocelot.brain.entity.{Case => BrainCase, Keyboard, Relay, Screen}
import totoro.ocelot.brain.nbt.{CompressedStreamTools, NBTBase, NBTTagCompound}

final class DesktopWorkspaceProjectsSpec extends AnyFunSuite with Matchers with EitherValues {
  test("inspect validates a Desktop root and derives deterministic logical IDs") {
    withDesktopFixture { source =>
      val inspection = DesktopWorkspaceProjects.inspect(source).value

      inspection.entityCount shouldBe 4
      inspection.edgeCount shouldBe 2
      inspection.computers.map(_.logicalId) shouldBe Vector("main-computer")
      inspection.screens.map(_.logicalId) shouldBe Vector("main-screen")
      inspection.additionalEntityCount shouldBe 2
      inspection.computers.head.toString should not include "entityId"
      inspection.computers.head.toString should not include "totoro.ocelot"
      inspection.sha256 should fullyMatch regex "[0-9a-f]{64}"
    }
  }

  test("inspect rejects malformed roots and unavailable serialized classes") {
    withTemporaryDirectory { root =>
      val missingBack = new NBTTagCompound()
      missingBack.setTag("front", new NBTTagCompound())
      writeWorkspace(root, missingBack)
      DesktopWorkspaceProjects.inspect(root).left.value.errors.map(_.code) should contain(
        "desktop_format_invalid"
      )

      val unavailable = desktopRoot(Vector(entity("missing.addon.DesktopThing", "missing")))
      writeWorkspace(root, unavailable)
      val findings = DesktopWorkspaceProjects.inspect(root).left.value.errors
      findings.map(_.code) should contain("desktop_class_unavailable")
      findings.map(_.message).mkString should include("missing.addon.DesktopThing")
    }
  }

  test("duplicate frontend labels receive deterministic stable suffixes") {
    withTemporaryDirectory { root =>
      val first = entity(classOf[Screen].getName, "screen-a")
      val second = entity(classOf[Screen].getName, "screen-b")
      writeWorkspace(
        root,
        desktopRoot(
          Vector(first, second),
          labels = Vector("screen-a" -> "Shared", "screen-b" -> "Shared")
        )
      )

      DesktopWorkspaceProjects.inspect(root).value.screens.map(_.logicalId) shouldBe
        Vector("shared", "shared-2")
    }
  }

  test("compressed-size and source-link limits reject input before import") {
    withTemporaryDirectory { root =>
      val channel = FileChannel.open(
        root.resolve("workspace.nbt"),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
      )
      try {
        channel.position(64L * 1024L * 1024L)
        channel.write(java.nio.ByteBuffer.wrap(Array[Byte](1)))
      } finally channel.close()
      DesktopWorkspaceProjects.inspect(root).left.value.errors.map(_.code) should contain(
        "desktop_limit_exceeded"
      )
    }

    withDesktopFixture { source =>
      val outside = Files.createTempDirectory("ocelot-harness-desktop-outside-")
      val link = source.resolve("escape")
      try {
        createDirectoryLink(link, outside)
        DesktopWorkspaceProjects.inspect(source).left.value.errors.map(_.code) should contain(
          "invalid_path"
        )
      } finally {
        Files.deleteIfExists(link)
        Files.deleteIfExists(outside)
      }
    }
  }

  test("import copies the bounded source and emits schema-v2 identity metadata") {
    withDesktopFixture { source =>
      withTemporaryDirectory { parent =>
        val destination = parent.resolve("imported")
        val before = Files.readAllBytes(source.resolve("workspace.nbt"))

        val result = DesktopWorkspaceProjects.importProject(source, destination).value

        result.projectRoot shouldBe destination.toRealPath()
        ProjectLoader.load(destination).value.workspaceSource shouldBe
          WorkspaceSourceDefinition.Desktop(destination.resolve("desktop").toRealPath())
        val metadata = new String(
          Files.readAllBytes(destination.resolve(".ocelot-harness/desktop-import.conf")),
          StandardCharsets.UTF_8
        )
        metadata should include("main-computer")
        metadata should include("main-screen")
        metadata should include("workspaceSha256")
        Files.readAllBytes(source.resolve("workspace.nbt")) shouldBe before
        Files.exists(source.resolve("ocelot-harness.conf")) shouldBe false
      }
    }
  }

  test("identity metadata must match every imported computer and screen") {
    withDesktopFixture { source =>
      withTemporaryDirectory { parent =>
        val destination = parent.resolve("imported")
        DesktopWorkspaceProjects.importProject(source, destination).value
        val metadata = destination.resolve(DesktopWorkspaceProjects.MetadataRelativePath)
        val mismatched = new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8)
          .replace("main-screen", "other-screen")
        Files.write(metadata, mismatched.getBytes(StandardCharsets.UTF_8))
        val project = ProjectLoader.load(destination).value

        DesktopWorkspaceProjects
          .validateImportedProject(project)
          .left
          .value
          .errors
          .map(_.code) should contain("import_metadata_invalid")
      }
    }
  }

  test("invalid imported identity metadata is rejected deterministically") {
    withDesktopFixture { source =>
      withTemporaryDirectory { parent =>
        val destination = parent.resolve("imported")
        DesktopWorkspaceProjects.importProject(source, destination).value
        val metadata = destination.resolve(DesktopWorkspaceProjects.MetadataRelativePath)
        val invalid = new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8)
          .replace("main-screen", "Bad ID")
        Files.write(metadata, invalid.getBytes(StandardCharsets.UTF_8))

        DesktopImportMetadata.read(destination).left.value.errors.map(_.code) should contain(
          "import_metadata_invalid"
        )
      }
    }
  }

  private def withDesktopFixture(testBody: Path => Unit): Unit =
    withTemporaryDirectory { root =>
      val computer = entity(classOf[BrainCase].getName, "computer")
      val screen = entity(classOf[Screen].getName, "screen")
      val keyboard = entity(classOf[Keyboard].getName, "keyboard")
      val relay = entity(classOf[Relay].getName, "relay")
      val rootTag = desktopRoot(
        Vector(computer, screen, keyboard, relay),
        Vector("computer" -> "screen", "screen" -> "keyboard"),
        Vector("computer" -> "Main Computer", "screen" -> "Main Screen")
      )
      writeWorkspace(root, rootTag)
      Files.createDirectories(root.resolve("computer-disk"))
      Files.write(
        root.resolve("computer-disk/main.lua"),
        "return true\n".getBytes(StandardCharsets.UTF_8)
      )
      testBody(root)
    }

  private def desktopRoot(
      entities: Vector[NBTTagCompound],
      edges: Vector[(String, String)] = Vector.empty,
      labels: Vector[(String, String)] = Vector.empty
  ): NBTTagCompound = {
    val back = new NBTTagCompound()
    back.setInteger("time", 0)
    back.setBoolean("time_paused", false)
    back.setTagList("entities", entities.map(value => value: NBTBase).asJava)
    back.setTagList(
      "edges",
      edges.map { case (left, right) =>
        val edge = new NBTTagCompound()
        edge.setString("left", left)
        edge.setString("right", right)
        edge: NBTBase
      }.asJava
    )
    val front = new NBTTagCompound()
    front.setTagList(
      "nodes",
      labels.map { case (address, label) =>
        val node = new NBTTagCompound()
        node.setString("address", address)
        node.setString("label", label)
        node.setString("class", "ocelot.desktop.node.TestNode")
        node: NBTBase
      }.asJava
    )
    front.setTagList("connections", Vector.empty[NBTBase].asJava)
    val root = new NBTTagCompound()
    root.setTag("back", back)
    root.setTag("front", front)
    root
  }

  private def entity(className: String, address: String): NBTTagCompound = {
    val node = new NBTTagCompound()
    node.setString("address", address)
    val data = new NBTTagCompound()
    data.setString(
      "entity_id",
      UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8)).toString
    )
    data.setTag("node", node)
    val entity = new NBTTagCompound()
    entity.setString("type", className)
    entity.setTag("data", data)
    entity
  }

  private def writeWorkspace(root: Path, nbt: NBTTagCompound): Unit =
    Files.write(root.resolve("workspace.nbt"), CompressedStreamTools.write(nbt))

  private def createDirectoryLink(link: Path, target: Path): Unit = {
    try Files.createSymbolicLink(link, target)
    catch {
      case _: java.nio.file.FileSystemException
          if System.getProperty("os.name").startsWith("Windows") =>
        val process = new ProcessBuilder(
          "cmd.exe",
          "/D",
          "/C",
          "mklink",
          "/J",
          link.toString,
          target.toString
        ).inheritIO().start()
        require(process.waitFor() == 0, "failed to create test junction")
    }
  }

  private def withTemporaryDirectory(testBody: Path => Unit): Unit = {
    val root = Files.createTempDirectory("ocelot-harness-desktop-project-")
    try testBody(root)
    finally {
      if (Files.exists(root)) {
        val paths = Files.walk(root)
        try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
  }
}
