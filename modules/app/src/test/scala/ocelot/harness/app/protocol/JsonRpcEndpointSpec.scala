package ocelot.harness.app.protocol

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.UnknownComputer
import ocelot.harness.core.artifact.{ArtifactDescription, ScreenArtifactRequest}
import ocelot.harness.core.project._
import ocelot.harness.core.workspace._

final class JsonRpcEndpointSpec extends AnyFunSuite with Matchers {
  test("JSON-RPC framing survives malformed input and preserves request IDs") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())

    val parseError = response(endpoint.handleLine("{"))
    parseError("error")("code").num.toInt shouldBe -32700
    parseError("id") shouldBe ujson.Null

    val handshake = response(
      endpoint.handleLine(request(7, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )
    handshake("id").num.toInt shouldBe 7
    handshake("result")("protocolMajor").num.toInt shouldBe 1
    handshake("result")("harnessCommit").str should fullyMatch regex "[0-9a-f]{40}"
    handshake("result").obj.keySet should contain("sourceDirty")
    handshake("result")("brainCommit").str shouldBe
      "bec1cc6b1e9e588692f753e9c617063c74967fed"

    val described = response(endpoint.handleLine(request("next", "workspace.describe")))
    described("id").str shouldBe "next"
    described("result")("projectId").str shouldBe "fixture"
  }

  test("protocol handshake rejects a mismatched major and missing loopback token") {
    val mismatch = response(
      new JsonRpcEndpoint(new StubSession, None, () => ())
        .handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 2)))
    )
    mismatch("error")("data")("harnessCode").str shouldBe "protocol_version_mismatch"

    val protectedEndpoint = new JsonRpcEndpoint(new StubSession, Some("secret"), () => ())
    val denied = response(
      protectedEndpoint.handleLine(
        request(2, "harness.version", ujson.Obj("protocolMajor" -> 1, "token" -> "wrong"))
      )
    )
    denied("error")("data")("harnessCode").str shouldBe "authentication_failed"

    val accepted = response(
      protectedEndpoint.handleLine(
        request(3, "harness.version", ujson.Obj("protocolMajor" -> 1, "token" -> "secret"))
      )
    )
    accepted("result")("protocolMajor").num.toInt shouldBe 1
  }

  test("notifications have no response and service shutdown is delegated") {
    var shutdowns = 0
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => shutdowns += 1)
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    endpoint.handleLine(notification("service.shutdown")) shouldBe None
    shutdowns shouldBe 1
  }

  test("invalid requests, unknown methods, bad params, and domain failures are distinct") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    response(endpoint.handleLine("[]"))("error")("code").num.toInt shouldBe -32600
    response(endpoint.handleLine(request(2, "missing.method")))("error")(
      "code"
    ).num.toInt shouldBe -32601
    response(endpoint.handleLine(request(3, "machine.start")))("error")(
      "code"
    ).num.toInt shouldBe -32602

    val invalidParams = response(
      endpoint.handleLine(
        ujson.write(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> 31,
            "method" -> "workspace.describe",
            "params" -> ujson.Arr()
          )
        )
      )
    )
    invalidParams("id").num.toInt shouldBe 31
    invalidParams("error")("code").num.toInt shouldBe -32602

    val domain = response(
      endpoint.handleLine(request(4, "machine.start", ujson.Obj("computerId" -> "missing")))
    )
    domain("error")("code").num.toInt shouldBe -32000
    domain("error")("data")("harnessCode").str shouldBe "unknown_computer"
  }

  test("machine status reports the latest guest memory usage in bytes") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val status = response(
      endpoint.handleLine(request(2, "machine.status", ujson.Obj("computerId" -> "main")))
    )("result")
    status("state").str shouldBe "running"
    status("memory") shouldBe ujson.Obj(
      "totalBytes" -> "4194304",
      "freeBytes" -> "3145728",
      "usedBytes" -> "1048576"
    )

    response(
      endpoint.handleLine(request(3, "machine.start", ujson.Obj("computerId" -> "main")))
    )("result")("memory") shouldBe ujson.Null
    response(
      endpoint.handleLine(request(4, "machine.status", ujson.Obj("computerId" -> "missing")))
    )("error")("data")("harnessCode").str shouldBe "unknown_computer"
  }

  test("screen reads support additive summary and text projections while defaulting to full") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val full = response(
      endpoint.handleLine(request(2, "screen.read", ujson.Obj("screenId" -> "main")))
    )("result")
    full.obj.keySet should contain allElementsOf Set("text", "cells", "palette", "runtimeAddress")

    val text = response(
      endpoint.handleLine(
        request(
          3,
          "screen.read",
          ujson.Obj("screenId" -> "main", "projection" -> "text")
        )
      )
    )("result")
    text.obj.keySet shouldBe Set(
      "screenId",
      "revision",
      "width",
      "height",
      "powered",
      "captureTick",
      "text"
    )
    text("text").str shouldBe "READY"

    val summary = response(
      endpoint.handleLine(
        request(
          4,
          "screen.read",
          ujson.Obj("screenId" -> "main", "projection" -> "summary")
        )
      )
    )("result")
    summary.obj.keySet shouldBe Set(
      "screenId",
      "revision",
      "width",
      "height",
      "powered",
      "captureTick"
    )

    val invalid = response(
      endpoint.handleLine(
        request(
          5,
          "screen.read",
          ujson.Obj("screenId" -> "main", "projection" -> "sparse")
        )
      )
    )
    invalid("error")("code").num.toInt shouldBe -32602
  }

  test("simulation runs support compact summary and text projections while defaulting to full") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    def run(projection: Option[String], id: Int): ujson.Value = {
      val params = ujson.Obj(
        "condition" -> ujson.Obj(
          "type" -> "screen_contains",
          "screenId" -> "main",
          "text" -> "READY"
        ),
        "maxTicks" -> 100,
        "timeoutMillis" -> 1000
      )
      projection.foreach(value => params("projection") = value)
      response(endpoint.handleLine(request(id, "simulation.run", params)))("result")
    }

    val full = run(None, 2)
    full.obj.keySet should contain allElementsOf Set(
      "events",
      "screens",
      "timeline",
      "timelineDroppedCount"
    )
    full("screens")("main")("cells").arr should have size 1

    val text = run(Some("text"), 3)
    text.obj.keySet shouldBe Set(
      "stopReason",
      "elapsedTicks",
      "elapsedWallTimeMillis",
      "machines",
      "screenRevisions",
      "screens"
    )
    text("screens")("main")("text").str shouldBe "READY"
    text("screens")("main").obj.contains("cells") shouldBe false

    val summary = run(Some("summary"), 4)
    summary.obj.keySet shouldBe Set(
      "stopReason",
      "elapsedTicks",
      "elapsedWallTimeMillis",
      "machines",
      "screenRevisions"
    )
  }

  test("media.eject names the drive and reports whether it held media") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val ejected =
      response(endpoint.handleLine(request(2, "media.eject", ujson.Obj("deviceId" -> "drive"))))
    ejected("result") shouldBe ujson.Obj("deviceId" -> "drive", "ejected" -> true)
    response(endpoint.handleLine(request(3, "media.eject")))("error")(
      "code"
    ).num.toInt shouldBe -32602
    response(
      endpoint.handleLine(request(4, "media.eject", ujson.Obj("deviceId" -> "missing")))
    )("error")("data")("harnessCode").str shouldBe "unknown_drive"
  }

  test("redstone.input sets a computer's redstone card input on one side") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val set = response(
      endpoint.handleLine(
        request(
          2,
          "redstone.input",
          ujson.Obj("computerId" -> "main", "side" -> "back", "value" -> 4)
        )
      )
    )
    set("result") shouldBe ujson.Obj("computerId" -> "main", "side" -> "back", "value" -> 4)
    response(
      endpoint.handleLine(
        request(3, "redstone.input", ujson.Obj("computerId" -> "main", "side" -> "back"))
      )
    )("error")("code").num.toInt shouldBe -32602
    response(
      endpoint.handleLine(
        request(
          4,
          "redstone.input",
          ujson.Obj("computerId" -> "main", "side" -> "back", "value" -> 16)
        )
      )
    )("error")("data")("harnessCode").str shouldBe "invalid_input"
  }

  test("observability methods validate their parameters and encode what the session reports") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1))))
    def call(method: String, params: ujson.Obj = ujson.Obj()): ujson.Value =
      response(endpoint.handleLine(request(2, method, params)))

    val trace = call("trace.read", ujson.Obj("kinds" -> ujson.Arr("call"), "limit" -> 10))("result")
    trace("events")(0)("name").str shouldBe "gpu.set"
    trace("events")(0)("arguments") shouldBe ujson.Arr("1", "1", "\"READY\"")
    trace("latestSequence").str shouldBe "5"
    call("trace.read", ujson.Obj("kinds" -> "call"))("error")("code").num.toInt shouldBe -32602

    val metrics = call("metrics.read")("result")("computers")(0)
    metrics("memoryUsedBytes").str shouldBe "1000"
    metrics("calls")(0)("name").str shouldBe "gpu.set"
    call("metrics.history", ujson.Obj("afterTick" -> 0))("result")("samples")(0)(
      "tick"
    ).str shouldBe "41"

    call("profile.start", ujson.Obj("computerId" -> "main"))("result")(
      "profiling"
    ).bool shouldBe true
    val profile = call("profile.stop", ujson.Obj("path" -> "profiles/main"))("result")
    profile("hottestLines")(0)("line").str shouldBe "/main.lua:6"
    profile("artifacts")(0)("relativePath").str shouldBe "profiles/main.svg"

    call("simulation.mode")("result")("mode").str shouldBe "threaded"
    val deterministic =
      call("simulation.mode", ujson.Obj("mode" -> "deterministic", "serverTps" -> 7))("result")
    deterministic shouldBe ujson.Obj("mode" -> "deterministic", "serverTps" -> 7)
    call("simulation.mode", ujson.Obj("mode" -> "fast"))("error")("code").num.toInt shouldBe -32602

    call("recording.start", ujson.Obj("name" -> "lagged"))("result")("active").bool shouldBe true
    call("recording.stop")("error")("data")("harnessCode").str shouldBe "recording_invalid"
    val replay = call(
      "recording.replay",
      ujson.Obj(
        "name" -> "lagged",
        "timeoutMillis" -> 1000,
        "until" -> ujson.Obj("type" -> "screen_contains", "screenId" -> "main", "text" -> "X")
      )
    )("result")
    replay("divergedAtTick").str shouldBe "22"
    replay("divergence").str should include("differ")
    call("recording.replay", ujson.Obj("name" -> "lagged"))("error")(
      "code"
    ).num.toInt shouldBe -32602

    val sweep = call(
      "simulation.sweep",
      ujson.Obj(
        "snapshot" -> "lagged",
        "condition" -> ujson.Obj("type" -> "screen_contains", "screenId" -> "main", "text" -> "OK"),
        "runs" -> 3,
        "maxTicks" -> 100,
        "seed" -> 40,
        "timeoutMillis" -> 1000
      )
    )("result")
    sweep("failingSeeds") shouldBe ujson.Arr("41")
    sweep("runs").arr.size shouldBe 3
    call(
      "simulation.sweep",
      ujson.Obj("snapshot" -> "lagged", "condition" -> ujson.Obj(), "runs" -> 1000)
    )("error")("code").num.toInt shouldBe -32602

    call("debug.breakpoints", ujson.Obj("file" -> "main.lua", "lines" -> ujson.Arr(14)))(
      "result"
    )("breakpoints")(0)("line").num.toInt shouldBe 14
    call("debug.breakpoints", ujson.Obj("file" -> "main.lua", "lines" -> ujson.Arr(0)))(
      "error"
    )("code").num.toInt shouldBe -32602
    call("debug.state", ujson.Obj("afterSequence" -> 2, "waitMillis" -> 100))("result")(
      "reason"
    ).str shouldBe "breakpoint"
    call("debug.frames")("result")("frames")(0)("line").num.toInt shouldBe 14
    call("debug.variables", ujson.Obj("reference" -> "0:L"))("result")("variables")(0)(
      "type"
    ).str shouldBe "number"
    call("debug.variables", ujson.Obj("reference" -> "zz"))("error")("data")(
      "harnessCode"
    ).str shouldBe "invalid_input"
    call("debug.resume", ujson.Obj("mode" -> "over"))("result")("paused").bool shouldBe false

    val diff = call("snapshot.diff", ujson.Obj("from" -> "a1", "to" -> "a2"))("result")
    diff("changes")(0)("path").str shouldBe "screen main/text line 1"
    diff("changes")(0)("after").str shouldBe "COUNT 52"
    diff("toTick").str shouldBe "130"
    diff("notes")(0).str should include("a1")
    call("snapshot.diff", ujson.Obj("from" -> "a1"))("error")("code").num.toInt shouldBe -32602
    call("snapshot.diff", ujson.Obj("from" -> "../x", "to" -> "a2"))("error")(
      "code"
    ).num.toInt shouldBe -32602

    call("jvm.recording.start")("result")("recording").bool shouldBe true
    call("jvm.recording.stop", ujson.Obj("path" -> "jvm/run.jfr"))("result")("artifact")(
      "relativePath"
    ).str shouldBe "jvm/run.jfr"
    call("jvm.recording.stop")("error")("code").num.toInt shouldBe -32602
  }

  test("artifact methods return bounded metadata instead of inline bytes") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val capture = response(
      endpoint.handleLine(
        request(
          2,
          "screen.capture",
          ujson.Obj(
            "screenId" -> "main",
            "path" -> "screens/main.png",
            "format" -> "png"
          )
        )
      )
    )("result")
    capture("relativePath").str shouldBe "screens/main.png"
    capture("size").str shouldBe "12"
    capture("sha256").str should have length 64
    capture.obj.contains("bytes") shouldBe false
  }

  test("stdio transport bounds frames and continues after malformed or oversized lines") {
    val input = Seq(
      "{",
      "x" * (StdioTransport.MaxLineCharacters + 1),
      request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)),
      notification("workspace.describe"),
      request(2, "workspace.describe")
    ).mkString("\n") + "\n"
    val output = new ByteArrayOutputStream()

    StdioTransport.serve(
      new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
      output,
      new JsonRpcEndpoint(new StubSession, None, () => ())
    )

    val frames = new String(output.toByteArray, StandardCharsets.UTF_8).linesIterator.toVector
    frames should have size 4
    frames.take(2).foreach(line => ujson.read(line)("error")("code").num.toInt shouldBe -32700)
    frames.foreach(line => ujson.read(line)("jsonrpc").str shouldBe "2.0")
    frames.lastOption.map(line => ujson.read(line)("id").num.toInt) shouldBe Some(2)
  }

  test("stdio transport accepts a byte order mark before the first frame") {
    val input = "﻿" + request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)) + "\n"
    val output = new ByteArrayOutputStream()

    StdioTransport.serve(
      new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
      output,
      new JsonRpcEndpoint(new StubSession, None, () => ())
    )

    val frame = ujson.read(new String(output.toByteArray, StandardCharsets.UTF_8).trim)
    frame.obj.contains("error") shouldBe false
    frame("id").num.toInt shouldBe 1
  }

  test("clock methods expose bounded controls and lossless counters") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val started = response(
      endpoint.handleLine(request(2, "simulation.start", ujson.Obj("tps" -> 100)))
    )("result")
    started("state").str shouldBe "running"
    started("targetTps").num.toInt shouldBe 100
    started("totalTicks").str shouldBe "9007199254740993"

    response(endpoint.handleLine(request(3, "simulation.pause")))("result")("state").str shouldBe
      "paused"
    val stepped = response(
      endpoint.handleLine(request(4, "simulation.step", ujson.Obj("count" -> 3)))
    )("result")
    stepped("totalTicks").str shouldBe "9007199254740996"
    response(endpoint.handleLine(request(5, "simulation.resume")))("result")("state").str shouldBe
      "running"
    response(endpoint.handleLine(request(6, "simulation.rate", ujson.Obj("tps" -> 20))))(
      "result"
    )("targetTps").num.toInt shouldBe 20
    response(endpoint.handleLine(request(7, "simulation.status")))("result")("state").str shouldBe
      "running"

    response(
      endpoint.handleLine(request(8, "simulation.start", ujson.Obj("tps" -> 1001)))
    )("error")("code").num.toInt shouldBe -32602
  }

  test("protocol execution limits reject effectively unbounded runs") {
    val endpoint = new JsonRpcEndpoint(new StubSession, None, () => ())
    response(
      endpoint.handleLine(request(1, "harness.version", ujson.Obj("protocolMajor" -> 1)))
    )

    val result = response(
      endpoint.handleLine(
        request(
          2,
          "simulation.run",
          ujson.Obj(
            "condition" -> ujson.Obj(
              "type" -> "screen_contains",
              "screenId" -> "main",
              "text" -> "READY"
            ),
            "maxTicks" -> 10000001,
            "timeoutMillis" -> 300001
          )
        )
      )
    )
    result("id").num.toInt shouldBe 2
    result("error")("code").num.toInt shouldBe -32602
  }

  private def request(id: Int, method: String, params: ujson.Obj = ujson.Obj()): String =
    ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))

  private def request(id: String, method: String): String =
    ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method))

  private def notification(method: String): String =
    ujson.write(ujson.Obj("jsonrpc" -> "2.0", "method" -> method))

  private def response(value: Option[String]): ujson.Value =
    ujson.read(value.getOrElse(fail("expected a JSON-RPC response")))

  private final class StubSession extends HarnessSession {
    private val projectId = ProjectId.parse("fixture").toOption.get
    private val computerId = ComputerId.parse("main").toOption.get
    private var clock = SimulationClockStatus(
      SimulationClockState.Paused,
      20,
      0.0,
      9007199254740993L,
      0L,
      0L
    )
    override def describe(): WorkspaceDescription =
      WorkspaceDescription(projectId, Vector.empty, Vector.empty, Vector.empty)

    override def startMachine(id: ComputerId): Either[HarnessError, MachineStatus] =
      if (id == computerId) Right(MachineStatus(id, MachineState.Running, None))
      else Left(UnknownComputer(id.value))

    override def stopMachine(id: ComputerId): Either[HarnessError, MachineStatus] =
      Right(MachineStatus(id, MachineState.Stopped, None))

    override def resetMachine(id: ComputerId): Either[HarnessError, MachineStatus] =
      Right(MachineStatus(id, MachineState.Running, None))

    override def machineStatus(id: ComputerId): Either[HarnessError, MachineStatus] =
      if (id == computerId)
        Right(
          MachineStatus(id, MachineState.Running, None, Some(MachineMemory(3145728L, 4194304L)))
        )
      else Left(UnknownComputer(id.value))

    override def run(request: RunRequest): Either[HarnessError, RunResult] = {
      val screenId = ScreenId.parse("main").toOption.get
      Right(
        RunResult(
          RunStopReason.ConditionSatisfied,
          1,
          1.millis,
          Vector(MachineStatus(computerId, MachineState.Running, None)),
          Map(screenId -> 1L),
          EventSnapshot(Vector(SessionEvent(1L, "ready", None, Some("READY"))), 0L),
          Map(screenId -> screen(screenId)),
          Vector(
            RunObservation(
              1,
              1.millis,
              Vector(MachineStatus(computerId, MachineState.Running, None)),
              Map(screenId -> 1L)
            )
          )
        )
      )
    }

    override def startClock(tps: Option[Int]): Either[HarnessError, SimulationClockStatus] = {
      clock = clock.copy(
        state = SimulationClockState.Running,
        targetTps = tps.getOrElse(clock.targetTps)
      )
      Right(clock)
    }

    override def pauseClock(): Either[HarnessError, SimulationClockStatus] = {
      clock = clock.copy(state = SimulationClockState.Paused)
      Right(clock)
    }

    override def resumeClock(): Either[HarnessError, SimulationClockStatus] =
      startClock(None)

    override def stepClock(count: Int): Either[HarnessError, SimulationClockStatus] = {
      clock = clock.copy(totalTicks = clock.totalTicks + count)
      Right(clock)
    }

    override def setClockRate(tps: Int): Either[HarnessError, SimulationClockStatus] = {
      clock = clock.copy(targetTps = tps)
      Right(clock)
    }

    override def clockStatus(): Either[HarnessError, SimulationClockStatus] = Right(clock)

    override def readScreen(id: ScreenId): Either[HarnessError, ScreenSnapshot] =
      Right(screen(id))

    private def screen(id: ScreenId): ScreenSnapshot =
      ScreenSnapshot(
        id,
        1L,
        1,
        1,
        "READY",
        Vector(ScreenCell('R', 0xffffff, 0x000000, 0xff00.toShort)),
        Vector.fill(16)(0),
        8,
        powered = true,
        1L,
        Some("00000000-0000-0000-0000-000000000001"),
        precisionMode = false
      )

    override def captureScreen(
        id: ScreenId,
        request: ScreenArtifactRequest
    ): Either[HarnessError, ArtifactDescription] =
      Right(
        ArtifactDescription(
          request.relativePath,
          12L,
          "image/png",
          "0" * 64
        )
      )

    override def send(id: ScreenId, input: UserInput): Either[HarnessError, InputResult] =
      Right(InputResult(1, 0))

    override def recentEvents(): Either[HarnessError, EventSnapshot] =
      Right(EventSnapshot(Vector.empty, 0L))

    override def saveSnapshot(
        request: SnapshotRequest
    ): Either[HarnessError, SnapshotDescription] =
      Right(
        SnapshotDescription(
          request.name,
          s"${request.name.value}.nbt",
          10L,
          "1" * 64,
          request.hostDisks,
          1L
        )
      )

    override def loadSnapshot(name: SnapshotName): Either[HarnessError, WorkspaceDescription] =
      Right(describe())

    override def ejectMedia(driveId: String): Either[HarnessError, MediaEjectResult] =
      if (driveId == "drive") Right(MediaEjectResult(driveId, ejected = true))
      else Left(HarnessError.UnknownDrive(driveId))

    override def setRedstoneInput(
        id: ComputerId,
        side: String,
        value: Int
    ): Either[HarnessError, RedstoneInputResult] =
      if (value < 0 || value > 15) Left(HarnessError.InvalidInput("redstone input must be 0-15"))
      else Right(RedstoneInputResult(id.value, side, value))

    override def diagnostics(request: DiagnosticRequest): Either[HarnessError, DiagnosticBundle] =
      Right(
        DiagnosticBundle(
          ArtifactDescription(request.relativePath, 10L, "application/zip", "2" * 64),
          Vector("manifest.conf")
        )
      )

    private var mode: ExecutionMode = ExecutionMode.Threaded
    private val paused =
      DebugState(Some("main"), paused = true, Some("breakpoint"), 3L, Vector.empty)

    override def readTrace(query: TraceQuery): Either[HarnessError, TracePage] =
      Right(
        TracePage(
          Vector(
            TraceEvent(
              5L,
              40L,
              Some("main"),
              "call",
              "gpu.set",
              Vector("1", "1", "\"READY\""),
              Vector("true"),
              1200L
            )
          ).filter(_.sequence > query.afterSequence),
          0L,
          5L
        )
      )

    override def machineMetrics(): Either[HarnessError, Vector[MachineMetrics]] =
      Right(
        Vector(
          MachineMetrics(
            "main",
            Some(1000L),
            Some(4096L),
            2000L,
            7L,
            9000L,
            3000L,
            5000000000L,
            0L,
            0.5,
            2L,
            2L,
            0,
            1,
            256,
            1L,
            Vector(CallCount("gpu.set", 3L, 0L, 3600L))
          )
        )
      )

    override def metricsHistory(
        computerId: Option[ComputerId],
        afterTick: Long
    ): Either[HarnessError, Vector[MetricsSample]] =
      Right(Vector(MetricsSample(41L, "main", 1000L, 300L, 0, 1L)).filter(_.tick > afterTick))

    override def resetMetrics(): Either[HarnessError, Unit] = Right(())

    override def startProfile(computerId: Option[ComputerId]): Either[HarnessError, Unit] = Right(
      ()
    )

    override def stopProfile(
        relativePath: Option[String]
    ): Either[HarnessError, (ProfileReport, Vector[ArtifactDescription])] =
      Right(
        ProfileReport(
          Some("main"),
          4L,
          20L,
          Vector("/main.lua:6" -> 4L),
          Vector(ProfileEntry(Vector("main", "work (/main.lua:4)"), 4L))
        ) ->
          relativePath.toVector.map(path =>
            ArtifactDescription(s"$path.svg", 10L, "image/svg+xml", "3" * 64)
          )
      )

    override def executionMode(): Either[HarnessError, ExecutionMode] = Right(mode)

    override def setExecutionMode(value: ExecutionMode): Either[HarnessError, ExecutionMode] = {
      mode = value
      Right(value)
    }

    override def startRecording(name: SnapshotName): Either[HarnessError, RecordingStatus] =
      Right(RecordingStatus(name.value, active = true, 10L, 0L, 0, limitReached = false))

    override def stopRecording(): Either[HarnessError, RecordingStatus] =
      Left(HarnessError.RecordingInvalid("nothing is being recorded"))

    override def replay(request: ReplayRequest): Either[HarnessError, ReplayResult] =
      Right(
        ReplayResult(
          request.name.value,
          12L,
          100L,
          Some(22L),
          Some("calls, signals or inputs differ"),
          22L
        )
      )

    override def sweep(request: SweepRequest): Either[HarnessError, SweepResult] =
      Right(
        SweepResult(
          Vector.tabulate(request.runs)(index =>
            SweepRun(
              request.seed + index,
              index != 1,
              50L,
              request.minServerTps,
              request.maxServerTps
            )
          ),
          if (request.runs > 1) Vector(request.seed + 1) else Vector.empty
        )
      )

    override def setBreakpoints(
        file: java.nio.file.Path,
        lines: Vector[Int]
    ): Either[HarnessError, Vector[DebugBreakpoint]] =
      Right(lines.map(DebugBreakpoint(file.toString, _)))

    override def debugPause(): Either[HarnessError, DebugState] = Right(paused.copy(paused = false))

    override def debugState(
        afterSequence: Option[Long],
        waitMillis: Long
    ): Either[HarnessError, DebugState] =
      Right(paused)

    override def debugFrames(): Either[HarnessError, Vector[DebugFrame]] =
      Right(Vector(DebugFrame(0, "/main.lua", 14, "main chunk", Some("/project/main.lua"))))

    override def debugVariables(reference: String): Either[HarnessError, Vector[DebugVariable]] =
      if (reference == "0:L") Right(Vector(DebugVariable("count", "3", "number", None)))
      else Left(HarnessError.InvalidInput(s"unknown variable reference: $reference"))

    override def debugResume(mode: String): Either[HarnessError, DebugState] =
      Right(paused.copy(paused = false, reason = None))

    override def diffSnapshots(
        from: SnapshotName,
        to: SnapshotName
    ): Either[HarnessError, SnapshotComparison] =
      Right(
        SnapshotComparison(
          from.value,
          to.value,
          97L,
          130L,
          Vector(
            SnapshotChange("screen main/text line 1", "changed", Some("COUNT 20"), Some("COUNT 52"))
          ),
          1,
          Vector(s"${from.value} did not copy host folder disks, so their files are not compared")
        )
      )

    override def startJvmRecording(): Either[HarnessError, Unit] = Right(())

    override def stopJvmRecording(relativePath: String): Either[HarnessError, ArtifactDescription] =
      Right(ArtifactDescription(relativePath, 2048L, "application/octet-stream", "4" * 64))

    override def close(): Unit = ()
  }
}
