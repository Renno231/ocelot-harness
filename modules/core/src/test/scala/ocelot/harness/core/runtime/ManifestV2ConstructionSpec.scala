package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.project.{ComputerId, ProjectTemplates, ScreenId}
import ocelot.harness.core.workspace.{
  HostDiskSnapshotPolicy,
  RunRequest,
  ScreenContains,
  SnapshotName,
  SnapshotRequest,
  TickPace
}

final class ManifestV2ConstructionSpec extends AnyFunSuite with Matchers {
  test("a generated mixed manifest constructs and operates the supported original-node topology") {
    val root = Files.createTempDirectory("ocelot-harness-manifest-v2-")
    val stdout = root.resolve("stdout.log")
    val stderr = root.resolve("stderr.log")
    try {
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.core.runtime.ManifestV2ConstructionProbe",
        root.toString
      ).redirectOutput(stdout.toFile).redirectError(stderr.toFile).start()
      val exited = process.waitFor(60L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      val out = new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8)
      val err = new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8)
      withClue(s"stdout:\n$out\nstderr:\n$err") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        out should include("MANIFEST_V2_DEVICES=13")
        out should include("MANIFEST_V2_COMPUTERS=3")
        out should include("MANIFEST_V2_MAIN_READY=true")
        out should include("MANIFEST_V2_SERVER_READY=true")
        out should include("MANIFEST_V2_RESTORE=true")
        out should include("MANIFEST_V2_SERVER_RESTART=true")
        out should include("MANIFEST_V2_SHUTDOWN=true")
      }
    } finally deleteRecursively(root)
  }

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

private[runtime] object ManifestV2ConstructionProbe {
  def main(arguments: Array[String]): Unit = {
    val root = Paths.get(arguments(0)).toAbsolutePath.normalize()
    val projectRoot = root.resolve("project")
    ProjectTemplates
      .initialize(projectRoot, "mixed-network")
      .fold(
        error => throw new IllegalStateException(error.message),
        identity
      )
    val owner = RuntimeOwner
      .start(
        RuntimeConfig(root.resolve("runtime"), root.resolve("native"))
      )
      .fold(error => throw new IllegalStateException(error.message), identity)
    try {
      val session = owner
        .openProject(projectRoot)
        .fold(
          error => throw new IllegalStateException(s"${error.code}: ${error.message}"),
          identity
        )
      try {
        val description = session.describe()
        println(s"MANIFEST_V2_DEVICES=${description.devices.size}")
        println(s"MANIFEST_V2_COMPUTERS=${description.computers.size}")
        Vector("main", "server", "controller").foreach { raw =>
          val id = ComputerId.parse(raw).toOption.get
          session
            .startMachine(id)
            .fold(error => throw new IllegalStateException(error.message), identity)
        }
        val main = session
          .run(
            RunRequest(
              ScreenContains(ScreenId.parse("main-screen").toOption.get, "MAIN"),
              2000,
              15.seconds,
              TickPace.Accelerated
            )
          )
          .fold(error => throw new IllegalStateException(error.message), identity)
        val server = session
          .run(
            RunRequest(
              ScreenContains(ScreenId.parse("server-screen").toOption.get, "SERVER"),
              2000,
              15.seconds,
              TickPace.Accelerated
            )
          )
          .fold(error => throw new IllegalStateException(error.message), identity)
        println(s"MANIFEST_V2_MAIN_READY=${main.screens.values.exists(_.text.contains("MAIN"))}")
        println(
          s"MANIFEST_V2_SERVER_READY=${server.screens.values.exists(_.text.contains("SERVER"))}"
        )
        val snapshotName = SnapshotName.parse("manifest-v2").toOption.get
        session
          .saveSnapshot(
            SnapshotRequest(snapshotName, HostDiskSnapshotPolicy.ReferenceOnly, 64L * 1024L * 1024L)
          )
          .fold(error => throw new IllegalStateException(error.message), identity)
        val restored = session
          .loadSnapshot(snapshotName)
          .fold(
            error => throw new IllegalStateException(s"${error.code}: ${error.message}"),
            identity
          )
        println(s"MANIFEST_V2_RESTORE=${restored.devices.size == 13}")
        val serverId = ComputerId.parse("server").toOption.get
        session
          .resetMachine(serverId)
          .fold(
            error => throw new IllegalStateException(error.message),
            identity
          )
        val restarted = session
          .run(
            RunRequest(
              ScreenContains(ScreenId.parse("server-screen").toOption.get, "SERVER"),
              2000,
              15.seconds,
              TickPace.Accelerated
            )
          )
          .fold(error => throw new IllegalStateException(error.message), identity)
        println(
          s"MANIFEST_V2_SERVER_RESTART=${restarted.screens.values.exists(_.text.contains("SERVER"))}"
        )
      } finally session.close()
    } finally {
      owner.close()
      println("MANIFEST_V2_SHUTDOWN=true")
    }
  }
}
