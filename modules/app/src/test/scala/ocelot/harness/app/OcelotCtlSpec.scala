package ocelot.harness.app

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.app.protocol.AppExitCode
import totoro.ocelot.brain.nbt.{CompressedStreamTools, NBTBase, NBTTagCompound}

final class OcelotCtlSpec extends AnyFunSuite with Matchers {
  test("checked-in CLI reference is generated from the executable command contract") {
    val candidates = Vector(
      Paths.get("docs", "reference", "cli.md"),
      Paths.get("..", "..", "docs", "reference", "cli.md")
    ).map(_.toAbsolutePath.normalize())
    val reference =
      candidates.find(Files.isRegularFile(_)).getOrElse(fail("CLI reference is missing"))

    new String(Files.readAllBytes(reference), StandardCharsets.UTF_8) shouldBe
      OcelotCtl.ReferenceMarkdown
  }

  test("release metadata records the packaged graph, notices, and security override") {
    val roots = Vector(Paths.get("."), Paths.get("..", "..")).map(_.toAbsolutePath.normalize())
    val root = roots
      .find(path => Files.isRegularFile(path.resolve("build.sbt")))
      .getOrElse(
        fail("repository root is missing")
      )
    val sbom = ujson.read(
      new String(
        Files.readAllBytes(root.resolve("docs").resolve("release").resolve("sbom.cdx.json")),
        StandardCharsets.UTF_8
      )
    )
    sbom("bomFormat").str shouldBe "CycloneDX"
    sbom("specVersion").str shouldBe "1.6"
    val components =
      sbom("components").arr.map(value => value("name").str -> value("version").str).toMap
    components("ocelot-brain") shouldBe "0.24.2"
    components("log4j-api") shouldBe "2.25.5"
    components("log4j-core") shouldBe "2.25.5"

    val notices = new String(
      Files.readAllBytes(root.resolve("THIRD_PARTY_NOTICES.md")),
      StandardCharsets.UTF_8
    )
    notices should include("LICENSE-unifont")
    notices should include("LICENSE-oc")
    val dependencies = new String(
      Files.readAllBytes(root.resolve("docs").resolve("release").resolve("dependencies.md")),
      StandardCharsets.UTF_8
    )
    dependencies should include("GHSA-qv9r-c865-cp47")
    dependencies should include("no remaining advisories")
  }

  test("CLI options reject unknown and duplicate flags") {
    OcelotCtl.run(
      Array("screen", "wait", "main", "--contains", "READY", "--typo", "1")
    ) shouldBe AppExitCode.Usage
    OcelotCtl.run(
      Array(
        "screen",
        "wait",
        "main",
        "--contains",
        "READY",
        "--contains",
        "AGAIN"
      )
    ) shouldBe AppExitCode.Usage
  }

  test("global parsing stops at the command so option-like input remains data") {
    val project = Files.createTempDirectory("ocelot-harness-cli-parse-")
    try {
      OcelotCtl.run(
        Array("--project", project.toString, "screen", "paste", "main", "--json")
      ) shouldBe AppExitCode.Connection
    } finally Files.delete(project)
  }

  test("local Desktop inspect, import, and project validation do not require a daemon") {
    val parent = Files.createTempDirectory("ocelot-harness-local-project-cli-")
    val source = Files.createDirectory(parent.resolve("desktop-source"))
    val destination = parent.resolve("imported")
    val back = new NBTTagCompound()
    back.setTagList("entities", Vector.empty[NBTBase].asJava)
    back.setTagList("edges", Vector.empty[NBTBase].asJava)
    val root = new NBTTagCompound()
    root.setTag("back", back)
    root.setTag("front", new NBTTagCompound())
    Files.write(source.resolve("workspace.nbt"), CompressedStreamTools.write(root))

    try {
      val (inspectExit, inspectOut) = capture(
        OcelotCtl.run(Array("project", "inspect-desktop", source.toString, "--json"))
      )
      inspectExit shouldBe AppExitCode.Success
      ujson.read(inspectOut)("entityCount").num shouldBe 0

      val (importExit, importOut) = capture(
        OcelotCtl.run(
          Array("project", "import-desktop", source.toString, destination.toString, "--json")
        )
      )
      importExit shouldBe AppExitCode.Success
      ujson.read(importOut)("projectRoot").str shouldBe destination.toRealPath().toString

      val (validateExit, validateOut) = capture(
        OcelotCtl.run(Array("--project", destination.toString, "project", "validate", "--json"))
      )
      validateExit shouldBe AppExitCode.Success
      ujson.read(validateOut)("schemaVersion").num shouldBe 2
    } finally {
      val paths = Files.walk(parent)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  test("project init writes bounded schema-v2 templates that validate without repair") {
    val parent = Files.createTempDirectory("ocelot-harness-project-init-")
    try {
      Vector("single-computer", "two-computers", "rack-server", "mixed-network").foreach {
        template =>
          val destination = parent.resolve(template)
          val (initExit, initOut) = capture(
            OcelotCtl.run(
              Array("project", "init", destination.toString, "--template", template, "--json")
            )
          )
          withClue(s"template $template output: $initOut") {
            initExit shouldBe AppExitCode.Success
            Files.isRegularFile(destination.resolve("ocelot-harness.conf")) shouldBe true
            val (validateExit, validateOut) = capture(
              OcelotCtl.run(
                Array("--project", destination.toString, "project", "validate", "--json")
              )
            )
            validateExit shouldBe AppExitCode.Success
            ujson.read(validateOut)("schemaVersion").num shouldBe 2
          }
      }
    } finally {
      val paths = Files.walk(parent)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  test("project init refuses an occupied destination and unknown templates") {
    val parent = Files.createTempDirectory("ocelot-harness-project-init-invalid-")
    try {
      val occupied = Files.createDirectory(parent.resolve("occupied"))
      Files.write(occupied.resolve("keep.txt"), Array[Byte](1))
      OcelotCtl.run(
        Array("project", "init", occupied.toString, "--template", "single-computer")
      ) shouldBe AppExitCode.Domain
      OcelotCtl.run(
        Array("project", "init", parent.resolve("unknown").toString, "--template", "nope")
      ) shouldBe AppExitCode.Usage
      Files.readAllBytes(occupied.resolve("keep.txt")) shouldBe Array[Byte](1)
    } finally {
      val paths = Files.walk(parent)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  test("CLI durations reject numeric overflow") {
    OcelotCtl.run(
      Array(
        "screen",
        "wait",
        "main",
        "--contains",
        "READY",
        "--timeout",
        "999999999999999999999s"
      )
    ) shouldBe AppExitCode.Usage
  }

  private def capture(operation: => Int): (Int, String) = {
    val stdout = new ByteArrayOutputStream()
    val stderr = new ByteArrayOutputStream()
    val originalOut = System.out
    val originalErr = System.err
    try {
      System.setOut(new PrintStream(stdout, true, "UTF-8"))
      System.setErr(new PrintStream(stderr, true, "UTF-8"))
      val exit = operation
      exit -> new String(stdout.toByteArray, StandardCharsets.UTF_8).trim
    } finally {
      System.setOut(originalOut)
      System.setErr(originalErr)
    }
  }
}
