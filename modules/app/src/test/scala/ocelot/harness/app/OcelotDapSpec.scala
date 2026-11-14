package ocelot.harness.app

import java.io.{InputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.app.protocol.LoopbackClient

final class OcelotDapSpec extends AnyFunSuite with Matchers {
  test(
    "VS Code's debugger messages stop a running computer on a breakpoint, show its stack and locals, step and continue"
  ) {
    val workDirectory = Files.createTempDirectory("ocelot-harness-dap-")
    val projectDirectory = workDirectory.resolve("project")
    copyTree(fixture, projectDirectory)
    val manifest = projectDirectory.resolve("ocelot-harness.conf")
    Files.write(
      manifest,
      new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
        .replace("schemaVersion = 1", "schemaVersion = 2\nworkspace { kind = \"manifest\" }")
        .replace("tickRate = 20", "tickRate = 20\n  clock { autoStart = true }")
        .getBytes(StandardCharsets.UTF_8)
    )
    val program = projectDirectory.resolve("computer").resolve("main.lua")
    Files.write(program, Program.getBytes(StandardCharsets.UTF_8))
    val root = projectDirectory.toRealPath()
    val programPath = root.resolve("computer").resolve("main.lua").toString

    try {
      val up = runJava(
        workDirectory,
        "up",
        "ocelot.harness.app.HarnessDaemon",
        "up",
        "--project",
        root.toString
      )
      withClue(up.output)(up.exitCode shouldBe 0)
      LoopbackClient
        .call(root, "machine.start", ujson.Obj("computerId" -> "main"))
        .isRight shouldBe true
      eventually(20000L) {
        LoopbackClient
          .call(root, "screen.read", ujson.Obj("screenId" -> "main"))
          .exists(_("text").str.contains("COUNT"))
      } shouldBe true

      val client = new DapClient
      try {
        client
          .request("initialize", ujson.Obj("adapterID" -> "ocelot"))("success")
          .bool shouldBe true
        client.event("initialized")
        client
          .request("attach", ujson.Obj("project" -> root.toString))("success")
          .bool shouldBe true
        val set = client.request(
          "setBreakpoints",
          ujson.Obj(
            "source" -> ujson.Obj("path" -> programPath),
            "breakpoints" -> ujson.Arr(ujson.Obj("line" -> 7))
          )
        )
        set("body")("breakpoints")(0)("verified").bool shouldBe true
        client.request("configurationDone")("success").bool shouldBe true

        client.event("stopped")("body")("reason").str shouldBe "breakpoint"
        val frames =
          client.request("stackTrace", ujson.Obj("threadId" -> 1))("body")("stackFrames").arr
        frames.head("line").num.toInt shouldBe 7
        Paths.get(frames.head("source")("path").str) shouldBe Paths.get(programPath)
        val scopes = client
          .request("scopes", ujson.Obj("frameId" -> frames.head("id").num.toInt))("body")("scopes")
          .arr
        val locals = client
          .request(
            "variables",
            ujson.Obj("variablesReference" -> scopes.head("variablesReference").num.toInt)
          )("body")("variables")
          .arr
        locals.find(_("name").str == "count").map(_("type").str) shouldBe Some("number")
        locals.map(_("name").str).filter(_.startsWith("(")) shouldBe empty
        val state = locals.find(_("name").str == "state").get
        state("type").str shouldBe "table"
        val fields = client
          .request(
            "variables",
            ujson.Obj("variablesReference" -> state("variablesReference").num.toInt)
          )("body")("variables")
          .arr
        fields.find(_("name").str == "label").map(_("value").str) shouldBe Some("\"probe\"")

        client
          .request(
            "setBreakpoints",
            ujson.Obj("source" -> ujson.Obj("path" -> programPath), "breakpoints" -> ujson.Arr())
          )("success")
          .bool shouldBe true
        client.request("next", ujson.Obj("threadId" -> 1))("success").bool shouldBe true
        client.event("stopped")("body")("reason").str shouldBe "step"
        client
          .request("stackTrace", ujson.Obj("threadId" -> 1))("body")("stackFrames")(0)("line")
          .num
          .toInt shouldBe 8

        client.request("continue", ujson.Obj("threadId" -> 1))("success").bool shouldBe true
        val resumedAt = countOnScreen(root)
        eventually(10000L)(countOnScreen(root) > resumedAt) shouldBe true

        client.request("pause", ujson.Obj("threadId" -> 1))("success").bool shouldBe true
        client.event("stopped")("body")("reason").str shouldBe "pause"
        client.request("disconnect")("success").bool shouldBe true
        val released = countOnScreen(root)
        eventually(10000L)(countOnScreen(root) > released) shouldBe true
        LoopbackClient.call(root, "debug.state").map(_("paused").bool) shouldBe Right(false)
        LoopbackClient.call(root, "debug.state").map(_("breakpoints").arr.size) shouldBe Right(0)
      } finally client.close()
    } finally {
      runJava(
        workDirectory,
        "down",
        "ocelot.harness.app.HarnessDaemon",
        "down",
        "--project",
        root.toString
      )
      deleteRecursively(workDirectory)
    }
  }

  private val Program =
    """local gpu = component.proxy(component.list("gpu")())
      |gpu.bind((component.list("screen")()))
      |local count = 0
      |local state = { label = "probe" }
      |while true do
      |  computer.pullSignal(0.05)
      |  count = count + 1
      |  gpu.set(1, 1, "COUNT " .. count .. " " .. state.label .. "   ")
      |end
      |""".stripMargin

  private def countOnScreen(root: Path): Int =
    LoopbackClient
      .call(root, "screen.read", ujson.Obj("screenId" -> "main"))
      .toOption
      .flatMap(screen => "COUNT (\\d+)".r.findFirstMatchIn(screen("text").str))
      .map(_.group(1).toInt)
      .getOrElse(-1)

  /** Plays VS Code's side of the Debug Adapter Protocol against an in-process adapter. */
  private final class DapClient extends AutoCloseable {
    private val toAdapter = new PipedOutputStream()
    private val adapterInput = new PipedInputStream(toAdapter, 1 << 16)
    private val fromAdapter = new PipedInputStream(1 << 20)
    private val adapterOutput = new PipedOutputStream(fromAdapter)
    private val adapter = new DebugAdapter(adapterInput, adapterOutput)
    private val messages = new LinkedBlockingQueue[ujson.Value]()
    private var held = Vector.empty[ujson.Value]
    private var sequence = 0
    private val server = thread("adapter")(adapter.serve())
    private val reader = thread("reader") {
      try {
        var message = read(fromAdapter)
        while (message.nonEmpty) {
          messages.put(message.get)
          message = read(fromAdapter)
        }
      } catch { case _: java.io.IOException => }
    }

    def request(command: String, arguments: ujson.Obj = ujson.Obj()): ujson.Value = {
      sequence += 1
      val bytes = ujson
        .write(
          ujson.Obj(
            "seq" -> sequence,
            "type" -> "request",
            "command" -> command,
            "arguments" -> arguments
          )
        )
        .getBytes(StandardCharsets.UTF_8)
      toAdapter.write(
        s"Content-Length: ${bytes.length}\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
      )
      toAdapter.write(bytes)
      toAdapter.flush()
      val seq = sequence
      next(message => message("type").str == "response" && message("request_seq").num.toInt == seq)
    }

    def event(name: String): ujson.Value =
      next(message => message("type").str == "event" && message("event").str == name)

    private def next(matches: ujson.Value => Boolean): ujson.Value =
      held.find(matches) match {
        case Some(found) =>
          held = held.filterNot(_ eq found)
          found
        case None =>
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
          var found: Option[ujson.Value] = None
          while (found.isEmpty) {
            val remaining = deadline - System.nanoTime()
            val message =
              if (remaining > 0L) messages.poll(remaining, TimeUnit.NANOSECONDS) else null
            if (message == null) fail(s"no matching debug adapter message; held: $held")
            if (matches(message)) found = Some(message) else held :+= message
          }
          found.get
      }

    private def read(input: InputStream): Option[ujson.Value] = {
      val header = new StringBuilder
      var byte = input.read()
      while (byte >= 0 && !header.endsWith("\r\n\r\n")) {
        header.append(byte.toChar)
        if (!header.endsWith("\r\n\r\n")) byte = input.read()
      }
      if (byte < 0) None
      else {
        val length = "Content-Length: (\\d+)".r.findFirstMatchIn(header.toString).get.group(1).toInt
        val bytes = new Array[Byte](length)
        var offset = 0
        while (offset < length) offset += input.read(bytes, offset, length - offset)
        Some(ujson.read(new String(bytes, StandardCharsets.UTF_8)))
      }
    }

    override def close(): Unit = {
      toAdapter.close()
      server.join(10000L)
      adapterOutput.close()
      reader.join(5000L)
    }
  }

  private def thread(name: String)(body: => Unit): Thread = {
    val created = new Thread(() => body, s"dap-test-$name")
    created.setDaemon(true)
    created.start()
    created
  }

  private def runJava(
      work: Path,
      label: String,
      mainClass: String,
      arguments: String*
  ): ProcessResult = {
    val output = work.resolve(label + ".log")
    val java = Paths.get(
      System.getProperty("java.home"),
      "bin",
      if (System.getProperty("os.name").toLowerCase.contains("win")) "java.exe" else "java"
    )
    val process = new ProcessBuilder(
      (Vector(
        java.toString,
        "-cp",
        System.getProperty("java.class.path"),
        mainClass
      ) ++ arguments): _*
    ).redirectErrorStream(true).redirectOutput(output.toFile).start()
    val exited = process.waitFor(45L, TimeUnit.SECONDS)
    if (!exited) {
      process.destroyForcibly()
      process.waitFor(5L, TimeUnit.SECONDS)
    }
    ProcessResult(
      if (exited) process.exitValue() else -1,
      if (Files.exists(output)) new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
      else ""
    )
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
        val target = destination.resolve(source.relativize(path).toString)
        if (Files.isDirectory(path)) Files.createDirectories(target)
        else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally paths.close()
  }

  private def deleteRecursively(root: Path): Unit =
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }

  private def fixture: Path =
    Vector(
      Paths.get("fixtures", "vertical-spike"),
      Paths.get("..", "..", "fixtures", "vertical-spike")
    )
      .map(_.toAbsolutePath.normalize())
      .find(Files.isDirectory(_))
      .getOrElse(fail("vertical-spike fixture not found"))
}
