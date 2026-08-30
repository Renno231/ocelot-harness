package ocelot.harness.app.protocol

import java.io.{
  BufferedReader,
  InputStream,
  InputStreamReader,
  OutputStream,
  OutputStreamWriter,
  PrintWriter
}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.concurrent.duration._
import scala.util.control.NonFatal

import ocelot.harness.core.{BuildIdentity, HarnessError}
import ocelot.harness.core.artifact.{RenderOptions, ScreenArtifactFormat, ScreenArtifactRequest}
import ocelot.harness.core.project._
import ocelot.harness.core.workspace._

private[app] final class JsonRpcEndpoint(
    session: HarnessSession,
    expectedToken: Option[String],
    shutdown: () => Unit
) {
  require(session != null, "session is required")
  require(expectedToken != null, "token policy is required")
  require(shutdown != null, "shutdown callback is required")

  private var handshakeComplete = false

  def handleLine(line: String): Option[String] = synchronized {
    val outcome = parseCall(line) match {
      case Left(failure) if failure.code == -32602 =>
        requestId(line).map(id => errorResponse(id, failure))
      case Left(failure) => Some(errorResponse(ujson.Null, failure))
      case Right(call) =>
        val result =
          try dispatch(call)
          catch {
            case NonFatal(error) =>
              System.err.println(
                s"ERROR: JSON-RPC method ${call.method} failed with ${error.getClass.getSimpleName}"
              )
              Left(RpcFailure.internalError)
          }
        call.id.map(id => result.fold(errorResponse(id, _), successResponse(id, _)))
    }
    outcome.map(value => ujson.write(value, indent = -1, escapeUnicode = false))
  }

  private def dispatch(call: RpcCall): Either[RpcFailure, ujson.Value] = {
    if (call.method == "harness.version") handshake(call.params)
    else if (!handshakeComplete) Left(RpcFailure.handshakeRequired)
    else
      call.method match {
        case "workspace.describe" => Right(JsonCodec.workspace(session.describe()))
        case "machine.start"      => machine(call.params, session.startMachine)
        case "machine.stop"       => machine(call.params, session.stopMachine)
        case "machine.reset"      => machine(call.params, session.resetMachine)
        case "simulation.run"     => run(call.params)
        case "screen.read" =>
          screenId(call.params).flatMap(id => domain(session.readScreen(id))).map(JsonCodec.screen)
        case "screen.input"        => input(call.params)
        case "screen.capture"      => capture(call.params)
        case "snapshot.save"       => saveSnapshot(call.params)
        case "snapshot.load"       => loadSnapshot(call.params)
        case "diagnostics.collect" => diagnostics(call.params)
        case "service.shutdown" =>
          shutdown()
          Right(ujson.Obj("shuttingDown" -> true))
        case _ => Left(RpcFailure.methodNotFound(call.method))
      }
  }

  private def handshake(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      requestedMajor <- int(params, "protocolMajor")
      _ <-
        if (requestedMajor == BuildIdentity.ProtocolVersion) Right(())
        else Left(RpcFailure.protocolMismatch(requestedMajor))
      _ <- expectedToken match {
        case None => Right(())
        case Some(expected) =>
          string(params, "token").flatMap { supplied =>
            if (constantTimeEquals(expected, supplied)) Right(())
            else Left(RpcFailure.authenticationFailed)
          }
      }
    } yield {
      handshakeComplete = true
      ujson.Obj(
        "protocolMajor" -> BuildIdentity.ProtocolVersion,
        "harnessVersion" -> BuildIdentity.HarnessVersion,
        "harnessCommit" -> BuildIdentity.HarnessCommit,
        "sourceDirty" -> BuildIdentity.SourceDirty,
        "brainCommit" -> BuildIdentity.BrainCommit
      )
    }

  private def machine(
      params: ujson.Obj,
      operation: ComputerId => Either[HarnessError, MachineStatus]
  ): Either[RpcFailure, ujson.Value] =
    computerId(params).flatMap(id => domain(operation(id))).map(JsonCodec.machine)

  private def run(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      conditionValue <- obj(params, "condition")
      condition <- stopCondition(conditionValue)
      maxTicks <- boundedPositiveInt(params, "maxTicks", 10000000)
      timeoutMillis <- boundedPositiveLong(params, "timeoutMillis", 300000L)
      pace <- optionalObj(params, "pace").flatMap {
        case None        => Right(TickPace.Accelerated)
        case Some(value) => tickPace(value)
      }
      result <- domain(
        session.run(
          RunRequest(
            condition,
            maxTicks,
            timeoutMillis.millis,
            pace,
            RunCancellation.create()
          )
        )
      )
    } yield JsonCodec.run(result)

  private def input(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      id <- screenId(params)
      value <- obj(params, "input").flatMap(userInput)
      result <- domain(session.send(id, value))
    } yield ujson.Obj("eventsSent" -> result.eventsSent, "ticksAdvanced" -> result.ticksAdvanced)

  private def capture(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      id <- screenId(params)
      path <- string(params, "path")
      formatName <- string(params, "format")
      format <- formatName match {
        case "text"       => Right(ScreenArtifactFormat.Text)
        case "cells-json" => Right(ScreenArtifactFormat.CellsJson)
        case "png"        => Right(ScreenArtifactFormat.Png)
        case other        => Left(RpcFailure.invalidParams(s"unsupported capture format: $other"))
      }
      scale <- optionalInt(params, "scale", 1)
      maxPixels <- optionalLong(params, "maxPixels", 16777216L)
      result <- domain(
        session.captureScreen(
          id,
          ScreenArtifactRequest(path, format, RenderOptions(scale, maxPixels))
        )
      )
    } yield JsonCodec.artifact(result)

  private def saveSnapshot(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      rawName <- string(params, "name")
      name <- SnapshotName.parse(rawName).left.map(RpcFailure.invalidParams)
      policyName <- optionalString(params, "hostDisks", "reference-only")
      policy <- policyName match {
        case "reference-only" => Right(HostDiskSnapshotPolicy.ReferenceOnly)
        case "copy"           => Right(HostDiskSnapshotPolicy.Copy)
        case other => Left(RpcFailure.invalidParams(s"unsupported host disk policy: $other"))
      }
      maxBytes <- optionalLong(params, "maxBytes", 64L * 1024L * 1024L)
      result <- domain(session.saveSnapshot(SnapshotRequest(name, policy, maxBytes)))
    } yield JsonCodec.snapshot(result)

  private def loadSnapshot(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      rawName <- string(params, "name")
      name <- SnapshotName.parse(rawName).left.map(RpcFailure.invalidParams)
      result <- domain(session.loadSnapshot(name))
    } yield JsonCodec.workspace(result)

  private def diagnostics(params: ujson.Obj): Either[RpcFailure, ujson.Value] =
    for {
      path <- string(params, "path")
      maxBytes <- optionalLong(params, "maxBytes", 16L * 1024L * 1024L)
      result <- domain(session.diagnostics(DiagnosticRequest(path, maxBytes)))
    } yield ujson.Obj(
      "artifact" -> JsonCodec.artifact(result.artifact),
      "entries" -> ujson.Arr.from(result.entries)
    )

  private def stopCondition(value: ujson.Obj): Either[RpcFailure, StopCondition] =
    string(value, "type").flatMap {
      case "screen_contains" =>
        for {
          id <- screenId(value)
          text <- string(value, "text")
        } yield ScreenContains(id, text)
      case "screen_revision_after" =>
        for {
          id <- screenId(value)
          revision <- long(value, "revision")
        } yield ScreenRevisionAfter(id, revision)
      case "machine_reaches" =>
        for {
          id <- computerId(value)
          stateName <- string(value, "state")
          state <- JsonCodec.machineState(stateName)
        } yield MachineReaches(id, state)
      case "event_occurs" => string(value, "kind").map(EventOccurs)
      case other          => Left(RpcFailure.invalidParams(s"unsupported stop condition: $other"))
    }

  private def tickPace(value: ujson.Obj): Either[RpcFailure, TickPace] =
    string(value, "type").flatMap {
      case "accelerated" => Right(TickPace.Accelerated)
      case "fixed_delay" =>
        positiveLong(value, "delayMillis").map(delay => TickPace.FixedDelay(delay.millis))
      case other => Left(RpcFailure.invalidParams(s"unsupported tick pace: $other"))
    }

  private def userInput(value: ujson.Obj): Either[RpcFailure, UserInput] = {
    val user = optionalString(value, "user", "agent")
    string(value, "type").flatMap {
      case "key_down" =>
        for {
          key <- string(value, "key")
          character <- optionalCharacter(value)
          name <- user
        } yield UserInput.KeyDown(key, character, name)
      case "key_up" =>
        for {
          key <- string(value, "key")
          character <- optionalCharacter(value)
          name <- user
        } yield UserInput.KeyUp(key, character, name)
      case "type_text" =>
        for {
          text <- string(value, "text")
          ticks <- optionalInt(value, "interKeyTicks", 1)
          name <- user
        } yield UserInput.TypeText(text, ticks, name)
      case "paste" =>
        for {
          text <- string(value, "text")
          name <- user
        } yield UserInput.Paste(text, name)
      case "touch" =>
        for {
          x <- double(value, "x")
          y <- double(value, "y")
          button <- optionalInt(value, "button", 0)
          name <- user
        } yield UserInput.Touch(x, y, button, name)
      case "drag" =>
        for {
          fromX <- double(value, "fromX")
          fromY <- double(value, "fromY")
          toX <- double(value, "toX")
          toY <- double(value, "toY")
          button <- optionalInt(value, "button", 0)
          steps <- optionalInt(value, "steps", 1)
          name <- user
        } yield UserInput.Drag(fromX, fromY, toX, toY, button, steps, name)
      case "drop" =>
        for {
          x <- double(value, "x")
          y <- double(value, "y")
          button <- optionalInt(value, "button", 0)
          name <- user
        } yield UserInput.Drop(x, y, button, name)
      case "scroll" =>
        for {
          x <- double(value, "x")
          y <- double(value, "y")
          delta <- int(value, "delta")
          name <- user
        } yield UserInput.Scroll(x, y, delta, name)
      case other => Left(RpcFailure.invalidParams(s"unsupported input type: $other"))
    }
  }

  private def optionalCharacter(value: ujson.Obj): Either[RpcFailure, Char] =
    value.value.get("character") match {
      case None | Some(ujson.Null) => Right(0.toChar)
      case Some(ujson.Str(text)) if text.codePointCount(0, text.length) == 1 && text.length == 1 =>
        Right(text.charAt(0))
      case _ => Left(RpcFailure.invalidParams("character must be one UTF-16 character"))
    }

  private def computerId(params: ujson.Obj): Either[RpcFailure, ComputerId] =
    string(params, "computerId").flatMap(value =>
      ComputerId.parse(value).left.map(RpcFailure.invalidParams)
    )

  private def screenId(params: ujson.Obj): Either[RpcFailure, ScreenId] =
    string(params, "screenId").flatMap(value =>
      ScreenId.parse(value).left.map(RpcFailure.invalidParams)
    )

  private def domain[A](value: Either[HarnessError, A]): Either[RpcFailure, A] =
    value.left.map(RpcFailure.domain)

  private def constantTimeEquals(expected: String, supplied: String): Boolean =
    MessageDigest.isEqual(
      expected.getBytes(StandardCharsets.UTF_8),
      supplied.getBytes(StandardCharsets.UTF_8)
    )

  private def requestId(line: String): Option[ujson.Value] =
    try {
      ujson.read(if (line == null) "" else line) match {
        case value: ujson.Obj =>
          value.value.get("id").collect {
            case id: ujson.Str                                => id
            case id: ujson.Num if isSafeJsonInteger(id.value) => id
          }
        case _ => None
      }
    } catch {
      case NonFatal(_) => None
    }

  private def parseCall(line: String): Either[RpcFailure, RpcCall] = {
    val parsed =
      try Right(ujson.read(if (line == null) "" else line))
      catch {
        case NonFatal(_) => Left(RpcFailure.parseError)
      }
    parsed.flatMap {
      case value: ujson.Obj =>
        for {
          version <- string(value, "jsonrpc").left.map(_ => RpcFailure.invalidRequest)
          _ <- if (version == "2.0") Right(()) else Left(RpcFailure.invalidRequest)
          method <- string(value, "method").left.map(_ => RpcFailure.invalidRequest)
          id <- value.value.get("id") match {
            case None                                               => Right(None)
            case Some(id: ujson.Str)                                => Right(Some(id))
            case Some(id: ujson.Num) if isSafeJsonInteger(id.value) => Right(Some(id))
            case _ => Left(RpcFailure.invalidRequest)
          }
          params <- value.value.get("params") match {
            case None                    => Right(ujson.Obj())
            case Some(params: ujson.Obj) => Right(params)
            case _ => Left(RpcFailure.invalidParams("params must be an object"))
          }
        } yield RpcCall(id, method, params)
      case _ => Left(RpcFailure.invalidRequest)
    }
  }

  private def string(value: ujson.Obj, field: String): Either[RpcFailure, String] =
    value.value.get(field) match {
      case Some(ujson.Str(text)) => Right(text)
      case _                     => Left(RpcFailure.invalidParams(s"$field must be a string"))
    }

  private def obj(value: ujson.Obj, field: String): Either[RpcFailure, ujson.Obj] =
    value.value.get(field) match {
      case Some(result: ujson.Obj) => Right(result)
      case _                       => Left(RpcFailure.invalidParams(s"$field must be an object"))
    }

  private def optionalObj(value: ujson.Obj, field: String): Either[RpcFailure, Option[ujson.Obj]] =
    value.value.get(field) match {
      case None | Some(ujson.Null) => Right(None)
      case Some(result: ujson.Obj) => Right(Some(result))
      case _                       => Left(RpcFailure.invalidParams(s"$field must be an object"))
    }

  private def double(value: ujson.Obj, field: String): Either[RpcFailure, Double] =
    value.value.get(field) match {
      case Some(ujson.Num(number)) if !number.isNaN && !number.isInfinite => Right(number)
      case _ => Left(RpcFailure.invalidParams(s"$field must be a finite number"))
    }

  private def long(value: ujson.Obj, field: String): Either[RpcFailure, Long] =
    value.value.get(field) match {
      case Some(ujson.Num(number)) if isSafeJsonInteger(number) => Right(number.toLong)
      case Some(ujson.Str(text)) if text.matches("-?(0|[1-9][0-9]*)") =>
        try Right(text.toLong)
        catch {
          case _: NumberFormatException =>
            Left(RpcFailure.invalidParams(s"$field is outside the 64-bit integer range"))
        }
      case _ => Left(RpcFailure.invalidParams(s"$field must be an integer or decimal string"))
    }

  private def isSafeJsonInteger(number: Double): Boolean =
    !number.isNaN && !number.isInfinite && number == Math.rint(number) &&
      math.abs(number) <= 9007199254740991d

  private def int(value: ujson.Obj, field: String): Either[RpcFailure, Int] =
    long(value, field).flatMap(number =>
      if (number >= Int.MinValue && number <= Int.MaxValue) Right(number.toInt)
      else Left(RpcFailure.invalidParams(s"$field is outside the integer range"))
    )

  private def positiveInt(value: ujson.Obj, field: String): Either[RpcFailure, Int] =
    int(value, field).flatMap(number =>
      if (number > 0) Right(number) else Left(RpcFailure.invalidParams(s"$field must be positive"))
    )

  private def positiveLong(value: ujson.Obj, field: String): Either[RpcFailure, Long] =
    long(value, field).flatMap(number =>
      if (number > 0L) Right(number) else Left(RpcFailure.invalidParams(s"$field must be positive"))
    )

  private def boundedPositiveInt(
      value: ujson.Obj,
      field: String,
      maximum: Int
  ): Either[RpcFailure, Int] =
    positiveInt(value, field).flatMap(number =>
      if (number <= maximum) Right(number)
      else Left(RpcFailure.invalidParams(s"$field must not exceed $maximum"))
    )

  private def boundedPositiveLong(
      value: ujson.Obj,
      field: String,
      maximum: Long
  ): Either[RpcFailure, Long] =
    positiveLong(value, field).flatMap(number =>
      if (number <= maximum) Right(number)
      else Left(RpcFailure.invalidParams(s"$field must not exceed $maximum"))
    )

  private def optionalString(
      value: ujson.Obj,
      field: String,
      default: String
  ): Either[RpcFailure, String] =
    value.value.get(field) match {
      case None | Some(ujson.Null) => Right(default)
      case Some(ujson.Str(text))   => Right(text)
      case _                       => Left(RpcFailure.invalidParams(s"$field must be a string"))
    }

  private def optionalInt(value: ujson.Obj, field: String, default: Int): Either[RpcFailure, Int] =
    if (value.value.contains(field)) int(value, field) else Right(default)

  private def optionalLong(
      value: ujson.Obj,
      field: String,
      default: Long
  ): Either[RpcFailure, Long] =
    if (value.value.contains(field)) long(value, field) else Right(default)

  private def successResponse(id: ujson.Value, result: ujson.Value): ujson.Value =
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

  private def errorResponse(id: ujson.Value, failure: RpcFailure): ujson.Value = {
    val error = ujson.Obj("code" -> failure.code, "message" -> failure.message)
    failure.data.foreach(value => error("data") = value)
    ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "error" -> error)
  }
}

private[app] object StdioTransport {
  private[protocol] val MaxLineCharacters = 1024 * 1024

  def serve(input: InputStream, output: OutputStream, endpoint: JsonRpcEndpoint): Unit =
    serve(input, output, endpoint, () => true)

  def serve(
      input: InputStream,
      output: OutputStream,
      endpoint: JsonRpcEndpoint,
      keepRunning: () => Boolean
  ): Unit = {
    require(input != null, "input stream is required")
    require(output != null, "output stream is required")
    require(endpoint != null, "protocol endpoint is required")
    require(keepRunning != null, "run-state callback is required")

    val reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
    val writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true)
    var framed = if (keepRunning()) readBoundedLine(reader) else None
    while (framed.nonEmpty) {
      val line = framed.get.getOrElse("")
      endpoint.handleLine(line).foreach(writer.println)
      framed = if (keepRunning()) readBoundedLine(reader) else None
    }
    writer.flush()
  }

  private def readBoundedLine(reader: BufferedReader): Option[Either[Unit, String]] = {
    val result = new StringBuilder
    var tooLong = false
    var sawInput = false
    var character = reader.read()
    while (character >= 0 && character != '\n') {
      sawInput = true
      if (character != '\r') {
        if (result.length < MaxLineCharacters) result.append(character.toChar)
        else tooLong = true
      }
      character = reader.read()
    }
    if (!sawInput && character < 0) None
    else Some(if (tooLong) Left(()) else Right(result.toString()))
  }
}

private final case class RpcCall(id: Option[ujson.Value], method: String, params: ujson.Obj)

private final case class RpcFailure(code: Int, message: String, data: Option[ujson.Value] = None)

private object RpcFailure {
  val parseError: RpcFailure = RpcFailure(-32700, "Parse error")
  val invalidRequest: RpcFailure = RpcFailure(-32600, "Invalid Request")
  def methodNotFound(method: String): RpcFailure =
    RpcFailure(-32601, "Method not found", Some(ujson.Obj("method" -> method)))
  def invalidParams(message: String): RpcFailure = RpcFailure(-32602, message)
  val handshakeRequired: RpcFailure =
    domainCode("protocol_handshake_required", "harness.version must complete first")
  def protocolMismatch(requested: Int): RpcFailure =
    domainCode(
      "protocol_version_mismatch",
      s"protocol major $requested is incompatible with ${BuildIdentity.ProtocolVersion}"
    )
  val authenticationFailed: RpcFailure =
    domainCode("authentication_failed", "authentication failed")
  val internalError: RpcFailure = RpcFailure(-32603, "Internal error")
  def domain(error: HarnessError): RpcFailure =
    domainCode(error.code, error.message)
  private def domainCode(code: String, message: String): RpcFailure =
    RpcFailure(-32000, message, Some(ujson.Obj("harnessCode" -> code)))
}

private object JsonCodec {
  def artifact(value: ocelot.harness.core.artifact.ArtifactDescription): ujson.Value =
    ujson.Obj(
      "relativePath" -> value.relativePath,
      "size" -> int64(value.size),
      "mediaType" -> value.mediaType,
      "sha256" -> value.sha256
    )

  def workspace(value: WorkspaceDescription): ujson.Value =
    ujson.Obj(
      "projectId" -> value.projectId.value,
      "computers" -> ujson.Arr.from(value.computers.map(computer)),
      "screens" -> ujson.Arr.from(value.screens.map(screenDescription)),
      "connections" -> ujson.Arr.from(
        value.connections.map(connection =>
          ujson.Obj("from" -> connection.from.value, "to" -> connection.to.value)
        ) ++ value.deviceConnections.map { case (from, to) =>
          ujson.Obj("from" -> from, "to" -> to)
        }
      ),
      "devices" -> ujson.Arr.from(
        value.devices.map(device =>
          ujson.Obj(
            "deviceId" -> device.id,
            "kind" -> device.kind,
            "tier" -> device.tier.map[ujson.Value](ujson.Num(_)).getOrElse(ujson.Null),
            "runtimeAddress" -> device.runtimeAddress
              .map[ujson.Value](ujson.Str)
              .getOrElse(ujson.Null)
          )
        )
      )
    )

  def machine(value: MachineStatus): ujson.Value =
    ujson.Obj(
      "computerId" -> value.id.value,
      "state" -> machineStateName(value.state),
      "lastError" -> value.lastError.map[ujson.Value](ujson.Str).getOrElse(ujson.Null)
    )

  def screen(value: ScreenSnapshot): ujson.Value =
    ujson.Obj(
      "screenId" -> value.id.value,
      "revision" -> int64(value.revision),
      "width" -> value.width,
      "height" -> value.height,
      "text" -> value.text,
      "cells" -> ujson.Arr.from(
        value.cells.map(cell =>
          ujson.Obj(
            "codePoint" -> cell.codePoint,
            "foreground" -> (cell.foreground & 0xffffff),
            "background" -> (cell.background & 0xffffff),
            "packedColor" -> (cell.packedColor & 0xffff)
          )
        )
      ),
      "palette" -> ujson.Arr.from(value.palette),
      "colorDepth" -> value.colorDepth,
      "powered" -> value.powered,
      "captureTick" -> int64(value.captureTick),
      "runtimeAddress" -> value.runtimeAddress.map[ujson.Value](ujson.Str).getOrElse(ujson.Null),
      "precisionMode" -> value.precisionMode
    )

  def run(value: RunResult): ujson.Value =
    ujson.Obj(
      "stopReason" -> stopReason(value.stopReason),
      "elapsedTicks" -> value.elapsedTicks,
      "elapsedWallTimeMillis" -> int64(value.elapsedWallTime.toMillis),
      "machines" -> ujson.Arr.from(value.machines.map(machine)),
      "screenRevisions" -> ujson.Obj.from(value.screenRevisions.toVector.map {
        case (id, revision) => id.value -> int64(revision)
      }),
      "events" -> eventSnapshot(value.events),
      "screens" -> ujson.Obj.from(value.screens.toVector.map { case (id, snapshot) =>
        id.value -> screen(snapshot)
      }),
      "timeline" -> ujson.Arr.from(
        value.timeline.map(observation =>
          ujson.Obj(
            "elapsedTicks" -> observation.elapsedTicks,
            "elapsedWallTimeMillis" -> int64(observation.elapsedWallTime.toMillis),
            "machines" -> ujson.Arr.from(observation.machines.map(machine)),
            "screenRevisions" -> ujson.Obj.from(observation.screenRevisions.toVector.map {
              case (id, revision) => id.value -> int64(revision)
            })
          )
        )
      ),
      "timelineDroppedCount" -> int64(value.timelineDroppedCount)
    )

  def snapshot(value: SnapshotDescription): ujson.Value =
    ujson.Obj(
      "name" -> value.name.value,
      "relativePath" -> value.relativePath,
      "size" -> int64(value.size),
      "sha256" -> value.sha256,
      "hostDisks" -> value.hostDisks.name,
      "captureTick" -> int64(value.captureTick),
      "copiedDiskBytes" -> int64(value.copiedDiskBytes)
    )

  def machineState(value: String): Either[RpcFailure, MachineState] = value match {
    case "running" => Right(MachineState.Running)
    case "paused"  => Right(MachineState.Paused)
    case "stopped" => Right(MachineState.Stopped)
    case "crashed" => Right(MachineState.Crashed)
    case other     => Left(RpcFailure.invalidParams(s"unsupported machine state: $other"))
  }

  private def computer(value: ComputerDescription): ujson.Value =
    ujson.Obj(
      "computerId" -> value.id.value,
      "kind" -> value.kind,
      "caseTier" -> value.caseTier,
      "runtimeAddress" -> value.runtimeAddress,
      "components" -> ujson.Arr.from(
        value.components.map(component =>
          ujson.Obj(
            "role" -> component.role.name,
            "tier" -> component.tier,
            "runtimeAddress" -> component.runtimeAddress
              .map[ujson.Value](ujson.Str)
              .getOrElse(ujson.Null)
          )
        )
      ),
      "disks" -> ujson.Arr.from(
        value.disks.map(disk =>
          ujson.Obj(
            "diskId" -> disk.id.value,
            "tier" -> disk.tier,
            "label" -> disk.label,
            "source" -> disk.source.toString,
            "access" -> (disk.access match {
              case DiskAccess.ReadWrite => "read-write"
              case DiskAccess.ReadOnly  => "read-only"
            }),
            "runtimeAddress" -> disk.runtimeAddress
          )
        )
      ),
      "cards" -> ujson.Arr.from(
        value.cards.map(card =>
          ujson.Obj(
            "kind" -> card.kind.name,
            "tier" -> card.tier,
            "runtimeAddress" -> card.runtimeAddress
          )
        )
      )
    )

  private def screenDescription(value: ScreenDescription): ujson.Value =
    ujson.Obj(
      "screenId" -> value.id.value,
      "tier" -> value.tier,
      "aspectRatio" -> ujson.Arr(value.aspectRatio._1, value.aspectRatio._2),
      "runtimeAddress" -> value.runtimeAddress,
      "keyboardRuntimeAddress" -> value.keyboardRuntimeAddress
        .map[ujson.Value](ujson.Str)
        .getOrElse(ujson.Null)
    )

  private def eventSnapshot(value: EventSnapshot): ujson.Value =
    ujson.Obj(
      "events" -> ujson.Arr.from(
        value.events.map(event =>
          ujson.Obj(
            "sequence" -> int64(event.sequence),
            "kind" -> event.kind,
            "sourceAddress" -> event.sourceAddress
              .map[ujson.Value](ujson.Str)
              .getOrElse(ujson.Null),
            "message" -> event.message.map[ujson.Value](ujson.Str).getOrElse(ujson.Null)
          )
        )
      ),
      "droppedCount" -> int64(value.droppedCount)
    )

  private def int64(value: Long): ujson.Value = ujson.Str(value.toString)

  private def stopReason(value: RunStopReason): ujson.Value = value match {
    case RunStopReason.ConditionSatisfied => ujson.Obj("type" -> "condition_satisfied")
    case RunStopReason.ConditionTimedOut(limit) =>
      ujson.Obj("type" -> "condition_timed_out", "limit" -> limit)
    case RunStopReason.Cancelled => ujson.Obj("type" -> "cancelled")
  }

  private def machineStateName(value: MachineState): String = value match {
    case MachineState.Running => "running"
    case MachineState.Paused  => "paused"
    case MachineState.Stopped => "stopped"
    case MachineState.Crashed => "crashed"
  }
}
