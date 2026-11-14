package ocelot.harness.app

import java.io.{BufferedReader, ByteArrayOutputStream, InputStream, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}

import scala.annotation.tailrec
import scala.util.Try

import ocelot.harness.app.protocol.{AppExitCode, LoopbackClient}
import ocelot.harness.core.project.{
  DesktopWorkspaceInspection,
  DesktopWorkspaceProjects,
  ProjectErrors,
  ProjectLoader,
  ProjectTemplates,
  WorkspaceSourceDefinition
}

object OcelotCtl {
  def main(arguments: Array[String]): Unit = {
    val exitCode = run(arguments)
    if (exitCode != AppExitCode.Success) System.exit(exitCode)
  }

  private[app] def run(arguments: Array[String]): Int =
    if (arguments.toVector == Vector("--help") || arguments.toVector == Vector("help")) {
      System.out.print(ReferenceMarkdown)
      AppExitCode.Success
    } else if (arguments.toVector == Vector("batch")) runBatch(System.in, System.out)
    else
      parseGlobal(arguments.toVector) match {
        case Left(message) => fail(AppExitCode.Usage, message)
        case Right(global) if global.arguments.headOption.contains("project") =>
          runProjectCommand(global)
        case Right(global) =>
          parseCommand(global.arguments) match {
            case Left(message) => fail(AppExitCode.Usage, message)
            case Right(request) =>
              LoopbackClient.call(
                global.projectRoot,
                request.method,
                request.paramsFor(global.json)
              ) match {
                case Left(error) => fail(error.exitCode, error.message, error.harnessCode)
                case Right(result) =>
                  printResult(global.json, result, request.human)
                  AppExitCode.Success
              }
          }
      }

  // One process for many commands, so a caller that drives the harness step by step pays for one
  // JVM start: each stdin line is a JSON array of ocelotctl arguments, and each answer is one line
  // {"exit": <code>, "stdout": <text>, "stderr": <text>}. Commands run one at a time, in order.
  private[app] def runBatch(input: InputStream, output: PrintStream): Int = {
    val reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
    Iterator
      .continually(reader.readLine())
      .takeWhile(_ != null)
      .map(_.stripPrefix("\uFEFF")) // .NET callers' stdin may start with a byte order mark
      .filter(_.trim.nonEmpty)
      .foreach { line =>
        val arguments = Try(ujson.read(line).arr.map(_.str).toArray).toEither
        val (exit, stdout, stderr) = captured {
          arguments match {
            case Left(error) =>
              fail(
                AppExitCode.Usage,
                s"batch lines are JSON arrays of arguments: ${error.getMessage}"
              )
            case Right(values) if values.toVector == Vector("batch") =>
              fail(AppExitCode.Usage, "batch does not nest")
            case Right(values) => run(values)
          }
        }
        output.println(
          ujson.write(
            ujson.Obj("exit" -> exit, "stdout" -> stdout, "stderr" -> stderr),
            escapeUnicode = true // ASCII lines, whatever the console's code page
          )
        )
        output.flush()
      }
    AppExitCode.Success
  }

  private def captured(body: => Int): (Int, String, String) = {
    val (out, err) = (new ByteArrayOutputStream(), new ByteArrayOutputStream())
    val (savedOut, savedErr) = (System.out, System.err)
    System.setOut(new PrintStream(out, true, "UTF-8"))
    System.setErr(new PrintStream(err, true, "UTF-8"))
    val exit =
      try body
      finally {
        System.setOut(savedOut)
        System.setErr(savedErr)
      }
    (exit, out.toString("UTF-8"), err.toString("UTF-8"))
  }

  private def runProjectCommand(global: Global): Int = {
    val trailingJson = global.arguments.lastOption.contains("--json")
    val arguments = if (trailingJson) global.arguments.dropRight(1) else global.arguments
    val result = arguments match {
      case Vector("project", "inspect-desktop", source) =>
        DesktopWorkspaceProjects
          .inspect(Paths.get(source).toAbsolutePath.normalize())
          .map(value => inspectionJson(value) -> inspectionHuman(value))
      case Vector("project", "import-desktop", source, destination) =>
        DesktopWorkspaceProjects
          .importProject(
            Paths.get(source).toAbsolutePath.normalize(),
            Paths.get(destination).toAbsolutePath.normalize()
          )
          .map { imported =>
            val json = ujson.Obj(
              "projectRoot" -> imported.projectRoot.toString,
              "inspection" -> inspectionJson(imported.inspection)
            )
            json -> s"imported ${imported.projectRoot}"
          }
      case Vector("project", "init", destination, "--template", template) =>
        ProjectTemplates.initialize(Paths.get(destination), template) match {
          case Left(error) if error.code == "unknown_template" =>
            return fail(AppExitCode.Usage, error.message, Some(error.code))
          case Left(error) =>
            return fail(AppExitCode.Domain, error.message, Some(error.code))
          case Right(projectRoot) =>
            val json = ujson.Obj("projectRoot" -> projectRoot.toString, "template" -> template)
            Right(json -> s"initialized $projectRoot")
        }
      case Vector("project", "validate") =>
        validateProject(global.projectRoot).map { case (project, inspection) =>
          val kind = project.workspaceSource match {
            case WorkspaceSourceDefinition.Manifest   => "manifest"
            case _: WorkspaceSourceDefinition.Desktop => "desktop"
          }
          val computerCount = inspection.fold(
            project.manifestTopology.fold(project.computers.size)(
              _.devices.count(device =>
                Set("computer", "server", "microcontroller").contains(device.kind.name)
              )
            )
          )(_.computers.size)
          val screenCount = inspection.fold(
            project.manifestTopology.fold(project.screens.size)(
              _.devices.count(_.kind.name == "screen")
            )
          )(_.screens.size)
          val json = ujson.Obj(
            "schemaVersion" -> project.schemaVersion,
            "projectId" -> project.id.value,
            "workspaceKind" -> kind,
            "computers" -> computerCount,
            "screens" -> screenCount,
            "tickRate" -> project.runtime.tickRate,
            "clockAutoStart" -> project.runtime.clockAutoStart
          )
          json -> (
            s"${project.id.value}: valid schema ${project.schemaVersion} $kind project, " +
              s"${project.runtime.tickRate} TPS, clock auto-start ${project.runtime.clockAutoStart}"
          )
        }
      case _ => return fail(AppExitCode.Usage, usage)
    }
    result match {
      case Left(errors) =>
        fail(
          AppExitCode.Domain,
          errors.errors.map(error => s"${error.path}: ${error.message}").mkString("; "),
          Some("project_validation_failed")
        )
      case Right((json, human)) =>
        printResult(global.json || trailingJson, json, _ => human)
        AppExitCode.Success
    }
  }

  private def validateProject(
      root: Path
  ): Either[
    ProjectErrors,
    (ocelot.harness.core.project.ValidatedProject, Option[DesktopWorkspaceInspection])
  ] =
    ProjectLoader.load(root).flatMap { project =>
      project.workspaceSource match {
        case WorkspaceSourceDefinition.Manifest => Right(project -> None)
        case _: WorkspaceSourceDefinition.Desktop =>
          DesktopWorkspaceProjects
            .validateImportedProject(project)
            .map(value => project -> Some(value))
      }
    }

  private def inspectionJson(value: DesktopWorkspaceInspection): ujson.Obj =
    ujson.Obj(
      "source" -> value.source.toString,
      "sha256" -> value.sha256,
      "entityCount" -> value.entityCount,
      "edgeCount" -> value.edgeCount,
      "additionalEntityCount" -> value.additionalEntityCount,
      "computers" -> ujson.Arr.from(value.computers.map(deviceJson)),
      "screens" -> ujson.Arr.from(value.screens.map(deviceJson))
    )

  private def deviceJson(value: ocelot.harness.core.project.DesktopLogicalDevice): ujson.Obj =
    ujson.Obj(
      "logicalId" -> value.logicalId,
      "label" -> value.label.map(ujson.Str).getOrElse(ujson.Null)
    )

  private def inspectionHuman(value: DesktopWorkspaceInspection): String =
    s"compatible Desktop workspace: ${value.computers.size} computers, ${value.screens.size} screens, ${value.additionalEntityCount} additional entities"

  private def printResult(
      json: Boolean,
      value: ujson.Value,
      human: ujson.Value => String
  ): Unit =
    if (json) System.out.println(ujson.write(value, indent = -1, escapeUnicode = false))
    else System.out.println(human(value))

  private def parseGlobal(arguments: Vector[String]): Either[String, Global] = {
    @tailrec
    def loop(
        remaining: Vector[String],
        project: Path,
        json: Boolean
    ): Either[String, Global] =
      remaining match {
        case Vector()            => Right(Global(project, json, Vector.empty))
        case Vector("--project") => Left("--project requires a path")
        case Vector("--project", path, tail @ _*) =>
          loop(tail.toVector, Paths.get(path).toAbsolutePath.normalize(), json)
        case Vector("--json", tail @ _*) => loop(tail.toVector, project, json = true)
        case command                     => Right(Global(project, json, command))
      }

    loop(arguments, Paths.get(".").toAbsolutePath.normalize(), json = false)
  }

  private def parseCommand(arguments: Vector[String]): Either[String, CliRequest] =
    arguments match {
      case Vector("version") => Right(CliRequest("harness.version", ujson.Obj(), versionHuman))
      case Vector("workspace", "describe") =>
        Right(
          CliRequest(
            "workspace.describe",
            ujson.Obj(),
            value => s"project ${value("projectId").str}"
          )
        )
      case Vector("machine", operation, id) if Set("start", "stop", "reset").contains(operation) =>
        Right(
          CliRequest(
            s"machine.$operation",
            ujson.Obj("computerId" -> id),
            value => s"${value("computerId").str}: ${value("state").str}"
          )
        )
      case Vector("machine", "status", id) =>
        Right(CliRequest("machine.status", ujson.Obj("computerId" -> id), machineStatusHuman))
      case Vector("screen", "read", id) =>
        Right(
          CliRequest(
            "screen.read",
            ujson.Obj("screenId" -> id),
            value => value("text").str,
            Some("text")
          )
        )
      case Vector("screen", "wait", id, tail @ _*) => waitRequest(id, tail.toVector)
      case Vector("simulation", "run", tail @ _*)  => simulationRequest(tail.toVector)
      case Vector("simulation", "start") =>
        Right(clockRequest("simulation.start", ujson.Obj()))
      case Vector("simulation", "start", "--tps", value) =>
        clockTps(value).map(tps => clockRequest("simulation.start", ujson.Obj("tps" -> tps)))
      case Vector("simulation", "pause") =>
        Right(clockRequest("simulation.pause", ujson.Obj()))
      case Vector("simulation", "resume") =>
        Right(clockRequest("simulation.resume", ujson.Obj()))
      case Vector("simulation", "step") =>
        Right(clockRequest("simulation.step", ujson.Obj("count" -> 1)))
      case Vector("simulation", "step", value) =>
        boundedInteger(value, "step count", 1, 10000)
          .map(count => clockRequest("simulation.step", ujson.Obj("count" -> count)))
      case Vector("simulation", "rate", value) =>
        clockTps(value).map(tps => clockRequest("simulation.rate", ujson.Obj("tps" -> tps)))
      case Vector("simulation", "status") =>
        Right(clockRequest("simulation.status", ujson.Obj()))
      case Vector("screen", "touch", id, x, y, tail @ _*) =>
        coordinateInput(id, "touch", x, y, tail.toVector)
      case Vector("screen", "drop", id, x, y, tail @ _*) =>
        coordinateInput(id, "drop", x, y, tail.toVector)
      case Vector("screen", "scroll", id, x, y, delta) =>
        for {
          parsedX <- number(x, "x")
          parsedY <- number(y, "y")
          parsedDelta <- integer(delta, "delta")
        } yield inputRequest(
          id,
          ujson.Obj("type" -> "scroll", "x" -> parsedX, "y" -> parsedY, "delta" -> parsedDelta)
        )
      case Vector("screen", "drag", id, fromX, fromY, toX, toY, tail @ _*) =>
        for {
          x1 <- number(fromX, "fromX")
          y1 <- number(fromY, "fromY")
          x2 <- number(toX, "toX")
          y2 <- number(toY, "toY")
          options <- options(tail.toVector, Set("--button", "--steps"))
          button <- optionalInt(options, "--button", 0)
          steps <- optionalInt(options, "--steps", 1)
        } yield inputRequest(
          id,
          ujson.Obj(
            "type" -> "drag",
            "fromX" -> x1,
            "fromY" -> y1,
            "toX" -> x2,
            "toY" -> y2,
            "button" -> button,
            "steps" -> steps
          )
        )
      case Vector("screen", "paste", id, text) =>
        Right(inputRequest(id, ujson.Obj("type" -> "paste", "text" -> text)))
      case Vector("screen", "type", id, text, tail @ _*) =>
        for {
          parsed <- options(tail.toVector, Set("--inter-key-ticks"))
          ticks <- optionalInt(parsed, "--inter-key-ticks", 1)
        } yield inputRequest(
          id,
          ujson.Obj("type" -> "type_text", "text" -> text, "interKeyTicks" -> ticks)
        )
      case Vector("screen", direction, id, key, tail @ _*)
          if direction == "key-down" || direction == "key-up" =>
        for {
          parsed <- options(tail.toVector, Set("--character"))
          character = parsed.get("--character").map(ujson.Str).getOrElse(ujson.Null)
        } yield inputRequest(
          id,
          ujson.Obj(
            "type" -> (if (direction == "key-down") "key_down" else "key_up"),
            "key" -> key,
            "character" -> character
          )
        )
      case Vector("screen", "capture", id, tail @ _*)  => captureRequest(id, tail.toVector)
      case Vector("snapshot", "save", name, tail @ _*) => snapshotSave(name, tail.toVector)
      case Vector("snapshot", "diff", from, to) =>
        Right(CliRequest("snapshot.diff", ujson.Obj("from" -> from, "to" -> to), snapshotDiffHuman))
      case Vector("jfr", "start") =>
        Right(
          CliRequest(
            "jvm.recording.start",
            ujson.Obj(),
            _ => "recording the service JVM; reproduce the problem, then `jfr stop`"
          )
        )
      case Vector("jfr", "stop", tail @ _*) =>
        options(tail.toVector, Set("--path")).map(parsed =>
          CliRequest(
            "jvm.recording.stop",
            ujson.Obj("path" -> parsed.getOrElse("--path", "jvm/latest.jfr")),
            value =>
              s"wrote ${value("artifact")("relativePath").str} (${value("artifact")("size").str} bytes); open it in JDK Mission Control"
          )
        )
      case Vector("snapshot", "load", name) =>
        Right(
          CliRequest(
            "snapshot.load",
            ujson.Obj("name" -> name),
            value => s"restored ${value("projectId").str}"
          )
        )
      case Vector("media", "eject", id) =>
        Right(
          CliRequest(
            "media.eject",
            ujson.Obj("deviceId" -> id),
            value =>
              s"${value("deviceId").str}: ${if (value("ejected").bool) "ejected" else "empty"}"
          )
        )
      case Vector("redstone", "input", id, side, value) =>
        value.toIntOption
          .toRight(s"redstone input must be a number: $value")
          .map(level =>
            CliRequest(
              "redstone.input",
              ujson.Obj("computerId" -> id, "side" -> side, "value" -> level),
              result =>
                s"${result("computerId").str} ${result("side").str}: ${result("value").num.toInt}"
            )
          )
      case Vector("diagnostics", "collect", tail @ _*) => diagnosticsRequest(tail.toVector)
      case "trace" +: tail                             => traceRequest(tail)
      case Vector("metrics", tail @ _*)                => metricsRequest(tail.toVector)
      case Vector("profile", "start", tail @ _*) =>
        options(tail.toVector, Set("--computer")).map(parsed =>
          CliRequest(
            "profile.start",
            parsed.get("--computer").fold(ujson.Obj())(id => ujson.Obj("computerId" -> id)),
            _ => "profiling; run the simulation, then `profile stop`"
          )
        )
      case Vector("profile", "stop", tail @ _*) =>
        options(tail.toVector, Set("--path")).map(parsed =>
          CliRequest(
            "profile.stop",
            ujson.Obj("path" -> parsed.getOrElse("--path", "profiles/latest")),
            profileHuman
          )
        )
      case Vector("simulation", "mode") =>
        Right(CliRequest("simulation.mode", ujson.Obj(), modeHuman))
      case Vector("simulation", "mode", "threaded") =>
        Right(CliRequest("simulation.mode", ujson.Obj("mode" -> "threaded"), modeHuman))
      case Vector("simulation", "mode", "deterministic", tail @ _*) =>
        options(tail.toVector, Set("--server-tps"))
          .flatMap(optionalInt(_, "--server-tps", 20))
          .map(tps =>
            CliRequest(
              "simulation.mode",
              ujson.Obj("mode" -> "deterministic", "serverTps" -> tps),
              modeHuman
            )
          )
      case Vector("record", "start", name) =>
        Right(CliRequest("recording.start", ujson.Obj("name" -> name), recordingHuman))
      case Vector("record", "stop") =>
        Right(CliRequest("recording.stop", ujson.Obj(), recordingHuman))
      case Vector("replay", name, tail @ _*) => replayRequest(name, tail.toVector)
      case Vector("sweep", name, tail @ _*)  => sweepRequest(name, tail.toVector)
      case "debug" +: tail                   => debugRequest(tail)
      case _                                 => Left(usage)
    }

  private def traceRequest(arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(arguments, Set("--computer", "--kind", "--name", "--after", "--limit"))
      limit <- optionalInt(parsed, "--limit", 50)
      after <- parsed
        .get("--after")
        .map(value => Try(value.toLong).toEither.left.map(_ => "--after must be an integer"))
        .getOrElse(Right(0L))
    } yield {
      val params = ujson.Obj("limit" -> limit, "afterSequence" -> ujson.Num(after.toDouble))
      parsed.get("--computer").foreach(id => params("computerId") = id)
      parsed.get("--name").foreach(text => params("nameContains") = text)
      parsed
        .get("--kind")
        .foreach(kinds =>
          params("kinds") = ujson.Arr.from(kinds.split(',').map(_.trim).filter(_.nonEmpty))
        )
      CliRequest("trace.read", params, traceHuman)
    }

  private def metricsRequest(arguments: Vector[String]): Either[String, CliRequest] =
    arguments match {
      case Vector("reset") => Right(CliRequest("metrics.reset", ujson.Obj(), _ => "meters reset"))
      case _ =>
        options(arguments, Set("--computer", "--history")).flatMap { parsed =>
          parsed.get("--history") match {
            case None =>
              Right(CliRequest("metrics.read", ujson.Obj(), metricsHuman(parsed.get("--computer"))))
            case Some(ticks) =>
              integer(ticks, "--history").map { count =>
                val params = ujson.Obj()
                parsed.get("--computer").foreach(id => params("computerId") = id)
                CliRequest("metrics.history", params, historyHuman(count))
              }
          }
        }
    }

  private def replayRequest(name: String, arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(arguments, Set("--until-screen", "--contains", "--timeout"))
      timeout <- optionalDurationMillis(parsed, "--timeout", 300000L)
      until <- (parsed.get("--until-screen"), parsed.get("--contains")) match {
        case (Some(screen), Some(text)) =>
          Right(Some(ujson.Obj("type" -> "screen_contains", "screenId" -> screen, "text" -> text)))
        case (None, None) => Right(None)
        case _            => Left("--until-screen and --contains go together")
      }
    } yield {
      val params = ujson.Obj("name" -> name, "timeoutMillis" -> ujson.Num(timeout.toDouble))
      until.foreach(value => params("until") = value)
      CliRequest("recording.replay", params, replayHuman)
    }

  private def sweepRequest(name: String, arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(
        arguments,
        Set(
          "--screen",
          "--contains",
          "--runs",
          "--max-ticks",
          "--min-tps",
          "--max-tps",
          "--seed",
          "--timeout"
        )
      )
      screen <- required(parsed, "--screen")
      text <- required(parsed, "--contains")
      runs <- optionalInt(parsed, "--runs", 10)
      maxTicks <- optionalInt(parsed, "--max-ticks", 2000)
      minTps <- optionalInt(parsed, "--min-tps", 5)
      maxTps <- optionalInt(parsed, "--max-tps", 20)
      seed <- optionalInt(parsed, "--seed", 1)
      timeout <- optionalDurationMillis(parsed, "--timeout", 300000L)
    } yield CliRequest(
      "simulation.sweep",
      ujson.Obj(
        "snapshot" -> name,
        "condition" -> ujson.Obj("type" -> "screen_contains", "screenId" -> screen, "text" -> text),
        "runs" -> runs,
        "maxTicks" -> maxTicks,
        "minServerTps" -> minTps,
        "maxServerTps" -> maxTps,
        "seed" -> seed,
        "timeoutMillis" -> ujson.Num(timeout.toDouble)
      ),
      sweepHuman
    )

  private def debugRequest(arguments: Vector[String]): Either[String, CliRequest] =
    arguments match {
      case "break" +: file +: lines =>
        lines
          .foldLeft[Either[String, Vector[Int]]](Right(Vector.empty)) { (result, line) =>
            result.flatMap(values => boundedInteger(line, "line", 1, Int.MaxValue).map(values :+ _))
          }
          .map(values =>
            CliRequest(
              "debug.breakpoints",
              ujson.Obj(
                "file" -> Paths.get(file).toAbsolutePath.normalize().toString,
                "lines" -> ujson.Arr.from(values)
              ),
              value =>
                value("breakpoints").arr
                  .map(point => s"${point("file").str}:${point("line").num.toInt}") match {
                  case points if points.isEmpty => "no breakpoints in that file"
                  case points                   => points.mkString("breakpoints: ", ", ", "")
                }
            )
          )
      case Vector("pause") =>
        Right(
          CliRequest("debug.pause", ujson.Obj(), _ => "pausing at the next line a computer runs")
        )
      case "wait" +: tail =>
        for {
          parsed <- options(tail.toVector, Set("--timeout", "--after"))
          timeout <- optionalDurationMillis(parsed, "--timeout", 30000L)
          after <- optionalInt(parsed, "--after", 0)
        } yield CliRequest(
          "debug.state",
          ujson.Obj(
            "afterSequence" -> after,
            "waitMillis" -> ujson.Num(math.min(timeout, 60000L).toDouble)
          ),
          debugStateHuman
        )
      case Vector("state") => Right(CliRequest("debug.state", ujson.Obj(), debugStateHuman))
      case Vector("stack") => Right(CliRequest("debug.frames", ujson.Obj(), framesHuman))
      case Vector("vars")  => Right(variablesRequest("0:L"))
      case Vector("vars", reference) => Right(variablesRequest(reference))
      case Vector(command) if DebugResumeModes.contains(command) =>
        Right(
          CliRequest(
            "debug.resume",
            ujson.Obj("mode" -> DebugResumeModes(command)),
            debugStateHuman
          )
        )
      case _ => Left(usage)
    }

  private val DebugResumeModes =
    Map("continue" -> "continue", "step" -> "in", "next" -> "over", "out" -> "out")

  private def variablesRequest(reference: String): CliRequest =
    CliRequest(
      "debug.variables",
      ujson.Obj("reference" -> reference),
      value =>
        value("variables").arr
          .map { variable =>
            val more = variable("reference") match {
              case ujson.Str(id) => s"  (open with `debug vars $id`)"
              case _             => ""
            }
            s"${variable("name").str} = ${variable("value").str}$more"
          }
          .mkString("\n")
    )

  private def traceHuman(value: ujson.Value): String = {
    val lines = value("events").arr.map { event =>
      val who = event("computerId") match {
        case ujson.Str(id) => id
        case _             => "harness"
      }
      val arguments = event("arguments").arr.map(_.str).mkString(", ")
      val results = event("results").arr.map(_.str)
      val detail = event("kind").str match {
        case "call" =>
          s"calls ${event("name").str}($arguments)" +
            (if (results.isEmpty) "" else s" -> ${results.mkString(", ")}")
        case "signal_queued" => s"receives ${event("name").str}($arguments)"
        case "signal_taken"  => s"handles ${event("name").str}($arguments)"
        case "input"         => s"gets input ${event("name").str} $arguments"
        case other           => s"$other ${event("name").str}"
      }
      val note = event("note") match {
        case ujson.Str(text) => s"  [$text]"
        case _               => ""
      }
      s"#${event("sequence").str} tick ${event("tick").str} $who $detail$note"
    }
    val dropped = value("droppedCount").str.toLong
    (lines ++ (if (dropped > 0) Seq(s"($dropped older events were dropped)") else Seq.empty))
      .mkString("\n")
  }

  private def metricsHuman(computer: Option[String])(value: ujson.Value): String =
    value("computers").arr
      .filter(entry => computer.forall(_ == entry("computerId").str))
      .map { entry =>
        def bytes(field: String) = entry(field) match {
          case ujson.Str(text) => s"${text.toLong / 1024} KiB"
          case _               => "?"
        }
        val calls =
          entry("calls").arr.take(5).map(call => s"${call("name").str} x${call("count").str}")
        Seq(
          s"${entry("computerId").str}:",
          s"  memory ${bytes("memoryUsedBytes")} of ${bytes("memoryTotalBytes")} (peak ${bytes("memoryPeakBytes")})",
          s"  longest run without yielding ${entry("longestSliceNanos").str.toLong / 1000000} ms of ${entry("sliceTimeoutNanos").str.toLong / 1000000} ms allowed",
          s"  signals waiting ${entry("signalQueueDepth").num.toInt} of ${entry("signalQueueLimit").num.toInt} (peak ${entry("signalQueuePeak").num.toInt}), longest wait ${entry("longestSignalWaitTicks").str} ticks",
          f"  call budget: ran out ${entry("callBudgetExhausted").str} times, peak ${entry("peakCallBudgetUsed").num * 100}%.0f%% of a tick's budget",
          s"  most calls: ${if (calls.isEmpty) "none" else calls.mkString(", ")}"
        ).mkString("\n")
      }
      .mkString("\n")

  private def historyHuman(count: Int)(value: ujson.Value): String =
    value("samples").arr
      .takeRight(count)
      .map(sample =>
        s"tick ${sample("tick").str} ${sample("computerId").str}: memory ${sample("memoryUsedBytes").str.toLong / 1024} KiB, " +
          s"ran ${sample("sliceNanos").str.toLong / 1000} us, ${sample("calls").str} calls, ${sample("signalQueueDepth").num.toInt} signals waiting"
      )
      .mkString("\n")

  private def snapshotDiffHuman(value: ujson.Value): String = {
    val changes = value("changes").arr.map { change =>
      val before = change("before") match { case ujson.Str(text) => text; case _ => "" }
      val after = change("after") match { case ujson.Str(text) => text; case _ => "" }
      change("change").str match {
        case "added"   => s"+ ${change("path").str}: $after"
        case "removed" => s"- ${change("path").str}: $before"
        case _         => s"~ ${change("path").str}: $before -> $after"
      }
    }
    val total = value("totalChanges").num.toInt
    (Seq(
      s"${value("from").str} (tick ${value("fromTick").str}) -> ${value("to").str} (tick ${value("toTick").str}): $total changes"
    ) ++ changes ++
      (if (total > changes.size) Seq(s"(first ${changes.size} shown)") else Seq.empty) ++
      value("notes").arr.map(note => s"note: ${note.str}")).mkString("\n")
  }

  private def profileHuman(value: ujson.Value): String = {
    val samples = value("samples").str.toLong
    val hottest = value("hottestLines").arr.take(10).map { line =>
      val share = if (samples == 0L) 0.0 else line("samples").str.toLong * 100.0 / samples
      f"  $share%5.1f%%  ${line("line").str}"
    }
    val artifacts = value("artifacts").arr.map(artifact => s"  ${artifact("relativePath").str}")
    (Seq(
      s"$samples samples over ${value("ticks").str} ticks; hottest lines (time inside OpenComputers' kernel counts at the line that called it):"
    ) ++ hottest ++
      (if (artifacts.isEmpty) Seq.empty else "written to the artifacts folder:" +: artifacts))
      .mkString("\n")
  }

  private def modeHuman(value: ujson.Value): String = value("serverTps") match {
    case ujson.Num(tps) => s"deterministic, as a server running at ${tps.toInt} TPS"
    case _              => "threaded, as in game"
  }

  private def recordingHuman(value: ujson.Value): String =
    if (value("active").bool) s"recording ${value("name").str} from tick ${value("startTick").str}"
    else
      s"recorded ${value("name").str}: ${value("ticks").str} ticks, ${value("inputs").num.toInt} inputs" +
        (if (value("limitReached").bool) " (stopped growing at its limit)" else "")

  private def replayHuman(value: ujson.Value): String =
    value("divergedAtTick") match {
      case ujson.Str(tick) =>
        s"replay differs from the recording at tick $tick: ${value("divergence").str}"
      case _ =>
        s"replayed ${value("replayedTicks").str} of ${value("recordedTicks").str} ticks, all matching; stopped at tick ${value("stoppedAtTick").str}"
    }

  private def sweepHuman(value: ujson.Value): String = {
    val runs = value("runs").arr.map { run =>
      s"  seed ${run("seed").str}: ${if (run("passed").bool) "passed" else "FAILED"} after ${run("ticks").str} ticks, server ${run("minServerTps").num.toInt}-${run("maxServerTps").num.toInt} TPS"
    }
    val failing = value("failingSeeds").arr.map(_.str)
    ((if (failing.isEmpty) "every run passed"
      else s"failing seeds: ${failing.mkString(", ")}") +: runs)
      .mkString("\n")
  }

  private def debugStateHuman(value: ujson.Value): String =
    if (value("paused").bool)
      s"${value("computerId").str} paused (${value("reason").str}); pause #${value("pauseSequence").str}"
    else "running"

  private def framesHuman(value: ujson.Value): String =
    value("frames").arr
      .map { frame =>
        val where = frame("hostPath") match {
          case ujson.Str(path) => path
          case _               => frame("source").str
        }
        s"#${frame("index").num.toInt} ${frame("function").str} at $where:${frame("line").num.toInt}"
      }
      .mkString("\n")

  private def waitRequest(id: String, arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(arguments, Set("--contains", "--max-ticks", "--timeout"))
      text <- required(parsed, "--contains")
      maxTicks <- optionalInt(parsed, "--max-ticks", 1000)
      timeout <- optionalDurationMillis(parsed, "--timeout", 30000L)
    } yield runRequest(
      ujson.Obj("type" -> "screen_contains", "screenId" -> id, "text" -> text),
      maxTicks,
      timeout
    )

  private def simulationRequest(arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(
        arguments,
        Set("--screen", "--contains", "--max-ticks", "--timeout")
      )
      screen <- required(parsed, "--screen")
      text <- required(parsed, "--contains")
      maxTicks <- optionalInt(parsed, "--max-ticks", 1000)
      timeout <- optionalDurationMillis(parsed, "--timeout", 30000L)
    } yield runRequest(
      ujson.Obj("type" -> "screen_contains", "screenId" -> screen, "text" -> text),
      maxTicks,
      timeout
    )

  private def runRequest(condition: ujson.Obj, maxTicks: Int, timeoutMillis: Long): CliRequest =
    CliRequest(
      "simulation.run",
      ujson.Obj(
        "condition" -> condition,
        "maxTicks" -> maxTicks,
        "timeoutMillis" -> ujson.Num(timeoutMillis.toDouble)
      ),
      value => s"${value("stopReason")("type").str} after ${value("elapsedTicks").num.toInt} ticks",
      Some("summary")
    )

  private def clockRequest(method: String, params: ujson.Obj): CliRequest =
    CliRequest(method, params, clockHuman)

  private def clockTps(value: String): Either[String, Int] =
    boundedInteger(value, "TPS", 1, 1000)

  private def boundedInteger(
      value: String,
      name: String,
      minimum: Int,
      maximum: Int
  ): Either[String, Int] =
    integer(value, name).flatMap { parsed =>
      if (parsed >= minimum && parsed <= maximum) Right(parsed)
      else Left(s"$name must be between $minimum and $maximum")
    }

  private def coordinateInput(
      id: String,
      kind: String,
      x: String,
      y: String,
      arguments: Vector[String]
  ): Either[String, CliRequest] =
    for {
      parsedX <- number(x, "x")
      parsedY <- number(y, "y")
      parsed <- options(arguments, Set("--button"))
      button <- optionalInt(parsed, "--button", 0)
    } yield inputRequest(
      id,
      ujson.Obj("type" -> kind, "x" -> parsedX, "y" -> parsedY, "button" -> button)
    )

  private def inputRequest(id: String, input: ujson.Obj): CliRequest =
    CliRequest(
      "screen.input",
      ujson.Obj("screenId" -> id, "input" -> input),
      value => s"sent ${value("eventsSent").num.toInt} input events"
    )

  private def captureRequest(id: String, arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(arguments, Set("--format", "--path", "--scale"))
      format <- required(parsed, "--format")
      _ <-
        if (Set("png", "text", "cells-json").contains(format)) Right(())
        else Left("--format must be png, text, or cells-json")
      path = parsed.getOrElse("--path", s"screens/$id.$format")
      scale <- optionalInt(parsed, "--scale", 1)
    } yield CliRequest(
      "screen.capture",
      ujson.Obj("screenId" -> id, "format" -> format, "path" -> path, "scale" -> scale),
      artifactHuman
    )

  private def snapshotSave(name: String, arguments: Vector[String]): Either[String, CliRequest] =
    for {
      parsed <- options(arguments, Set("--host-disks"))
      hostDisks = parsed.getOrElse("--host-disks", "reference-only")
    } yield CliRequest(
      "snapshot.save",
      ujson.Obj("name" -> name, "hostDisks" -> hostDisks),
      value => s"saved ${value("relativePath").str}"
    )

  private def diagnosticsRequest(arguments: Vector[String]): Either[String, CliRequest] =
    options(arguments, Set("--path")).map { parsed =>
      val path = parsed.getOrElse("--path", "diagnostics/latest.zip")
      CliRequest(
        "diagnostics.collect",
        ujson.Obj("path" -> path),
        value => artifactHuman(value("artifact"))
      )
    }

  private def options(
      arguments: Vector[String],
      allowed: Set[String]
  ): Either[String, Map[String, String]] = {
    @tailrec
    def loop(
        remaining: Vector[String],
        result: Map[String, String]
    ): Either[String, Map[String, String]] = {
      if (remaining.isEmpty) Right(result)
      else if (!remaining.head.startsWith("--")) Left(s"unexpected argument: ${remaining.head}")
      else if (!allowed.contains(remaining.head)) Left(s"unknown option: ${remaining.head}")
      else if (result.contains(remaining.head)) Left(s"duplicate option: ${remaining.head}")
      else if (remaining.length == 1) Left(s"${remaining.head} requires a value")
      else loop(remaining.drop(2), result.updated(remaining.head, remaining(1)))
    }
    loop(arguments, Map.empty)
  }

  private def required(values: Map[String, String], name: String): Either[String, String] =
    values.get(name).toRight(s"$name is required")

  private def optionalInt(
      values: Map[String, String],
      name: String,
      default: Int
  ): Either[String, Int] =
    values.get(name).map(integer(_, name)).getOrElse(Right(default))

  private def optionalDurationMillis(
      values: Map[String, String],
      name: String,
      default: Long
  ): Either[String, Long] =
    values.get(name).map(durationMillis(_, name)).getOrElse(Right(default))

  private def integer(value: String, name: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$name must be an integer")

  private def number(value: String, name: String): Either[String, Double] =
    Try(value.toDouble).toEither.left.map(_ => s"$name must be a number").flatMap { result =>
      if (result.isNaN || result.isInfinite) Left(s"$name must be finite") else Right(result)
    }

  private def durationMillis(value: String, name: String): Either[String, Long] = {
    val Pattern = "([0-9]+)(ms|s)".r
    value match {
      case Pattern(amount, unit) =>
        Try(amount.toLong)
          .flatMap(number => Try(if (unit == "s") Math.multiplyExact(number, 1000L) else number))
          .toEither
          .left
          .map(_ => s"$name is outside the supported duration range")
          .flatMap(result => if (result > 0L) Right(result) else Left(s"$name must be positive"))
      case _ => Left(s"$name must use a positive ms or s duration")
    }
  }

  private def versionHuman(value: ujson.Value): String =
    s"Ocelot Harness ${value("harnessVersion").str} protocol ${value("protocolMajor").num.toInt}"

  private def artifactHuman(value: ujson.Value): String =
    s"${value("relativePath").str} (${value("sha256").str})"

  private def clockHuman(value: ujson.Value): String =
    f"${value("state").str} at ${value("targetTps").num.toInt}%d TPS " +
      f"(measured ${value("measuredTps").num}%.2f, ticks ${value("totalTicks").str}, " +
      s"overruns ${value("overrunCount").str})"

  private def fail(code: Int, message: String, harnessCode: Option[String] = None): Int = {
    val suffix = harnessCode.map(value => s" [$value]").getOrElse("")
    System.err.println(s"ERROR$suffix: $message")
    code
  }

  private val usage =
    "usage: ocelotctl [--project <path>] [--json] <project|version|workspace|machine|simulation|screen|snapshot|diagnostics|trace|metrics|profile|record|replay|sweep|debug|jfr> ..."

  private[app] val ReferenceMarkdown: String =
    """# Ocelot Harness CLI reference
      |
      |Generated from `OcelotCtl.ReferenceMarkdown`. Regenerate with the packaged application by running:
      |
      |```text
      |java -cp ocelot-harness.jar ocelot.harness.app.OcelotCtl --help
      |```
      |
      |Global options:
      |
      |- `--project <path>` — project directory; defaults to the current directory
      |- `--json` — emit the command result as compact JSON
      |
      |Commands:
      |
      |```text
      |ocelotctl project init <project-directory> --template <single-computer|two-computers|rack-server|mixed-network> [--json]
      |ocelotctl project inspect-desktop <desktop-directory> [--json]
      |ocelotctl project import-desktop <desktop-directory> <project-directory> [--json]
      |ocelotctl --project <project-directory> project validate [--json]
      |ocelotctl version
      |ocelotctl batch
      |ocelotctl workspace describe
      |ocelotctl machine <start|stop|reset> <computer-id>
      |ocelotctl machine status <computer-id>
      |ocelotctl simulation run --screen <id> --contains <text> [--max-ticks <n>] [--timeout <n>ms|<n>s]
      |ocelotctl simulation start [--tps <1..1000>]
      |ocelotctl simulation pause
      |ocelotctl simulation resume
      |ocelotctl simulation step [count]
      |ocelotctl simulation rate <1..1000>
      |ocelotctl simulation status
      |ocelotctl screen read <screen-id>
      |ocelotctl screen wait <screen-id> --contains <text> [--max-ticks <n>] [--timeout <n>ms|<n>s]
      |ocelotctl screen touch <screen-id> <x> <y> [--button <n>]
      |ocelotctl screen drag <screen-id> <from-x> <from-y> <to-x> <to-y> [--button <n>] [--steps <n>]
      |ocelotctl screen drop <screen-id> <x> <y> [--button <n>]
      |ocelotctl screen scroll <screen-id> <x> <y> <delta>
      |ocelotctl screen paste <screen-id> <text>
      |ocelotctl screen type <screen-id> <text> [--inter-key-ticks <n>]
      |ocelotctl screen key-down <screen-id> <key> [--character <text>]
      |ocelotctl screen key-up <screen-id> <key> [--character <text>]
      |ocelotctl screen capture <screen-id> --format <png|text|cells-json> [--path <relative-path>] [--scale <n>]
      |ocelotctl snapshot save <name> [--host-disks <reference-only|copy>]
      |ocelotctl snapshot load <name>
      |ocelotctl snapshot diff <from-name> <to-name>
      |ocelotctl media eject <disk-drive-id>
      |ocelotctl redstone input <computer-id> <side> <0..15>
      |ocelotctl diagnostics collect [--path <relative-path>]
      |```
      |
      |Observability commands:
      |
      |```text
      |ocelotctl trace [--computer <id>] [--kind <call,signal_queued,signal_taken,input,crash,started,stopped,note>] [--name <text>] [--after <sequence>] [--limit <1..5000>]
      |ocelotctl metrics [--computer <id>]
      |ocelotctl metrics --history <n> [--computer <id>]
      |ocelotctl metrics reset
      |ocelotctl profile start [--computer <id>]
      |ocelotctl profile stop [--path <relative-path>]
      |ocelotctl simulation mode [threaded | deterministic [--server-tps <n>]]
      |ocelotctl record start <name>
      |ocelotctl record stop
      |ocelotctl replay <name> [--until-screen <screen-id> --contains <text>] [--timeout <n>ms|<n>s]
      |ocelotctl sweep <snapshot-name> --screen <screen-id> --contains <text> [--runs <n>] [--max-ticks <n>] [--min-tps <n>] [--max-tps <n>] [--seed <n>] [--timeout <n>ms|<n>s]
      |ocelotctl debug break <file> [line ...]
      |ocelotctl debug <pause|state|stack|continue|step|next|out>
      |ocelotctl debug wait [--after <pause-sequence>] [--timeout <n>ms|<n>s]
      |ocelotctl debug vars [<reference>]
      |ocelotctl jfr start
      |ocelotctl jfr stop [--path <relative-path>]
      |```
      |
      |`trace` reads the flight recorder: every component call with its arguments, results and time, every signal queued and taken (modem messages included), and the input the harness delivered. `metrics` shows how close each computer runs to OpenComputers' limits: memory, the longest run without yielding against the 5 s timeout, the per-tick call budget, and the 256-signal queue. `profile` samples where Lua spends its time and writes a flame graph (`.svg`) and folded stacks (`.folded.txt`). `simulation mode deterministic` runs every computer on the tick thread in a fixed order, so a run repeats exactly; `--server-tps` models a server running slower, which gives computers more slices per tick. `record` saves a state and logs every input and speed change after it; `replay` runs that again, reports the first tick where it diverges, and with `--until-screen` stops at the first tick the text shows. `sweep` reruns a save state under seeded server-lag patterns and lists the seeds that fail; each seed reruns the same pattern. `debug break` sets the breakpoints for one file (no lines clears them); a computer stopped on a breakpoint pauses the whole simulation until it continues. `snapshot diff` compares two save states by device names, card slots and screen lines, and the files of host-folder disks when both copied them. `jfr` records the service JVM itself with Java Flight Recorder for JDK Mission Control. `ocelot-dap` is the debug adapter for the VS Code extension in `tools/vscode-ocelot`.
      |
      |Daemon lifecycle commands:
      |
      |```text
      |ocelot-harnessd up --project <path>
      |ocelot-harnessd status --project <path> [--json]
      |ocelot-harnessd down --project <path> [--json]
      |ocelot-harnessd force-stop --project <path>
      |ocelot-harnessd serve <--stdio|--loopback> --project <path>
      |```
      |
      |Project init/inspect/import/validate commands run locally without a daemon. Desktop import copies bounded compatible source data and never modifies the original directory. Schema-v2 clocks auto-start by default; schema-v1 sessions start paused. Target TPS is bounded to 1–1000, manual step count to 1–10000, and measured TPS is informational rather than a real-time guarantee. Coordinates are one-based. All waits require positive tick and wall-clock bounds. Human-readable screen reads and waits request compact protocol projections; `--json` retains complete protocol responses. `redstone input` sets what a computer's redstone card reads on one side (bottom, top, back, front, right, left, or 0–5), as a neighbouring block would; a rise to the card's wake threshold starts a stopped computer. `batch` keeps one process for many commands: each stdin line is a JSON array of arguments (global options included), and each answer is one stdout line `{"exit", "stdout", "stderr"}`. Typed keys wait whole ticks for room in the computer's 256-signal queue, so typing never drops keys, and typing and pasting return once the computer has taken the input, so the next screen read shows what it did; a computer that takes no input for 2000 ticks in a row fails it. Each key down and up costs the computer a slice, about 12 ms; a paste is one signal per line. Artifact paths are project-relative and remain inside the configured artifact root.
      |""".stripMargin

  private def machineStatusHuman(value: ujson.Value): String = {
    val head = s"${value("computerId").str}: ${value("state").str}"
    value("memory") match {
      case ujson.Null => s"$head, memory not yet reported"
      case memory =>
        val used = memory("usedBytes").str.toLong
        val total = memory("totalBytes").str.toLong
        f"$head, memory $used / $total bytes used (${used * 100.0 / total}%.1f%%)"
    }
  }

  private final case class Global(projectRoot: Path, json: Boolean, arguments: Vector[String])
  private final case class CliRequest(
      method: String,
      params: ujson.Obj,
      human: ujson.Value => String,
      humanProjection: Option[String] = None
  ) {
    def paramsFor(jsonOutput: Boolean): ujson.Obj = {
      if (!jsonOutput) humanProjection.foreach(value => params("projection") = value)
      params
    }
  }
}
