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

    override def run(request: RunRequest): Either[HarnessError, RunResult] =
      Right(
        RunResult(
          RunStopReason.ConditionSatisfied,
          1,
          1.millis,
          Vector.empty,
          Map.empty,
          EventSnapshot(Vector.empty, 0L)
        )
      )

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
      Right(
        ScreenSnapshot(id, 1L, 1, 1, "READY", Vector.empty, Vector.empty, 8, powered = true, 1L)
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

    override def diagnostics(request: DiagnosticRequest): Either[HarnessError, DiagnosticBundle] =
      Right(
        DiagnosticBundle(
          ArtifactDescription(request.relativePath, 10L, "application/zip", "2" * 64),
          Vector("manifest.conf")
        )
      )

    override def close(): Unit = ()
  }
}
