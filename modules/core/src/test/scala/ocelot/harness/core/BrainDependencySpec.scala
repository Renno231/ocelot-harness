package ocelot.harness.core

import org.scalatest.funsuite.AnyFunSuite
import totoro.ocelot.brain.Ocelot

final class BrainDependencySpec extends AnyFunSuite {
  test("the pinned brain source project exposes the approved version") {
    assert(Ocelot.Version === "0.24.2")
  }
}
