package ocelot.harness.core.project

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ProjectLoaderSpec extends AnyFunSuite with Matchers with EitherValues {
  test("a minimum schema-v1 project expands deterministic defaults and canonical paths") {
    withProject(validManifest()) { root =>
      val project = ProjectLoader.load(root).value

      project.schemaVersion shouldBe 1
      project.id.value shouldBe "demo"
      project.paths.projectRoot shouldBe root.toRealPath()
      project.paths.manifest shouldBe root.resolve("ocelot-harness.conf").toRealPath()
      project.paths.artifacts shouldBe root.resolve(".ocelot-harness/artifacts").toAbsolutePath
      project.paths.snapshots shouldBe root.resolve(".ocelot-harness/snapshots").toAbsolutePath
      project.runtime.tickRate shouldBe 20
      project.runtime.limits.defaultMaxTicks shouldBe 1000
      project.runtime.limits.defaultMaxWallTimeMillis shouldBe 30000L
      project.runtime.limits.eventBufferSize shouldBe 10000
      project.runtime.internet.requestedHttp shouldBe false
      project.runtime.internet.requestedTcp shouldBe false
      project.runtime.internet.httpEnabled shouldBe false
      project.runtime.internet.tcpEnabled shouldBe false
      project.computers.map(_.id.value) shouldBe Vector("main")
      project.screens.map(_.id.value) shouldBe Vector("main")
      Files.exists(root.resolve(".ocelot-harness")) shouldBe false
    }
  }

  test("the checked-in two-computer example is a valid bounded schema-v1 project") {
    val candidates = Vector(
      java.nio.file.Paths.get("examples", "two-computers"),
      java.nio.file.Paths.get("..", "..", "examples", "two-computers")
    ).map(_.toAbsolutePath.normalize())
    val root = candidates.find(Files.isDirectory(_)).getOrElse(fail("example project is missing"))

    val project = ProjectLoader.load(root).value
    project.computers.map(_.id.value) shouldBe Vector("alpha", "beta")
    project.computers.map(_.caseTier) shouldBe Vector(3, 2)
    project.screens.map(_.tier) shouldBe Vector(3, 2)
    project.computers(1).hardware.disks.head.access shouldBe DiskAccess.ReadOnly
  }

  test("logical IDs are publicly constructible only through validated typed parsers") {
    val computer = ComputerId.parse("main").value
    val screen = ScreenId.parse("main").value

    computer.value shouldBe "main"
    screen.value shouldBe "main"
    computer should not equal screen
    DiskId.parse("Bad ID").left.value should include("must match")
  }

  test("invalid input reports independent errors in stable path order") {
    val manifest = validManifest()
      .replace("project { id = \"demo\" }", "project { id = \"Bad ID\" }")
      .replace("cpu = { tier = 3 }", "cpu = { tier = 3, mystery = true }")
      .replace("runtime {", "runtime {\n  secretRoot = \"../outside\"")

    withProject(manifest) { root =>
      val first = ProjectLoader.load(root).left.value.errors
      val second = ProjectLoader.load(root).left.value.errors

      first shouldBe second
      first.map(_.path) shouldBe first.map(_.path).sorted
      first.map(_.code) should contain("unknown_key")
      first.map(_.code) should contain("invalid_logical_id")
      first.count(_.code == "unknown_key") should be >= 2
    }
  }

  test("schema v2 selects a canonical project-contained Desktop workspace source") {
    withProject(
      """schemaVersion = 2
        |project { id = "desktop-demo" }
        |workspace { kind = "desktop", directory = "./desktop" }
        |runtime { }
        |""".stripMargin
    ) { root =>
      val desktop = Files.createDirectories(root.resolve("desktop"))
      Files.write(desktop.resolve("workspace.nbt"), Array[Byte](1, 2, 3))

      val project = ProjectLoader.load(root).value

      project.schemaVersion shouldBe 2
      project.computers shouldBe empty
      project.screens shouldBe empty
      project.connections shouldBe empty
      project.workspaceSource shouldBe WorkspaceSourceDefinition.Desktop(desktop.toRealPath())
    }
  }

  test("schema-v2 Desktop sources cannot escape the project or mix manifest topology") {
    withTempDirectory { parent =>
      val root = Files.createDirectory(parent.resolve("project"))
      val outside = Files.createDirectory(parent.resolve("outside"))
      writeManifest(
        root,
        s"""schemaVersion = 2
           |project { id = "desktop-demo" }
           |workspace { kind = "desktop", directory = "${outside.toString.replace('\\', '/')}" }
           |runtime { }
           |computers { main = {} }
           |""".stripMargin
      )

      val errors = ProjectLoader.load(root).left.value.errors
      errors.map(_.path) should contain allElementsOf Vector("workspace.directory", "computers")
      errors.map(_.code) should contain allElementsOf Vector("path_not_allowed", "source_conflict")
    }
  }

  test("an unsupported schema is rejected before project fields are interpreted") {
    withProject("schemaVersion = 3\nproject.id = \"Bad ID\"\n") { root =>
      val errors = ProjectLoader.load(root).left.value.errors

      errors.map(_.code) shouldBe Vector("unsupported_schema")
      errors.head.path shouldBe "schemaVersion"
    }
  }

  test("duplicate topology identities fail deterministically") {
    val manifest = validManifest().replace(
      "connections = [{ from = \"computer:main\", to = \"screen:main\" }]",
      """connections = [
        |  { from = "computer:main", to = "screen:main" },
        |  { from = "computer:main", to = "screen:main" }
        |]""".stripMargin
    )

    withProject(manifest) { root =>
      ProjectLoader.load(root).left.value.errors.map(_.code) should contain("duplicate_id")
    }
  }

  test("connections require at least one valid computer-to-screen edge") {
    val cases = Vector(
      "empty connections" -> validManifest().replace(
        "connections = [{ from = \"computer:main\", to = \"screen:main\" }]",
        "connections = []"
      ),
      "unknown endpoint" -> validManifest().replace("computer:main", "computer:missing"),
      "reversed endpoint kinds" -> validManifest().replace(
        "{ from = \"computer:main\", to = \"screen:main\" }",
        "{ from = \"screen:main\", to = \"computer:main\" }"
      )
    )

    cases.foreach { case (clue, manifest) =>
      withProject(manifest) { root =>
        withClue(clue) {
          val codes = ProjectLoader.load(root).left.value.errors.map(_.code)
          codes.exists(Set("profile_violation", "invalid_connection")) shouldBe true
        }
      }
    }
  }

  test("manifest includes are restricted to canonical project-local files") {
    withTempDirectory { parent =>
      val root = Files.createDirectory(parent.resolve("project"))
      Files.createDirectory(root.resolve("computer"))
      Files.write(
        root.resolve("identity.conf"),
        "project { id = \"demo\" }\n".getBytes(StandardCharsets.UTF_8)
      )
      writeManifest(
        root,
        validManifest().replace(
          "project { id = \"demo\" }",
          "include file(\"identity.conf\")"
        )
      )
      ProjectLoader.load(root).value.id.value shouldBe "demo"

      Files.write(
        parent.resolve("outside.conf"),
        "extensions { escaped = true }\n".getBytes(StandardCharsets.UTF_8)
      )
      writeManifest(root, s"include file(\"../outside.conf\")\n${validManifest()}")
      ProjectLoader.load(root).left.value.errors.map(_.code) shouldBe Vector(
        "manifest_parse_failed"
      )
    }
  }

  test("missing and excessive hardware roles report profile violations") {
    val cases: Vector[(String, String => String)] = Vector(
      "missing CPU" -> (_.replace("      cpu = { tier = 3 }\n", "")),
      "missing memory" -> (_.replace("      memory = [{ tier = 3.5 }]\n", "")),
      "missing GPU" -> (_.replace("      gpu = { tier = 3 }\n", "")),
      "missing EEPROM" -> (_.replace("      eeprom = { builtin = \"lua-bios\" }\n", "")),
      "excess memory" -> (_.replace(
        "      memory = [{ tier = 3.5 }]",
        "      memory = [{ tier = 3 }, { tier = 3 }, { tier = 3 }]"
      ))
    )

    cases.foreach { case (clue, mutate) =>
      withProject(mutate(validManifest())) { root =>
        withClue(clue) {
          ProjectLoader.load(root).left.value.errors.map(_.code) should contain("profile_violation")
        }
      }
    }
  }

  test("tier-1 and tier-2 computer and screen profiles are accepted with legal components") {
    val cases = Vector(
      1 -> validManifest()
        .replace("caseTier = 3", "caseTier = 1")
        .replace("cpu = { tier = 3 }", "cpu = { tier = 1 }")
        .replace("memory = [{ tier = 3.5 }]", "memory = [{ tier = 1.5 }]")
        .replace("gpu = { tier = 3 }", "gpu = { tier = 1 }")
        .replace("tier = 3\n          label", "tier = 1\n          label")
        .replace(
          "cards = [{ kind = \"network\", tier = 2 }]",
          "cards = [{ kind = \"network\", tier = 1 }]"
        )
        .replace("screens { main { tier = 3", "screens { main { tier = 1"),
      2 -> validManifest()
        .replace("caseTier = 3", "caseTier = 2")
        .replace("cpu = { tier = 3 }", "cpu = { tier = 2 }")
        .replace("memory = [{ tier = 3.5 }]", "memory = [{ tier = 2.5 }]")
        .replace("gpu = { tier = 3 }", "gpu = { tier = 2 }")
        .replace("tier = 3\n          label", "tier = 2\n          label")
        .replace("screens { main { tier = 3", "screens { main { tier = 2")
    )

    cases.foreach { case (tier, manifest) =>
      withProject(manifest) { root =>
        withClue(s"tier $tier") {
          val project = ProjectLoader.load(root).value
          project.computers.head.caseTier shouldBe tier
          project.computers.head.hardware.cpuTier shouldBe tier
          project.computers.head.hardware.gpuTier shouldBe tier
          project.screens.head.tier shouldBe tier
        }
      }
    }
  }

  test("hardware tiers and slot counts cannot exceed their case profile") {
    val tierOneWithTierTwoParts = validManifest()
      .replace("caseTier = 3", "caseTier = 1")
      .replace("cpu = { tier = 3 }", "cpu = { tier = 2 }")
      .replace("memory = [{ tier = 3.5 }]", "memory = [{ tier = 1 }, { tier = 1 }]")
      .replace("gpu = { tier = 3 }", "gpu = { tier = 2 }")

    withProject(tierOneWithTierTwoParts) { root =>
      val errors = ProjectLoader.load(root).left.value.errors
      errors.count(_.code == "profile_violation") should be >= 4
    }
  }

  test("service-owned topology limits bound device and connection counts") {
    val manifest = validManifest()
      .replace(
        "screens { main { tier = 3, keyboard = true } }",
        "screens { main { tier = 3, keyboard = true }, aux { tier = 3 } }"
      )
      .replace(
        "connections = [{ from = \"computer:main\", to = \"screen:main\" }]",
        "connections = [{ from = \"computer:main\", to = \"screen:main\" }, { from = \"computer:main\", to = \"screen:aux\" }]"
      )

    withProject(manifest) { root =>
      val errors = ProjectLoader
        .load(root, ServicePolicy(maxScreens = 1, maxConnections = 1))
        .left
        .value
        .errors
      errors.map(_.path) should contain allElementsOf Vector("screens", "connections")
    }
  }

  test("the second disk must fit the tier-2 secondary disk slot") {
    val manifest = validManifest().replace(
      "      cards = [{ kind = \"network\", tier = 2 }]",
      """      disks.z-backup = {
        |        kind = "hdd"
        |        tier = 3
        |        label = "backup"
        |        source = "./computer"
        |        access = "read-write"
        |      }
        |      cards = [{ kind = "network", tier = 2 }]""".stripMargin
    )

    withProject(manifest) { root =>
      ProjectLoader
        .load(root)
        .left
        .value
        .errors
        .map(error => error.path -> error.code) should contain(
        "computers.main.hardware.disks.z-backup.tier" -> "profile_violation"
      )
    }
  }

  test("relative disk paths resolve from the canonical manifest directory") {
    withProject(validManifest()) { root =>
      val source = Files.createDirectories(root.resolve("computer"))

      val project = ProjectLoader.load(root).value

      project.computers.head.hardware.disks.head.source shouldBe source.toRealPath()
    }
  }

  test("read-write traversal outside the project root is rejected") {
    withTempDirectory { parent =>
      val root = Files.createDirectory(parent.resolve("project"))
      Files.createDirectory(parent.resolve("outside"))
      writeManifest(root, validManifest("../outside"))

      val errors = ProjectLoader.load(root).left.value.errors

      errors.map(_.code) should contain("path_not_allowed")
    }
  }

  test("a symlink cannot escape an allowed root") {
    withTempDirectory { parent =>
      val root = Files.createDirectory(parent.resolve("project"))
      val outside = Files.createDirectory(parent.resolve("outside"))
      val link = root.resolve("computer")
      createDirectoryLink(link, outside)
      writeManifest(root, validManifest())

      val errors = ProjectLoader.load(root).left.value.errors

      errors.map(_.code) should contain("path_not_allowed")
    }
  }

  test("an external source requires a canonical service-level allowed root") {
    withTempDirectory { parent =>
      val root = Files.createDirectory(parent.resolve("project"))
      val external = Files.createDirectory(parent.resolve("external"))
      writeManifest(root, validManifest(external.toString.replace('\\', '/')))

      ProjectLoader.load(root).left.value.errors.map(_.code) should contain("path_not_allowed")

      val policy = ServicePolicy(additionalReadWriteRoots = Vector(external))
      ProjectLoader
        .load(root, policy)
        .value
        .computers
        .head
        .hardware
        .disks
        .head
        .source shouldBe external.toRealPath()
    }
  }

  test("invalid service-level roots fail once before construction") {
    withProject(validManifest()) { root =>
      val errors = ProjectLoader
        .load(
          root,
          ServicePolicy(additionalReadWriteRoots = Vector(root.resolve("missing-root")))
        )
        .left
        .value
        .errors

      errors.map(_.path) shouldBe Vector("$service.additionalReadWriteRoots[0]")
      errors.head.code shouldBe "invalid_path"
    }
  }

  test("a manifest cannot grant itself an external root") {
    val manifest = validManifest().replace(
      "runtime {",
      "runtime {\n  allowedRoots = [\"../outside\"]"
    )

    withProject(manifest) { root =>
      ProjectLoader
        .load(root)
        .left
        .value
        .errors
        .map(error => error.path -> error.code) should contain(
        "runtime.allowedRoots" -> "unknown_key"
      )
    }
  }

  test("internet access requires both manifest request and service policy") {
    val manifest = validManifest().replace(
      "runtime {",
      "runtime {\n  internet { http = true, tcp = true }"
    )

    withProject(manifest) { root =>
      val denied = ProjectLoader.load(root).value.runtime.internet
      denied.requestedHttp shouldBe true
      denied.requestedTcp shouldBe true
      denied.httpEnabled shouldBe false
      denied.tcpEnabled shouldBe false

      val allowed = ProjectLoader
        .load(root, ServicePolicy(allowInternetHttp = true, allowInternetTcp = true))
        .value
        .runtime
        .internet
      allowed.httpEnabled shouldBe true
      allowed.tcpEnabled shouldBe true
    }
  }

  private def validManifest(diskSource: String = "./computer"): String =
    s"""schemaVersion = 1
       |project { id = "demo" }
       |runtime { }
       |computers {
       |  main {
       |    caseTier = 3
       |    hardware {
       |      cpu = { tier = 3 }
       |      memory = [{ tier = 3.5 }]
       |      gpu = { tier = 3 }
       |      eeprom = { builtin = "lua-bios" }
       |      disks {
       |        project {
       |          kind = "hdd"
       |          tier = 3
       |          label = "project"
       |          source = "$diskSource"
       |          access = "read-write"
       |        }
       |      }
       |      cards = [{ kind = "network", tier = 2 }]
       |    }
       |  }
       |}
       |screens { main { tier = 3, keyboard = true } }
       |connections = [{ from = "computer:main", to = "screen:main" }]
       |""".stripMargin

  private def withProject(manifest: String)(testBody: Path => Unit): Unit =
    withTempDirectory { root =>
      Files.createDirectories(root.resolve("computer"))
      writeManifest(root, manifest)
      testBody(root)
    }

  private def writeManifest(root: Path, manifest: String): Unit =
    Files.write(root.resolve("ocelot-harness.conf"), manifest.getBytes(StandardCharsets.UTF_8))

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
        process.waitFor() shouldBe 0
    }
  }

  private def withTempDirectory(testBody: Path => Unit): Unit = {
    val root = Files.createTempDirectory("ocelot-harness-project-loader-")
    try testBody(root)
    finally deleteRecursively(root)
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
