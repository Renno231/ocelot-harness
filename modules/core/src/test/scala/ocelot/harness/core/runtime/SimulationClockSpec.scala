package ocelot.harness.core.runtime

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.workspace.{SimulationClockState, SimulationClockStatus}

final class SimulationClockSpec extends AnyFunSuite with Matchers {
  test("deadline calculation preserves target cadence and skips missed deadlines") {
    val period = 50L

    TickDeadline.next(0L, completedAtNanos = 40L, period) shouldBe
      TickDeadline.Advance(50L, overrun = false)
    TickDeadline.next(0L, completedAtNanos = 50L, period) shouldBe
      TickDeadline.Advance(50L, overrun = false)
    TickDeadline.next(0L, completedAtNanos = 51L, period) shouldBe
      TickDeadline.Advance(100L, overrun = true)
    TickDeadline.next(100L, completedAtNanos = 350L, period) shouldBe
      TickDeadline.Advance(400L, overrun = true)
  }

  test("deadline calculation rejects invalid periods and saturates instead of overflowing") {
    an[IllegalArgumentException] should be thrownBy TickDeadline.next(0L, 0L, 0L)
    TickDeadline.next(Long.MaxValue - 5L, Long.MaxValue - 1L, 10L).nextDeadlineNanos shouldBe
      Long.MaxValue
  }

  test("clock status is a brain-free immutable public value") {
    val status = SimulationClockStatus(
      SimulationClockState.Running,
      targetTps = 20,
      measuredTps = 19.75,
      totalTicks = 42L,
      overrunCount = 1L,
      lastTickDurationNanos = 250000L
    )

    status.state.name shouldBe "running"
    status.toString should not include "totoro.ocelot"
  }
}
