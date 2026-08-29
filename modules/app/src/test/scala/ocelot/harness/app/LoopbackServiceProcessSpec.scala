package ocelot.harness.app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{Callable, Executors, TimeUnit}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.app.protocol.{LoopbackClient, LoopbackConnection}

final class LoopbackServiceProcessSpec extends AnyFunSuite with Matchers {
  test("daemon up, authenticated CLI commands, status, and down share one project session") {
    val workDirectory = Files.createTempDirectory("ocelot-harness-loopback-")
    val projectDirectory = workDirectory.resolve("project with spaces")
    copyTree(verticalFixture, projectDirectory)

    try {
      val up = runJava(
        workDirectory,
        "up",
        "ocelot.harness.app.HarnessDaemon",
        "up",
        "--project",
        projectDirectory.toString
      )
      withClue(up.output) {
        up.exitCode shouldBe 0
        up.output should include("started")
      }

      val metadataPath = projectDirectory
        .resolve(".ocelot-harness")
        .resolve("run")
        .resolve("connection.json")
      val metadata =
        ujson.read(new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8))
      metadata("host").str shouldBe "127.0.0.1"
      metadata("port").num.toInt should be > 0
      val serviceToken = metadata("token").str
      serviceToken.length should be >= 40
      metadata("projectId").str shouldBe "vertical-spike"

      val unauthenticated = rawCall(
        metadata("port").num.toInt,
        ujson.Obj("protocolMajor" -> 1, "token" -> "wrong-token")
      )
      unauthenticated("error")("data")("harnessCode").str shouldBe "authentication_failed"

      val status = runJava(
        workDirectory,
        "status",
        "ocelot.harness.app.HarnessDaemon",
        "status",
        "--project",
        projectDirectory.toString,
        "--json"
      )
      withClue(status.output) {
        status.exitCode shouldBe 0
        ujson.read(status.output.trim)("running").bool shouldBe true
      }

      runCtl(workDirectory, projectDirectory, "machine", "start", "main")
      val ready = runCtl(
        workDirectory,
        projectDirectory,
        "screen",
        "wait",
        "main",
        "--contains",
        "READY",
        "--max-ticks",
        "2000",
        "--timeout",
        "10s"
      )
      ready("stopReason")("type").str shouldBe "condition_satisfied"

      val persistent = LoopbackConnection
        .open(projectDirectory)
        .fold(error => fail(error.message), identity)
      try {
        val workspace = persistent
          .call("workspace.describe")
          .fold(error => fail(error.message), value => value)
        workspace("projectId").str shouldBe "vertical-spike"
        val screen = persistent
          .call("screen.read", ujson.Obj("screenId" -> "main"))
          .fold(error => fail(error.message), value => value)
        screen("screenId").str shouldBe "main"
        val concurrentOneShot = LoopbackClient
          .call(projectDirectory, "screen.read", ujson.Obj("screenId" -> "main"))
          .fold(error => fail(error.message), value => value)
        concurrentOneShot("screenId").str shouldBe "main"
      } finally persistent.close()

      val executor = Executors.newFixedThreadPool(4)
      try {
        val calls = (1 to 8).map(_ =>
          new Callable[Either[String, String]] {
            override def call(): Either[String, String] =
              LoopbackClient
                .call(projectDirectory, "screen.read", ujson.Obj("screenId" -> "main"))
                .left
                .map(_.message)
                .map(_("screenId").str)
          }
        )
        executor
          .invokeAll(calls.asJava)
          .asScala
          .foreach(_.get(30L, TimeUnit.SECONDS) shouldBe Right("main"))
      } finally {
        executor.shutdownNow()
        executor.awaitTermination(5L, TimeUnit.SECONDS)
      }

      runCtl(workDirectory, projectDirectory, "screen", "touch", "main", "1", "1")
      val touched = runCtl(
        workDirectory,
        projectDirectory,
        "screen",
        "wait",
        "main",
        "--contains",
        "TOUCHED",
        "--max-ticks",
        "2000",
        "--timeout",
        "10s"
      )
      touched("stopReason")("type").str shouldBe "condition_satisfied"

      val capture = runCtl(
        workDirectory,
        projectDirectory,
        "screen",
        "capture",
        "main",
        "--format",
        "png",
        "--path",
        "screens/loopback.png"
      )
      capture("relativePath").str shouldBe "screens/loopback.png"
      capture.obj.contains("bytes") shouldBe false

      val secondOwner = runJava(
        workDirectory,
        "second-owner",
        "ocelot.harness.app.HarnessDaemon",
        "up",
        "--project",
        projectDirectory.toString
      )
      withClue(secondOwner.output) {
        secondOwner.exitCode should not be 0
        secondOwner.output.toLowerCase should include("already")
      }

      val down = runJava(
        workDirectory,
        "down",
        "ocelot.harness.app.HarnessDaemon",
        "down",
        "--project",
        projectDirectory.toString
      )
      withClue(down.output) {
        down.exitCode shouldBe 0
        down.output should include("stopped")
      }
      eventually(10000L)(!Files.exists(metadataPath)) shouldBe true
      val serviceLog = projectDirectory.resolve(".ocelot-harness/run/service.log")
      if (Files.isRegularFile(serviceLog)) {
        new String(
          Files.readAllBytes(serviceLog),
          StandardCharsets.UTF_8
        ) should not include serviceToken
      }
    } finally {
      val metadata = projectDirectory.resolve(".ocelot-harness/run/connection.json")
      if (Files.exists(metadata)) {
        runJava(
          workDirectory,
          "cleanup-down",
          "ocelot.harness.app.HarnessDaemon",
          "down",
          "--project",
          projectDirectory.toString
        )
      }
      deleteRecursively(workDirectory)
    }
  }

  private def rawCall(port: Int, handshakeParams: ujson.Obj): ujson.Value = {
    val socket = new java.net.Socket("127.0.0.1", port)
    try {
      val writer = new java.io.BufferedWriter(
        new java.io.OutputStreamWriter(socket.getOutputStream, StandardCharsets.UTF_8)
      )
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8)
      )
      writer.write(
        ujson.write(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> 1,
            "method" -> "harness.version",
            "params" -> handshakeParams
          )
        )
      )
      writer.newLine()
      writer.flush()
      ujson.read(reader.readLine())
    } finally socket.close()
  }

  private def runCtl(work: Path, project: Path, command: String*): ujson.Value = {
    val result = runJava(
      work,
      "ctl-" + command.mkString("-"),
      "ocelot.harness.app.OcelotCtl",
      (Vector("--project", project.toString, "--json") ++ command): _*
    )
    withClue(result.output) {
      result.exitCode shouldBe 0
    }
    ujson.read(result.output.trim)
  }

  private def runJava(
      work: Path,
      label: String,
      mainClass: String,
      arguments: String*
  ): ProcessResult = {
    val output = work.resolve(label.replaceAll("[^A-Za-z0-9-]", "_") + ".log")
    val command = Vector(
      javaExecutable.toString,
      "-cp",
      System.getProperty("java.class.path"),
      mainClass
    ) ++ arguments
    val process = new ProcessBuilder(command: _*)
      .redirectErrorStream(true)
      .redirectOutput(output.toFile)
      .start()
    val exited = process.waitFor(45L, TimeUnit.SECONDS)
    if (!exited) {
      process.destroyForcibly()
      process.waitFor(5L, TimeUnit.SECONDS)
    }
    val text =
      if (Files.exists(output)) new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
      else ""
    ProcessResult(if (exited) process.exitValue() else -1, text)
  }

  private def eventually(timeoutMillis: Long)(condition: => Boolean): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    var result = condition
    while (!result && System.nanoTime() < deadline) {
      Thread.sleep(25L)
      result = condition
    }
    result
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
    candidates.find(Files.isDirectory(_)).getOrElse(fail("vertical-spike fixture not found"))
  }

  private def javaExecutable: Path = {
    val executable =
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }

}

private final case class ProcessResult(exitCode: Int, output: String)
