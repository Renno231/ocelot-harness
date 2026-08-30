package ocelot.harness.app.viewer

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.app.ViewerOptions

final class ViewerModelSpec extends AnyFunSuite with Matchers {
  test("screen wire snapshots decode lossless revisions, cells, and optional fields") {
    val decoded = ViewerProtocol.decodeScreen(
      ujson.Obj(
        "screenId" -> "main",
        "revision" -> "7",
        "width" -> 2,
        "height" -> 1,
        "text" -> "A ",
        "cells" -> ujson.Arr(
          ujson.Obj(
            "codePoint" -> 65,
            "foreground" -> 0xffffff,
            "background" -> 0x000000,
            "packedColor" -> 0xffff
          ),
          ujson.Obj(
            "codePoint" -> 32,
            "foreground" -> 0x123456,
            "background" -> 0x654321,
            "packedColor" -> 0x1234
          )
        ),
        "palette" -> ujson.Arr.from((0 until 16).map(value => ujson.Num(value.toDouble))),
        "colorDepth" -> 8,
        "powered" -> true,
        "captureTick" -> "9",
        "runtimeAddress" -> ujson.Null,
        "precisionMode" -> false
      )
    )

    withClue(decoded) { decoded.isRight shouldBe true }
    val screen = decoded.toOption.get
    screen.id.value shouldBe "main"
    screen.revision shouldBe 7L
    screen.captureTick shouldBe 9L
    screen.width shouldBe 2
    screen.height shouldBe 1
    screen.cells.map(_.codePoint) shouldBe Vector(65, 32)
    screen.cells(1).foreground shouldBe 0x123456
    screen.cells(1).packedColor shouldBe 0x1234.toShort
    screen.runtimeAddress shouldBe None
  }

  test("screen wire snapshots reject malformed dimensions and int64 fields") {
    val base = ujson.Obj(
      "screenId" -> "main",
      "revision" -> "7",
      "width" -> 2,
      "height" -> 1,
      "text" -> "A",
      "cells" -> ujson.Arr(
        ujson.Obj(
          "codePoint" -> 65,
          "foreground" -> 0xffffff,
          "background" -> 0,
          "packedColor" -> 0
        )
      ),
      "palette" -> ujson.Arr(),
      "colorDepth" -> 8,
      "powered" -> true,
      "captureTick" -> "9",
      "runtimeAddress" -> ujson.Null,
      "precisionMode" -> false
    )

    ViewerProtocol.decodeScreen(base).left.toOption.get should include("cell count")
    base("revision") = ujson.Str("7.0")
    ViewerProtocol.decodeScreen(base).left.toOption.get should include("revision")
  }

  test("clock wire status decodes lossless counters and rejects invalid state") {
    val status = ViewerProtocol.decodeClock(
      ujson.Obj(
        "state" -> "running",
        "targetTps" -> 100,
        "measuredTps" -> 98.5,
        "totalTicks" -> "9007199254740993",
        "overrunCount" -> "7",
        "lastTickDurationNanos" -> "250000"
      )
    )

    withClue(status) { status.isRight shouldBe true }
    status.toOption.get.state shouldBe "running"
    status.toOption.get.totalTicks shouldBe 9007199254740993L
    status.toOption.get.measuredTps shouldBe 98.5

    ViewerProtocol
      .decodeClock(
        ujson.Obj(
          "state" -> "stopped",
          "targetTps" -> 20,
          "measuredTps" -> 0,
          "totalTicks" -> "0",
          "overrunCount" -> "0",
          "lastTickDurationNanos" -> "0"
        )
      )
      .left
      .toOption
      .get should include("state")
  }

  test("workspace screen IDs decode in server order and reject invalid IDs") {
    val result = ViewerProtocol.decodeScreenIds(
      ujson.Obj(
        "screens" -> ujson.Arr(
          ujson.Obj("screenId" -> "alpha"),
          ujson.Obj("screenId" -> "beta")
        )
      )
    )
    result.map(_.map(_.value)) shouldBe Right(Vector("alpha", "beta"))

    ViewerProtocol
      .decodeScreenIds(ujson.Obj("screens" -> ujson.Arr(ujson.Obj("screenId" -> "BAD"))))
      .isLeft shouldBe true
  }

  test("image pixels map to bounded one-based screen cells") {
    val geometry = ViewerGeometry(
      screenWidth = 40,
      screenHeight = 8,
      imageWidth = 640,
      imageHeight = 256
    )

    geometry.cellAt(0, 0) shouldBe Some(ViewerCell(1, 1))
    geometry.cellAt(639, 255) shouldBe Some(ViewerCell(40, 8))
    geometry.cellAt(16, 32) shouldBe Some(ViewerCell(2, 2))
    geometry.cellAt(-1, 0) shouldBe None
    geometry.cellAt(640, 0) shouldBe None
  }

  test("viewer options are bounded and accept an explicit initial screen") {
    val parsed = ViewerOptions.parse(
      Vector("--project", "demo", "--screen", "alpha", "--scale", "3", "--refresh-ms", "250")
    )
    withClue(parsed) { parsed.isRight shouldBe true }
    val options = parsed.toOption.get
    options.projectRoot.getFileName.toString shouldBe "demo"
    options.initialScreen.map(_.value) shouldBe Some("alpha")
    options.scale shouldBe 3
    options.refreshMillis shouldBe 250L

    ViewerOptions.parse(Vector("--scale", "0")).left.toOption.get should include("scale")
    ViewerOptions.parse(Vector("--refresh-ms", "10")).left.toOption.get should include("refresh")
    ViewerOptions.parse(Vector("--unknown")).isLeft shouldBe true
  }

  test("viewer inputs use existing screen.input schema and viewer identity") {
    ViewerProtocol.touch("alpha", ViewerCell(2, 3), button = 1) shouldBe ujson.Obj(
      "screenId" -> "alpha",
      "input" -> ujson.Obj(
        "type" -> "touch",
        "x" -> 2,
        "y" -> 3,
        "button" -> 1,
        "user" -> "viewer"
      )
    )

    ViewerProtocol.paste("beta", "hello")("input")("type").str shouldBe "paste"
    ViewerProtocol.scroll("beta", ViewerCell(4, 5), -1)("input")("delta").num.toInt shouldBe -1
  }
}
