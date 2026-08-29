package ocelot.harness.app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class BrainLifecycleProbeSpec extends AnyFunSuite with Matchers {
  test("the real brain initializes and shuts down in a bounded forked JVM") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-brain-probe-")
    val outputFile = workDirectory.resolve("probe.log")

    try {
      val process = new ProcessBuilder(
        javaExecutable.toString,
        "-cp",
        System.getProperty("java.class.path"),
        "ocelot.harness.app.BrainLifecycleProbe",
        workDirectory.resolve("native-libraries").toString
      )
        .redirectErrorStream(true)
        .redirectOutput(outputFile.toFile)
        .start()

      val exited = process.waitFor(30L, TimeUnit.SECONDS)
      if (!exited) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }

      val output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
      withClue(s"Forked probe output:\n$output\n") {
        exited shouldBe true
        process.exitValue() shouldBe 0
        output should include("BRAIN_LIFECYCLE_INITIALIZED version=0.24.2")
        output should include("BRAIN_NATIVE_LUA_AVAILABLE=true")
        output should include("BRAIN_LIFECYCLE_SHUTDOWN")
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
