package ocelot.harness.app

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
    } else
      parseGlobal(arguments.toVector) match {
        case Left(message) => fail(AppExitCode.Usage, message)
        case Right(global) if global.arguments.headOption.contains("project") =>
          runProjectCommand(global)
        case Right(global) =>
          parseCommand(global.arguments) match {
            case Left(message) => fail(AppExitCode.Usage, message)
            case Right(request) =>
              LoopbackClient.call(global.projectRoot, request.method, request.params) match {
                case Left(error) => fail(error.exitCode, error.message, error.harnessCode)
                case Right(result) =>
                  printResult(global.json, result, request.human)
                  AppExitCode.Success
              }
          }
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
      case Vector("screen", "read", id) =>
        Right(CliRequest("screen.read", ujson.Obj("screenId" -> id), value => value("text").str))
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
      case Vector("snapshot", "load", name) =>
        Right(
          CliRequest(
            "snapshot.load",
            ujson.Obj("name" -> name),
            value => s"restored ${value("projectId").str}"
          )
        )
      case Vector("diagnostics", "collect", tail @ _*) => diagnosticsRequest(tail.toVector)
      case _                                           => Left(usage)
    }

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
      value => s"${value("stopReason")("type").str} after ${value("elapsedTicks").num.toInt} ticks"
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
    "usage: ocelotctl [--project <path>] [--json] <project|version|workspace|machine|simulation|screen|snapshot|diagnostics> ..."

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
      |ocelotctl workspace describe
      |ocelotctl machine <start|stop|reset> <computer-id>
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
      |ocelotctl diagnostics collect [--path <relative-path>]
      |```
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
      |Project init/inspect/import/validate commands run locally without a daemon. Desktop import copies bounded compatible source data and never modifies the original directory. Schema-v2 clocks auto-start by default; schema-v1 sessions start paused. Target TPS is bounded to 1–1000, manual step count to 1–10000, and measured TPS is informational rather than a real-time guarantee. Coordinates are one-based. All waits require positive tick and wall-clock bounds. Artifact paths are project-relative and remain inside the configured artifact root.
      |""".stripMargin

  private final case class Global(projectRoot: Path, json: Boolean, arguments: Vector[String])
  private final case class CliRequest(
      method: String,
      params: ujson.Obj,
      human: ujson.Value => String
  )
}
