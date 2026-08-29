package ocelot.harness.app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class RuntimeOwnerProcessSpec extends AnyFunSuite with Matchers {
  test("the real brain initializes and shuts down in a bounded forked JVM") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-brain-probe-")
    val stdoutFile = workDirectory.resolve("stdout.log")
    val stderrFile = workDirectory.resolve("stderr.log")

    try {
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.app.BrainLifecycleProbe",
        workDirectory.resolve("native-libraries").toString,
        workDirectory.resolve("runtime").toString,
        workDirectory.resolve("project").toString
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
      withClue(s"Forked probe stdout:\n$stdout\nstderr:\n$stderr\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        stdout.linesIterator.filter(_.nonEmpty).toVector shouldBe Vector(
          "BRAIN_LIFECYCLE_INITIALIZED version=0.24.2",
          "BRAIN_NATIVE_LUA_AVAILABLE=true",
          "BRAIN_PROJECT_SESSION_OPENED",
          "BRAIN_PROJECT_SESSION_CLOSED",
          "BRAIN_LIFECYCLE_SHUTDOWN",
          "HARNESS_NON_DAEMON_THREADS=0"
        )
        stderr should not include "BRAIN_"
      }
    } finally {
      deleteRecursively(workDirectory)
    }
  }

  private def deleteRecursively(root: Path): Unit = {
    val paths = Files.walk(root)
    try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
    finally paths.close()
  }

  private def javaExecutable: Path = {
    val executable =
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }
}
