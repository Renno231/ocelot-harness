package ocelot.harness.app

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.app.protocol.AppExitCode

final class OcelotCtlSpec extends AnyFunSuite with Matchers {
  test("CLI options reject unknown and duplicate flags") {
    OcelotCtl.run(
      Array("screen", "wait", "main", "--contains", "READY", "--typo", "1")
    ) shouldBe AppExitCode.Usage
    OcelotCtl.run(
      Array(
        "screen",
        "wait",
        "main",
        "--contains",
        "READY",
        "--contains",
        "AGAIN"
      )
    ) shouldBe AppExitCode.Usage
  }

  test("global parsing stops at the command so option-like input remains data") {
    val project = Files.createTempDirectory("ocelot-harness-cli-parse-")
    try {
      OcelotCtl.run(
        Array("--project", project.toString, "screen", "paste", "main", "--json")
      ) shouldBe AppExitCode.Connection
    } finally Files.delete(project)
  }

  test("CLI durations reject numeric overflow") {
    OcelotCtl.run(
      Array(
        "screen",
        "wait",
        "main",
        "--contains",
        "READY",
        "--timeout",
        "999999999999999999999s"
      )
    ) shouldBe AppExitCode.Usage
  }
}
