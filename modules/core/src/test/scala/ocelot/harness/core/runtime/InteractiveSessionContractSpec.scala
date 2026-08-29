package ocelot.harness.core.runtime

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import totoro.ocelot.brain.event.{BeepEvent, EventBus}

import ocelot.harness.core.project.{ComputerId, ScreenId}
import ocelot.harness.core.workspace._

final class InteractiveSessionContractSpec extends AnyFunSuite with Matchers with EitherValues {
  test("the session command lane linearizes concurrent callers and rejects work after close") {
    val lane = new SessionCommandLane("contract-test")
    val callerPool = Executors.newFixedThreadPool(8)
    implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(callerPool)
    val start = new CountDownLatch(1)
    val active = new AtomicInteger(0)
    val maximumActive = new AtomicInteger(0)
    val sequence = new AtomicInteger(0)

    try {
      val results = (1 to 24).map { _ =>
        Future {
          start.await()
          lane.execute {
            val nowActive = active.incrementAndGet()
            maximumActive.accumulateAndGet(nowActive, Math.max)
            try sequence.incrementAndGet()
            finally active.decrementAndGet()
          }.value
        }
      }
      start.countDown()

      Await.result(Future.sequence(results), 10.seconds).sorted shouldBe (1 to 24)
      maximumActive.get() shouldBe 1

      lane.close()
      lane.execute(99).left.value.code shouldBe "session_closed"
    } finally {
      lane.close()
      callerPool.shutdownNow()
      callerPool.awaitTermination(5L, TimeUnit.SECONDS)
    }
  }

  test("the event buffer drops oldest values at its bound and stops collecting after close") {
    val buffer = new SessionEventBuffer(2)
    EventBus.send(BeepEvent("one", 440, 100))
    EventBus.send(BeepEvent("two", 440, 100))
    EventBus.send(BeepEvent("three", 440, 100))

    val snapshot = buffer.snapshot()
    snapshot.events.map(_.sourceAddress) shouldBe Vector(Some("two"), Some("three"))
    snapshot.events.map(_.kind) shouldBe Vector("beep", "beep")
    snapshot.droppedCount shouldBe 1L

    buffer.close()
    EventBus.send(BeepEvent("after-close", 440, 100))
    buffer.snapshot().events shouldBe empty
  }

  test("run, screen, event, and input models remain brain-free immutable values") {
    val computerId = ComputerId.parse("main").value
    val screenId = ScreenId.parse("main").value
    val cancellation = RunCancellation.create()
    val request = RunRequest(
      ScreenContains(screenId, "READY"),
      maxTicks = 200,
      maxWallTime = 5.seconds,
      pace = TickPace.Accelerated,
      cancellation = cancellation
    )
    val snapshot = ScreenSnapshot(
      screenId,
      revision = 1L,
      width = 2,
      height = 1,
      text = "OK",
      cells = Vector(
        ScreenCell('O', 0xffffff, 0x000000, 0xff00.toShort),
        ScreenCell('K', 0xffffff, 0x000000, 0xff00.toShort)
      ),
      palette = Vector.empty,
      colorDepth = 8,
      powered = true,
      captureTick = 3L
    )

    request.condition shouldBe ScreenContains(screenId, "READY")
    cancellation.isCancelled shouldBe false
    cancellation.cancel()
    cancellation.isCancelled shouldBe true
    snapshot.cells.map(_.codePoint) shouldBe Vector('O'.toInt, 'K'.toInt)
    snapshot.toString should not include "totoro.ocelot"
    MachineStatus(computerId, MachineState.Running, None).toString should not include
      "totoro.ocelot"
    UserInput.Paste("hello").user shouldBe "agent"
  }
}
