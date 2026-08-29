package ocelot.harness.app

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{FutureTask, TimeUnit}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class StdioServiceProcessSpec extends AnyFunSuite with Matchers {
  test("the packaged stdio service drives the vertical project with protocol-only stdout") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-stdio-")
    val projectDirectory = workDirectory.resolve("project with spaces")
    val stderrFile = workDirectory.resolve("stderr.log")
    copyTree(verticalFixture, projectDirectory)

    val process = new ProcessBuilder(
      javaExecutable.toString,
      "-cp",
      System.getProperty("java.class.path"),
      "ocelot.harness.app.HarnessDaemon",
      "serve",
      "--stdio",
      "--project",
      projectDirectory.toString
    ).redirectError(stderrFile.toFile).start()

    val writer =
      new BufferedWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))
    val reader =
      new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))

    try {
      val version = call(writer, reader, 1, "harness.version", ujson.Obj("protocolMajor" -> 1))
      version("result")("protocolMajor").num.toInt shouldBe 1

      val description = call(writer, reader, 2, "workspace.describe")
      description("result")("projectId").str shouldBe "vertical-spike"

      call(writer, reader, 3, "machine.start", ujson.Obj("computerId" -> "main"))
      val run = call(
        writer,
        reader,
        4,
        "simulation.run",
        ujson.Obj(
          "condition" -> ujson.Obj(
            "type" -> "screen_contains",
            "screenId" -> "main",
            "text" -> "READY"
          ),
          "maxTicks" -> 2000,
          "timeoutMillis" -> 10000
        )
      )
      run("result")("stopReason")("type").str shouldBe "condition_satisfied"

      val diagnostics = call(
        writer,
        reader,
        5,
        "diagnostics.collect",
        ujson.Obj("path" -> "diagnostics/stdio.zip")
      )
      diagnostics("result")("artifact")("relativePath").str shouldBe "diagnostics/stdio.zip"
      diagnostics("result")("artifact").obj.contains("bytes") shouldBe false

      call(writer, reader, 6, "service.shutdown")("result")("shuttingDown").bool shouldBe true
      writer.close()

      process.waitFor(30L, TimeUnit.SECONDS) shouldBe true
      val stderr = new String(Files.readAllBytes(stderrFile), StandardCharsets.UTF_8)
      withClue(s"service stderr:\n$stderr\n") {
        process.exitValue() shouldBe 0
        stderr should not include "\"jsonrpc\""
      }
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(5L, TimeUnit.SECONDS)
      }
      deleteRecursively(workDirectory)
    }
  }

  private def call(
      writer: BufferedWriter,
      reader: BufferedReader,
      id: Int,
      method: String,
      params: ujson.Obj = ujson.Obj()
  ): ujson.Value = {
    writer.write(
      ujson.write(
        ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)
      )
    )
    writer.newLine()
    writer.flush()
    val line = readLine(reader, 30L)
    val response = ujson.read(line)
    response("jsonrpc").str shouldBe "2.0"
    response("id").num.toInt shouldBe id
    withClue(s"response: $line") {
      response.obj.contains("error") shouldBe false
    }
    response
  }

  private def readLine(reader: BufferedReader, timeoutSeconds: Long): String = {
    val task = new FutureTask[String](() => reader.readLine())
    val thread = new Thread(task, "ocelot-harness-test-line-reader")
    thread.setDaemon(true)
    thread.start()
    Option(task.get(timeoutSeconds, TimeUnit.SECONDS)).getOrElse(fail("service stdout closed"))
  }

  private def copyTree(source: Path, destination: Path): Unit = {
    val paths = Files.walk(source)
    try {
      paths.iterator().asScala.foreach { path =>
        val target = destination.resolve(source.relativize(path))
        if (Files.isDirectory(path)) Files.createDirectories(target)
        else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally paths.close()
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  private def verticalFixture: Path = {
    val candidates = Vector(
      Paths.get("fixtures", "vertical-spike"),
      Paths.get("..", "..", "fixtures", "vertical-spike")
    ).map(_.toAbsolutePath.normalize())
    candidates
      .find(Files.isDirectory(_))
      .getOrElse(
        fail(s"vertical-spike fixture not found in ${candidates.mkString(", ")}")
      )
  }

  private def javaExecutable: Path = {
    val executable =
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }
}
