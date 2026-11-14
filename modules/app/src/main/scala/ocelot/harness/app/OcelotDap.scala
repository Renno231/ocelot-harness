package ocelot.harness.app

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import scala.collection.mutable
import scala.util.control.NonFatal

import ocelot.harness.app.protocol.{LoopbackConnection, RpcClientFailure}

/** A VS Code debug adapter for a running harness: breakpoints in a project's Lua files, the paused
  * computer's stack and variables, and stepping. It speaks the Debug Adapter Protocol on
  * stdin/stdout and the harness protocol to the project's running service.
  *
  * Launch and attach both connect to `ocelot-harnessd` for the project given as `project`; the
  * service must be up. Stop the session to leave breakpoints cleared and the computer running.
  */
object OcelotDap {
  def main(arguments: Array[String]): Unit = {
    val adapter = new DebugAdapter(System.in, System.out)
    adapter.serve()
    System.exit(0)
  }
}

private[app] final class DebugAdapter(input: InputStream, output: OutputStream) {
  private val reader = new BufferedInputStream(input)
  private val sequence = new AtomicLong(0L)
  private val running = new AtomicBoolean(true)
  @volatile private var commands: Option[LoopbackConnection] = None
  private val files = mutable.LinkedHashSet.empty[String]
  // DAP variable references are integers; the harness's are strings, valid while paused.
  private val references = mutable.ArrayBuffer.empty[String]
  @volatile private var watcher: Option[Thread] = None

  def serve(): Unit = {
    try {
      var message = readMessage()
      while (running.get() && message.nonEmpty) {
        handle(message.get)
        if (running.get()) message = readMessage()
      }
    } finally shutdown()
  }

  private def handle(message: ujson.Value): Unit = {
    val command = message.obj.get("command").map(_.str).getOrElse("")
    val arguments =
      message.obj.get("arguments").collect { case value: ujson.Obj => value }.getOrElse(ujson.Obj())
    val result: Either[String, ujson.Value] =
      try dispatch(command, arguments)
      catch { case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.toString)) }
    respond(message, command, result)
    command match {
      case "initialize" => event("initialized", ujson.Obj())
      case "disconnect" => running.set(false)
      case _            =>
    }
  }

  private def dispatch(command: String, arguments: ujson.Obj): Either[String, ujson.Value] =
    command match {
      case "initialize" =>
        Right(
          ujson.Obj(
            "supportsConfigurationDoneRequest" -> true,
            "supportsTerminateRequest" -> true
          )
        )
      case "launch" | "attach" => connect(arguments)
      case "setBreakpoints" =>
        val path = arguments("source")("path").str
        val lines = arguments.obj
          .get("breakpoints")
          .map(_.arr.map(_("line").num.toInt).toVector)
          .getOrElse(Vector.empty)
        call("debug.breakpoints", ujson.Obj("file" -> path, "lines" -> ujson.Arr.from(lines))).map {
          result =>
            synchronized(if (lines.isEmpty) files -= path else files += path)
            val set = result("breakpoints").arr.map(_("line").num.toInt).toSet
            ujson.Obj(
              "breakpoints" -> ujson.Arr.from(
                lines.map(line => ujson.Obj("verified" -> set.contains(line), "line" -> line))
              )
            )
        }
      case "configurationDone" => Right(ujson.Obj())
      case "threads" =>
        Right(ujson.Obj("threads" -> ujson.Arr(ujson.Obj("id" -> 1, "name" -> "computers"))))
      case "stackTrace" =>
        call("debug.frames").map { result =>
          val frames = result("frames").arr.map { frame =>
            val source = frame("hostPath") match {
              case ujson.Str(path) =>
                ujson.Obj("name" -> Paths.get(path).getFileName.toString, "path" -> path)
              case _ => ujson.Obj("name" -> frame("source").str)
            }
            ujson.Obj(
              "id" -> frame("index").num.toInt,
              "name" -> frame("function").str,
              "source" -> source,
              "line" -> frame("line").num.toInt,
              "column" -> 1
            )
          }
          ujson.Obj("stackFrames" -> ujson.Arr.from(frames), "totalFrames" -> frames.size)
        }
      case "scopes" =>
        val frame = arguments("frameId").num.toInt
        Right(
          ujson.Obj(
            "scopes" -> ujson.Arr(
              ujson.Obj(
                "name" -> "Locals",
                "variablesReference" -> reference(s"$frame:L"),
                "expensive" -> false
              ),
              ujson.Obj(
                "name" -> "Upvalues",
                "variablesReference" -> reference(s"$frame:U"),
                "expensive" -> false
              )
            )
          )
        )
      case "variables" =>
        val id = arguments("variablesReference").num.toInt
        synchronized(references.lift(id - 1)).toRight(s"unknown variables reference $id").flatMap {
          harnessReference =>
            call("debug.variables", ujson.Obj("reference" -> harnessReference)).map { result =>
              val values = result("variables").arr.map { variable =>
                ujson.Obj(
                  "name" -> variable("name").str,
                  "value" -> variable("value").str,
                  "type" -> variable("type").str,
                  "variablesReference" -> (variable("reference") match {
                    case ujson.Str(value) => reference(value)
                    case _                => 0
                  })
                )
              }
              ujson.Obj("variables" -> ujson.Arr.from(values))
            }
        }
      case "continue" => resume("continue").map(_ => ujson.Obj("allThreadsContinued" -> true))
      case "next"     => resume("over").map(_ => ujson.Obj())
      case "stepIn"   => resume("in").map(_ => ujson.Obj())
      case "stepOut"  => resume("out").map(_ => ujson.Obj())
      case "pause"    => call("debug.pause").map(_ => ujson.Obj())
      case "disconnect" | "terminate" =>
        clearAndRelease()
        Right(ujson.Obj())
      case other => Left(s"unsupported request: $other")
    }

  private def connect(arguments: ujson.Obj): Either[String, ujson.Value] =
    arguments.obj.get("project") match {
      case Some(ujson.Str(path)) =>
        val root = Paths.get(path).toAbsolutePath.normalize()
        LoopbackConnection.open(root).left.map(failure).map { connection =>
          commands = Some(connection)
          startWatcher(root)
          ujson.Obj()
        }
      case _ => Left("set \"project\" to the project folder in the launch configuration")
    }

  /** Watches for pauses on its own connection and tells VS Code where the computer stopped. */
  private def startWatcher(root: Path): Unit = {
    val thread = new Thread(
      () =>
        LoopbackConnection.open(root) match {
          case Left(error) => output(s"debugger cannot watch the harness: ${failure(error)}")
          case Right(connection) =>
            try {
              // A computer already stopped when VS Code attaches is reported as stopped too.
              var seen = connection
                .call("debug.state")
                .map(state =>
                  state("pauseSequence").str.toLong - (if (state("paused").bool) 1L else 0L)
                )
                .getOrElse(0L)
              while (running.get()) {
                connection.call(
                  "debug.state",
                  ujson.Obj("afterSequence" -> seen, "waitMillis" -> 1000)
                ) match {
                  case Right(state) =>
                    val current = state("pauseSequence").str.toLong
                    if (state("paused").bool && current > seen) {
                      seen = current
                      synchronized(references.clear())
                      event(
                        "stopped",
                        ujson.Obj(
                          "reason" -> (state("reason").str match {
                            case "breakpoint" => "breakpoint"
                            case "step"       => "step"
                            case _            => "pause"
                          }),
                          "description" -> s"${state("computerId").str} paused",
                          "threadId" -> 1,
                          "allThreadsStopped" -> true
                        )
                      )
                    }
                  case Left(error) =>
                    if (running.get()) {
                      output(s"lost the harness: ${failure(error)}")
                      event("terminated", ujson.Obj())
                      running.set(false)
                    }
                }
              }
            } finally connection.close()
        },
      "ocelot-dap-watcher"
    )
    thread.setDaemon(true)
    watcher = Some(thread)
    thread.start()
  }

  private def resume(mode: String): Either[String, ujson.Value] = {
    synchronized(references.clear())
    call("debug.resume", ujson.Obj("mode" -> mode))
  }

  private def reference(value: String): Int = synchronized {
    references += value
    references.size
  }

  private def clearAndRelease(): Unit = {
    val paths = synchronized(files.toVector)
    paths.foreach(path =>
      call("debug.breakpoints", ujson.Obj("file" -> path, "lines" -> ujson.Arr()))
    )
    call("debug.state").foreach { state =>
      if (state("paused").bool) call("debug.resume", ujson.Obj("mode" -> "continue"))
    }
  }

  private def call(method: String, params: ujson.Obj = ujson.Obj()): Either[String, ujson.Value] =
    commands
      .toRight("not connected to a harness; start with launch or attach")
      .flatMap(
        _.call(method, params).left.map(failure)
      )

  private def failure(error: RpcClientFailure): String = error.message

  private def shutdown(): Unit = {
    running.set(false)
    commands.foreach(_.close())
    watcher.foreach(_.join(3000L))
  }

  private def output(text: String): Unit =
    event("output", ujson.Obj("category" -> "console", "output" -> (text + "\n")))

  private def respond(
      request: ujson.Value,
      command: String,
      result: Either[String, ujson.Value]
  ): Unit = {
    val body = ujson.Obj(
      "type" -> "response",
      "request_seq" -> request.obj.get("seq").getOrElse(ujson.Num(0)),
      "command" -> command,
      "success" -> result.isRight
    )
    result match {
      case Right(value)  => body("body") = value
      case Left(message) => body("message") = message
    }
    send(body)
  }

  private def event(name: String, body: ujson.Obj): Unit =
    send(ujson.Obj("type" -> "event", "event" -> name, "body" -> body))

  private def send(message: ujson.Obj): Unit = output.synchronized {
    message("seq") = sequence.incrementAndGet().toDouble
    val bytes = ujson.write(message).getBytes(StandardCharsets.UTF_8)
    output.write(s"Content-Length: ${bytes.length}\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
    output.write(bytes)
    output.flush()
  }

  private def readMessage(): Option[ujson.Value] = {
    var length = -1
    var line = readHeaderLine()
    while (line.exists(_.nonEmpty)) {
      val text = line.get
      if (text.toLowerCase.startsWith("content-length:"))
        length = text.substring("content-length:".length).trim.toInt
      line = readHeaderLine()
    }
    if (line.isEmpty || length < 0 || length > DebugAdapter.MaxMessageBytes) None
    else {
      val bytes = new Array[Byte](length)
      var read = 0
      while (read < length) {
        val count = reader.read(bytes, read, length - read)
        if (count < 0) return None
        read += count
      }
      Some(ujson.read(new String(bytes, StandardCharsets.UTF_8)))
    }
  }

  private def readHeaderLine(): Option[String] = {
    val buffer = new ByteArrayOutputStream()
    var byte = reader.read()
    while (byte >= 0 && byte != '\n') {
      if (byte != '\r' && buffer.size() < 1024) buffer.write(byte)
      byte = reader.read()
    }
    if (byte < 0 && buffer.size() == 0) None
    else Some(new String(buffer.toByteArray, StandardCharsets.US_ASCII))
  }
}

private[app] object DebugAdapter {
  val MaxMessageBytes = 1024 * 1024
}
