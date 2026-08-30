package ocelot.harness.core.project

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}

import scala.util.control.NonFatal

final case class ProjectTemplateError(code: String, message: String)

object ProjectTemplates {
  val Names: Vector[String] =
    Vector("single-computer", "two-computers", "rack-server", "mixed-network")

  def initialize(destination: Path, template: String): Either[ProjectTemplateError, Path] = {
    if (!Names.contains(template)) {
      Left(ProjectTemplateError("unknown_template", s"unknown project template: $template"))
    } else if (destination == null) {
      Left(ProjectTemplateError("invalid_destination", "project destination is required"))
    } else {
      val normalized = destination.toAbsolutePath.normalize()
      try {
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
          val occupied =
            !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || {
              val entries = Files.list(normalized)
              try entries.findFirst().isPresent
              finally entries.close()
            }
          if (occupied) {
            Left(
              ProjectTemplateError("destination_occupied", s"destination is not empty: $normalized")
            )
          } else write(normalized, template)
        } else {
          Files.createDirectories(normalized)
          write(normalized, template)
        }
      } catch {
        case NonFatal(error) =>
          Left(
            ProjectTemplateError(
              "project_init_failed",
              Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
            )
          )
      }
    }
  }

  private def write(root: Path, template: String): Either[ProjectTemplateError, Path] = {
    val projectId = logicalProjectId(root)
    val programs = template match {
      case "single-computer" => Vector("computer" -> "MAIN")
      case "two-computers"   => Vector("alpha" -> "ALPHA", "beta" -> "BETA")
      case "rack-server"     => Vector("server" -> "SERVER")
      case "mixed-network"   => Vector("computer" -> "MAIN", "server" -> "SERVER")
    }
    val dataDirectories =
      if (template == "mixed-network") Vector("raid", "floppy") else Vector.empty
    (programs.map(_._1) ++ dataDirectories).foreach(value =>
      Files.createDirectories(root.resolve(value))
    )
    programs.foreach { case (directory, marker) =>
      writeProgram(root.resolve(directory), marker)
    }
    val manifest = render(template, projectId)
    Files.write(
      root.resolve(ProjectLoader.ManifestFileName),
      manifest.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE
    )
    Right(root.toRealPath())
  }

  private def writeProgram(root: Path, marker: String): Unit = {
    val firmware = Files.createDirectories(root.resolve("firmware"))
    val computer = Files.createDirectories(root.resolve("computer"))
    val init =
      """local boot = computer.getBootAddress()
        |local filesystem = assert(component.proxy(boot), "project filesystem unavailable")
        |local handle, reason = filesystem.open("/firmware/project-loader.lua", "r")
        |assert(handle, reason)
        |local chunks = {}
        |while true do
        |  local chunk = filesystem.read(handle, math.huge)
        |  if not chunk then break end
        |  chunks[#chunks + 1] = chunk
        |end
        |filesystem.close(handle)
        |local loader, loadReason = load(table.concat(chunks), "=/firmware/project-loader.lua", "t", _G)
        |assert(loader, loadReason)
        |return loader()
        |""".stripMargin
    val loader =
      """local boot = computer.getBootAddress()
        |local filesystem = assert(component.proxy(boot), "project filesystem unavailable")
        |local handle, reason = filesystem.open("/computer/main.lua", "r")
        |assert(handle, reason)
        |local chunks = {}
        |while true do
        |  local chunk = filesystem.read(handle, math.huge)
        |  if not chunk then break end
        |  chunks[#chunks + 1] = chunk
        |end
        |filesystem.close(handle)
        |local program, loadReason = load(table.concat(chunks), "=/computer/main.lua", "t", _G)
        |assert(program, loadReason)
        |return program()
        |""".stripMargin
    val program =
      s"""local gpu = component.proxy(component.list("gpu")())
         |local screen = component.list("screen")()
         |assert(gpu and screen, "GPU and screen are required")
         |gpu.bind(screen)
         |gpu.setResolution(40, 8)
         |gpu.fill(1, 1, 40, 8, " ")
         |gpu.set(1, 1, "$marker")
         |while true do computer.pullSignal() end
         |""".stripMargin
    Files.write(root.resolve("init.lua"), init.getBytes(StandardCharsets.UTF_8))
    Files.write(firmware.resolve("project-loader.lua"), loader.getBytes(StandardCharsets.UTF_8))
    Files.write(computer.resolve("main.lua"), program.getBytes(StandardCharsets.UTF_8))
  }

  private def logicalProjectId(root: Path): String = {
    val candidate = Option(root.getFileName).map(_.toString.toLowerCase).getOrElse("project")
    val normalized = candidate
      .replaceAll("[^a-z0-9-]+", "-")
      .replaceAll("^-+|-+$", "")
      .take(63)
    val prefixed =
      if (normalized.headOption.exists(_.isLetter)) normalized else s"project-$normalized"
    if (LogicalId.validate(prefixed).isRight) prefixed else "ocelot-project"
  }

  private def render(template: String, projectId: String): String = template match {
    case "single-computer" =>
      manifest(
        projectId,
        computer("main", "computer") + screen("main"),
        Vector("main:network" -> "main-screen:network")
      )
    case "two-computers" =>
      manifest(
        projectId,
        computer("alpha", "alpha") + computer("beta", "beta") + screen("alpha") + screen("beta"),
        Vector("alpha:network" -> "alpha-screen:network", "beta:network" -> "beta-screen:network")
      )
    case "rack-server" =>
      manifest(
        projectId,
        rack + server("server", "server") + screen("server"),
        Vector("rack:mount-1" -> "server:mount", "server:network" -> "server-screen:network")
      )
    case "mixed-network" => mixedNetwork(projectId)
  }

  private def manifest(
      projectId: String,
      devices: String,
      connections: Vector[(String, String)]
  ): String = {
    val renderedConnections = connections
      .map { case (from, to) =>
        s"  { from = \"$from\", to = \"$to\" }"
      }
      .mkString(",\n")
    s"""schemaVersion = 2
       |project { id = \"$projectId\" }
       |workspace { kind = \"manifest\" }
       |runtime {
       |  tickRate = 20
       |  clock { autoStart = true }
       |  internet { http = false, tcp = false }
       |}
       |devices {
       |$devices
       |}
       |connections = [
       |$renderedConnections
       |]
       |""".stripMargin
  }

  private def computer(id: String, directory: String): String =
    s"""  $id {
       |    kind = \"computer\", tier = 3
       |    inventory = [
       |      { slot = \"gpu\", kind = \"gpu\", tier = 3 },
       |      { slot = \"card-1\", kind = \"network\", tier = 1 },
       |      { slot = \"memory-1\", kind = \"memory\", tier = 3.5 },
       |      { slot = \"disk-1\", kind = \"managed-hdd\", tier = 3, id = \"project\", label = \"$id\", source = \"./$directory\", access = \"read-write\" },
       |      { slot = \"cpu\", kind = \"cpu\", tier = 3 },
       |      { slot = \"eeprom\", kind = \"eeprom\", builtin = \"lua-bios\" }
       |    ]
       |  }
       |""".stripMargin

  private def screen(id: String): String =
    s"""  $id-screen { kind = \"screen\", tier = 3, keyboard = true }
       |""".stripMargin

  private val rack: String = "  rack { kind = \"rack\" }\n"

  private def server(id: String, directory: String): String =
    s"""  $id {
       |    kind = \"server\", tier = 3
       |    inventory = [
       |      { slot = \"card-1\", kind = \"gpu\", tier = 3 },
       |      { slot = \"card-2\", kind = \"network\", tier = 1 },
       |      { slot = \"cpu\", kind = \"cpu\", tier = 3 },
       |      { slot = \"component-bus-1\", kind = \"component-bus\", tier = 3 },
       |      { slot = \"memory-1\", kind = \"memory\", tier = 3.5 },
       |      { slot = \"disk-1\", kind = \"managed-hdd\", tier = 3, id = \"project\", label = \"$id\", source = \"./$directory\", access = \"read-write\" },
       |      { slot = \"eeprom\", kind = \"eeprom\", builtin = \"lua-bios\" }
       |    ]
       |  }
       |""".stripMargin

  private def mixedNetwork(projectId: String): String = {
    val devices = computer("main", "computer") + server("server", "server") + screen("main") +
      screen("server") + rack +
      """  controller {
        |    kind = "microcontroller", tier = 2
        |    inventory = [
        |      { slot = "card-1", kind = "network", tier = 1 },
        |      { slot = "cpu", kind = "cpu", tier = 1 },
        |      { slot = "memory-1", kind = "memory", tier = 1.5 },
        |      { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
        |    ]
        |  }
        |  relay {
        |    kind = "relay"
        |    inventory = [
        |      { slot = "cpu", kind = "cpu", tier = 3 },
        |      { slot = "memory", kind = "memory", tier = 3.5 },
        |      { slot = "disk", kind = "unmanaged-hdd", tier = 3 },
        |      { slot = "card", kind = "wireless", tier = 2 }
        |    ]
        |  }
        |  raid {
        |    kind = "raid", label = "raid", source = "./raid", access = "read-write"
        |    inventory = [
        |      { slot = "disk-1", kind = "unmanaged-hdd", tier = 1 },
        |      { slot = "disk-2", kind = "unmanaged-hdd", tier = 2 },
        |      { slot = "disk-3", kind = "unmanaged-hdd", tier = 3 }
        |    ]
        |  }
        |  disk-drive {
        |    kind = "disk-drive"
        |    inventory = [{ slot = "floppy", kind = "managed-floppy", id = "media", label = "media", source = "./floppy", access = "read-write" }]
        |  }
        |  hologram { kind = "hologram", tier = 2 }
        |  note { kind = "note-block" }
        |  iron-note { kind = "iron-note-block" }
        |  cable { kind = "cable" }
        |""".stripMargin
    manifest(
      projectId,
      devices,
      Vector(
        "rack:mount-1" -> "server:mount",
        "main:network" -> "main-screen:network",
        "server:network" -> "server-screen:network",
        "main:network" -> "relay:north",
        "controller:south" -> "relay:east",
        "relay:west" -> "cable:network",
        "cable:network" -> "raid:network",
        "cable:network" -> "disk-drive:network",
        "cable:network" -> "hologram:network",
        "cable:network" -> "note:network",
        "cable:network" -> "iron-note:network"
      )
    )
  }
}
