package ocelot.harness.core.runtime

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.{
  AbstractExecutorService,
  Callable,
  Delayed,
  ScheduledExecutorService,
  ScheduledFuture,
  TimeUnit
}
import java.util.{List => JList}

import scala.collection.mutable.ArrayBuffer

import totoro.ocelot.brain.entity.machine.{Machine, MachineInternals}

/** Sees each computer slice the scheduler runs. */
private[runtime] trait SliceObserver {
  def beforeSlice(machine: Machine): Unit
  def afterSlice(machine: Machine, durationNanos: Long): Unit
}

/** The executor every computer schedules its slices on, in place of the brain's own originalPool.
  *
  * Threaded (the default) hands each slice to the brain's pool after the same delay, as the game
  * does. Deterministic keeps slices until the session drains them on its tick thread, in a fixed
  * computer order and a fixed number of rounds per tick, so a run from the same state with the same
  * inputs repeats exactly. Either way every slice passes the session's observer, which installs
  * instrumentation and measures the slice.
  */
private[runtime] final class MachineScheduler(val originalPool: ScheduledExecutorService)
    extends AbstractExecutorService
    with ScheduledExecutorService {
  @volatile private var observer: Option[SliceObserver] = None
  @volatile private var deterministic = false
  private val pending = ArrayBuffer.empty[Runnable]
  private val inFlight = new AtomicInteger(0)

  def observe(value: Option[SliceObserver]): Unit = observer = value

  def isDeterministic: Boolean = deterministic

  /** Switches mode. Called on the session lane between ticks. Slices already handed to the pool
    * finish first (bounded), and kept slices go to the pool when leaving deterministic mode.
    */
  def setDeterministic(value: Boolean): Unit = {
    if (value && !deterministic) {
      deterministic = true
      val deadline = System.nanoTime() + 2000000000L
      while (inFlight.get() > 0 && System.nanoTime() < deadline) LockSupport.parkNanos(200000L)
    } else if (!value && deterministic) {
      deterministic = false
      val kept = pending.synchronized {
        val copy = pending.toVector
        pending.clear()
        copy
      }
      kept.foreach(task => delegate(task, 0L))
    }
  }

  /** Runs kept slices: each round runs every slice kept when it began, ordered by `order`; slices a
    * round schedules wait for the next. Returns whether slices remain.
    */
  def drain(rounds: Int, order: Runnable => String): Boolean = {
    var round = 0
    while (round < rounds && pending.synchronized(pending.nonEmpty)) {
      val batch = pending.synchronized {
        val copy = pending.toVector
        pending.clear()
        copy
      }
      batch.sortBy(order).foreach(runSlice)
      round += 1
    }
    pending.synchronized(pending.nonEmpty)
  }

  def discardPending(): Unit = pending.synchronized(pending.clear())

  override def schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture[_] =
    if (deterministic) {
      pending.synchronized {
        // A computer schedules itself again while a slice is kept; one run covers both.
        if (!pending.exists(_ eq command)) pending += command
      }
      CompletedFuture
    } else delegate(command, unit.toNanos(delay))

  private def delegate(command: Runnable, delayNanos: Long): ScheduledFuture[_] = {
    inFlight.incrementAndGet()
    originalPool.schedule(
      new Runnable {
        override def run(): Unit =
          try runSlice(command)
          finally inFlight.decrementAndGet()
      },
      delayNanos,
      TimeUnit.NANOSECONDS
    )
  }

  private def runSlice(command: Runnable): Unit = command match {
    case machine: Machine =>
      val current = observer
      current.foreach(_.beforeSlice(machine))
      val startedAt = System.nanoTime()
      try machine.run()
      finally current.foreach(_.afterSlice(machine, System.nanoTime() - startedAt))
    case other => other.run()
  }

  override def schedule[V](callable: Callable[V], delay: Long, unit: TimeUnit): ScheduledFuture[V] =
    originalPool.schedule(callable, delay, unit)

  override def scheduleAtFixedRate(
      command: Runnable,
      initialDelay: Long,
      period: Long,
      unit: TimeUnit
  ): ScheduledFuture[_] = originalPool.scheduleAtFixedRate(command, initialDelay, period, unit)

  override def scheduleWithFixedDelay(
      command: Runnable,
      initialDelay: Long,
      delay: Long,
      unit: TimeUnit
  ): ScheduledFuture[_] = originalPool.scheduleWithFixedDelay(command, initialDelay, delay, unit)

  override def execute(command: Runnable): Unit = originalPool.execute(command)
  override def shutdown(): Unit = originalPool.shutdown()
  override def shutdownNow(): JList[Runnable] = originalPool.shutdownNow()
  override def isShutdown: Boolean = originalPool.isShutdown
  override def isTerminated: Boolean = originalPool.isTerminated
  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
    originalPool.awaitTermination(timeout, unit)

  private object CompletedFuture extends ScheduledFuture[AnyRef] {
    override def getDelay(unit: TimeUnit): Long = 0L
    override def compareTo(other: Delayed): Int = 0
    override def cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override def isCancelled: Boolean = false
    override def isDone: Boolean = true
    override def get(): AnyRef = null
    override def get(timeout: Long, unit: TimeUnit): AnyRef = null
  }
}

private[runtime] object MachineScheduler {

  /** Puts a scheduler in front of the brain's pool for the runtime's life. */
  def install(): MachineScheduler = {
    val original = MachineInternals.threadPool
    val scheduler = new MachineScheduler(original)
    MachineInternals.replaceThreadPool(scheduler)
    scheduler
  }

  def uninstall(scheduler: MachineScheduler): Unit = {
    scheduler.setDeterministic(false)
    scheduler.observe(None)
    MachineInternals.replaceThreadPool(scheduler.originalPool)
  }
}
