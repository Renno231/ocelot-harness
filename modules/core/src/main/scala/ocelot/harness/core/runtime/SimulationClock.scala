package ocelot.harness.core.runtime

import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable.ArrayDeque
import scala.util.control.NonFatal

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.{InvalidClockRequest, SessionClosed}
import ocelot.harness.core.workspace.{SimulationClockState, SimulationClockStatus}

private[runtime] object TickDeadline {
  final case class Advance(nextDeadlineNanos: Long, overrun: Boolean)

  def next(
      previousDeadlineNanos: Long,
      completedAtNanos: Long,
      periodNanos: Long
  ): Advance = {
    require(periodNanos > 0L, "tick period must be positive")
    val nominal = saturatedAdd(previousDeadlineNanos, periodNanos)
    if (completedAtNanos <= nominal) Advance(nominal, overrun = false)
    else {
      val lateBy = completedAtNanos - nominal
      val periodsToSkip = lateBy / periodNanos + 1L
      val skippedNanos = saturatedMultiply(periodsToSkip, periodNanos)
      Advance(saturatedAdd(nominal, skippedNanos), overrun = true)
    }
  }

  private def saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MaxValue - right) Long.MaxValue else left + right

  private def saturatedMultiply(left: Long, right: Long): Long =
    if (left > 0L && right > Long.MaxValue / left) Long.MaxValue else left * right
}

private[runtime] final class SimulationClock(
    sessionName: String,
    initialTps: Int,
    autoStart: Boolean,
    executeSerialized: (() => Unit) => Either[HarnessError, Unit],
    tickWorkspace: () => Unit,
    nanoTime: () => Long = () => System.nanoTime()
) extends AutoCloseable {
  SimulationClock
    .validateTps(initialTps)
    .fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    )
  require(executeSerialized != null, "serialized executor is required")
  require(tickWorkspace != null, "workspace tick is required")
  require(nanoTime != null, "monotonic time source is required")

  private val lock = new AnyRef
  private val closed = new AtomicBoolean(false)
  private val tickTimes = ArrayDeque.empty[Long]
  private var running = autoStart
  private var targetTps = initialTps
  private var generation = 0L
  private var nextDeadlineNanos = nanoTime()
  private var totalTicks = 0L
  private var overrunCount = 0L
  private var lastTickDurationNanos = 0L

  private val worker = new Thread(
    new Runnable {
      override def run(): Unit = runClock()
    },
    s"ocelot-harness-clock-$sessionName"
  )
  worker.setDaemon(false)
  worker.start()

  def start(requestedTps: Option[Int]): Either[HarnessError, SimulationClockStatus] =
    requestedTps match {
      case Some(value) => SimulationClock.validateTps(value).map(startValidated)
      case None        => Right(startValidated(targetTpsValue))
    }

  def pause(): SimulationClockStatus = lock.synchronized {
    running = false
    resetScheduleLocked(immediate = false)
    statusLocked(nanoTime())
  }

  def resume(): SimulationClockStatus = startValidated(targetTpsValue)

  def setRate(tps: Int): Either[HarnessError, SimulationClockStatus] =
    SimulationClock.validateTps(tps).map { valid =>
      lock.synchronized {
        targetTps = valid
        tickTimes.clear()
        resetScheduleLocked(immediate = running)
        statusLocked(nanoTime())
      }
    }

  def step(count: Int): Either[HarnessError, SimulationClockStatus] = {
    if (count < 1 || count > SimulationClock.MaxStepTicks)
      Left(
        InvalidClockRequest(
          s"step count must be between 1 and ${SimulationClock.MaxStepTicks}"
        )
      )
    else
      lock
        .synchronized {
          if (running) Left(InvalidClockRequest("pause the simulation clock before stepping"))
          else Right(())
        }
        .map { _ =>
          beginManualTicks()
          try {
            var index = 0
            while (index < count) {
              tickAndRecord(manual = true, scheduledDeadline = 0L)
              index += 1
            }
          } finally endManualTicks()
          status()
        }
  }

  def status(): SimulationClockStatus = lock.synchronized(statusLocked(nanoTime()))

  def beginManualTicks(): Unit = lock.synchronized {
    generation = saturatedIncrement(generation)
    lock.notifyAll()
  }

  def recordManualTick(operation: => Unit): Unit =
    tickAndRecord(manual = true, scheduledDeadline = 0L, operation)

  def endManualTicks(): Unit = lock.synchronized {
    if (running) tickTimes.clear()
    resetScheduleLocked(immediate = false)
  }

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) {
      lock.synchronized {
        running = false
        generation = saturatedIncrement(generation)
        lock.notifyAll()
      }
      worker.interrupt()
      if (Thread.currentThread() ne worker) {
        worker.join(5000L)
        if (worker.isAlive)
          throw new IllegalStateException("simulation clock did not stop within 5 seconds")
      }
    }
  }

  private def startValidated(tps: Int): SimulationClockStatus = lock.synchronized {
    targetTps = tps
    running = true
    tickTimes.clear()
    resetScheduleLocked(immediate = true)
    statusLocked(nanoTime())
  }

  private def targetTpsValue: Int = lock.synchronized(targetTps)

  private def runClock(): Unit = {
    var keepRunning = true
    while (keepRunning) {
      nextTicket() match {
        case None => keepRunning = false
        case Some(ticket) =>
          executeSerialized(() => performScheduledTick(ticket)) match {
            case Left(SessionClosed) => keepRunning = false
            case Left(error) =>
              System.err.println(s"ERROR: simulation clock stopped: ${error.message}")
              lock.synchronized {
                running = false
                resetScheduleLocked(immediate = false)
              }
            case Right(_) =>
          }
      }
    }
  }

  private def nextTicket(): Option[SimulationClock.Ticket] = lock.synchronized {
    while (!closed.get()) {
      if (!running) lock.wait()
      else {
        val now = nanoTime()
        val remaining = nextDeadlineNanos - now
        if (remaining <= 0L)
          return Some(SimulationClock.Ticket(generation, nextDeadlineNanos))
        waitNanos(remaining)
      }
    }
    None
  }

  private def performScheduledTick(ticket: SimulationClock.Ticket): Unit = {
    val permitted = lock.synchronized {
      !closed.get() && running && generation == ticket.generation &&
      nextDeadlineNanos == ticket.deadlineNanos
    }
    if (permitted) tickAndRecord(manual = false, ticket.deadlineNanos)
  }

  private def tickAndRecord(
      manual: Boolean,
      scheduledDeadline: Long,
      operation: => Unit = tickWorkspace()
  ): Unit = {
    val startedAt = nanoTime()
    try operation
    catch {
      case NonFatal(error) =>
        lock.synchronized {
          running = false
          resetScheduleLocked(immediate = false)
        }
        throw error
    }
    val completedAt = nanoTime()
    val duration = math.max(0L, completedAt - startedAt)
    lock.synchronized {
      totalTicks = saturatedIncrement(totalTicks)
      lastTickDurationNanos = duration
      tickTimes.append(completedAt)
      while (tickTimes.size > SimulationClock.MeasurementWindowTicks) tickTimes.removeHead()
      if (!manual) {
        val advance = TickDeadline.next(
          scheduledDeadline,
          completedAt,
          periodNanos(targetTps)
        )
        if (advance.overrun) overrunCount = saturatedIncrement(overrunCount)
        nextDeadlineNanos = advance.nextDeadlineNanos
      }
      lock.notifyAll()
    }
  }

  private def resetScheduleLocked(immediate: Boolean): Unit = {
    generation = saturatedIncrement(generation)
    val now = nanoTime()
    nextDeadlineNanos = if (immediate) now else saturatedAdd(now, periodNanos(targetTps))
    lock.notifyAll()
  }

  private def statusLocked(now: Long): SimulationClockStatus = {
    val measured =
      if (!running || tickTimes.size < 2) 0.0
      else {
        val last = tickTimes.last
        val staleAfter = math.max(2000000000L, periodNanos(targetTps) * 4L)
        if (now - last > staleAfter) 0.0
        else {
          val elapsed = last - tickTimes.head
          if (elapsed <= 0L) 0.0
          else (tickTimes.size - 1).toDouble * 1000000000.0 / elapsed.toDouble
        }
      }
    SimulationClockStatus(
      if (running) SimulationClockState.Running else SimulationClockState.Paused,
      targetTps,
      measured,
      totalTicks,
      overrunCount,
      lastTickDurationNanos
    )
  }

  private def waitNanos(value: Long): Unit = {
    val bounded = math.max(1L, value)
    val millis = bounded / 1000000L
    val nanos = (bounded % 1000000L).toInt
    try lock.wait(millis, nanos)
    catch {
      case _: InterruptedException =>
        if (!closed.get()) Thread.currentThread().interrupt()
    }
  }

  private def periodNanos(tps: Int): Long = 1000000000L / tps.toLong

  private def saturatedIncrement(value: Long): Long =
    if (value == Long.MaxValue) value else value + 1L

  private def saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MaxValue - right) Long.MaxValue else left + right
}

private[runtime] object SimulationClock {
  val MinTps = 1
  val MaxTps = 1000
  val MaxStepTicks = 10000
  val MeasurementWindowTicks = 256

  private final case class Ticket(generation: Long, deadlineNanos: Long)

  def validateTps(value: Int): Either[HarnessError, Int] =
    if (value >= MinTps && value <= MaxTps) Right(value)
    else Left(InvalidClockRequest(s"TPS must be between $MinTps and $MaxTps"))
}
