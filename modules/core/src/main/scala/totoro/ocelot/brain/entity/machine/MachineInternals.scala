package totoro.ocelot.brain.entity.machine

import java.util.concurrent.ScheduledExecutorService

import scala.collection.mutable

import li.cil.repack.com.naef.jnlua.LuaState

import totoro.ocelot.brain.entity.machine.luac.NativeLuaArchitecture

// What the harness reads of a computer that ocelot-brain keeps internal. It sits in the brain's
// package so the compiler checks the state access against the pinned brain; fields private to
// Machine or MachineAPI are reached by reflection. KeyDeliverySpec and the inspection specs
// cover every one.
object MachineInternals {
  // Whether the computer has a slice scheduled or running. The brain starts a scheduled slice
  // `executionDelay` ms (12 by default) after scheduling it, and one for each queued signal.
  def sliceDue(machine: Machine): Boolean = machine.state.synchronized {
    machine.state.top match {
      case MachineAPI.State.Yielded | MachineAPI.State.SynchronizedReturn |
          MachineAPI.State.Running =>
        true
      case _ => false
    }
  }

  // Whether the computer still holds a signal of one of these names from this sender.
  def holdsSignal(machine: Machine, names: Set[String], sender: String): Boolean = {
    val queue = signalQueue.get(machine).asInstanceOf[mutable.Queue[GenericSignal]]
    queue.synchronized(queue.exists { signal =>
      names.contains(signal.name) && signal.args.headOption.contains(sender)
    })
  }

  def queuedSignals(machine: Machine): Int = {
    val queue = signalQueue.get(machine).asInstanceOf[mutable.Queue[GenericSignal]]
    queue.synchronized(queue.size)
  }

  // The native Lua state of a running computer, or None for LuaJ or a stopped computer.
  def nativeLua(machine: Machine): Option[LuaState] = machine.architecture match {
    case native: NativeLuaArchitecture => Option(native.lua)
    case _                             => None
  }

  // Whether the computer is in a call the brain runs on the tick thread (a non-direct call or one
  // whose direct-call budget ran out).
  def inSynchronizedCall(machine: Machine): Boolean =
    synchronizedCall.getBoolean(machine)

  /** Sees every signal the computer queues and takes. */
  trait SignalTap {
    def queued(name: String, args: Array[AnyRef], depth: Int): Unit
    def taken(name: String, args: Array[AnyRef], depth: Int): Unit
  }

  // Swaps the computer's signal queue for one that reports to the tap, keeping queued signals.
  // A machine keeps one queue for its life, so this is done once per machine.
  def tapSignals(machine: Machine, tap: SignalTap): Unit = {
    val current = signalQueue.get(machine).asInstanceOf[mutable.Queue[MachineAPI.Signal]]
    current match {
      case tapped: TappedQueue => tapped.tap = tap
      case _ =>
        current.synchronized {
          val replacement = new TappedQueue(tap)
          current.foreach(replacement.addQuietly)
          signalQueue.set(machine, replacement)
        }
    }
  }

  private final class TappedQueue(@volatile var tap: SignalTap)
      extends mutable.Queue[MachineAPI.Signal](16) {
    def addQuietly(signal: MachineAPI.Signal): Unit = super.addOne(signal)

    override def addOne(signal: MachineAPI.Signal): this.type = {
      super.addOne(signal)
      tap.queued(signal.name, signal.args, size)
      this
    }

    override def removeHead(resizeInternalRepr: Boolean): MachineAPI.Signal = {
      val signal = super.removeHead(resizeInternalRepr)
      tap.taken(signal.name, signal.args, size)
      signal
    }
  }

  // The executor every computer schedules its slices on. One per process.
  def threadPool: ScheduledExecutorService = MachineAPI.threadPool

  /** The pool is a static final field, which reflection may not set; Unsafe can, and does so before
    * any computer has read it, so no compiled code holds the old value.
    */
  def replaceThreadPool(replacement: ScheduledExecutorService): Unit = {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val instance = unsafeClass.getDeclaredField("theUnsafe")
    instance.setAccessible(true)
    val unsafe = instance.get(null)
    val base = unsafeClass
      .getMethod("staticFieldBase", classOf[java.lang.reflect.Field])
      .invoke(unsafe, threadPoolField)
    val offset = unsafeClass
      .getMethod("staticFieldOffset", classOf[java.lang.reflect.Field])
      .invoke(unsafe, threadPoolField)
    unsafeClass
      .getMethod("putObjectVolatile", classOf[AnyRef], java.lang.Long.TYPE, classOf[AnyRef])
      .invoke(unsafe, base, offset, replacement)
    if (!(MachineAPI.threadPool eq replacement))
      throw new IllegalStateException("could not replace the computers' slice pool")
  }

  private val signalQueue = {
    val field = classOf[Machine].getDeclaredFields
      .find(field => field.getName == "signals" || field.getName.endsWith("$$signals"))
      .getOrElse(throw new IllegalStateException("ocelot-brain Machine has no signal queue"))
    field.setAccessible(true)
    field
  }

  private val synchronizedCall = {
    val field = classOf[Machine].getDeclaredFields
      .find(field =>
        field.getName == "inSynchronizedCall" || field.getName.endsWith("$$inSynchronizedCall")
      )
      .getOrElse(throw new IllegalStateException("ocelot-brain Machine has no synchronized flag"))
    field.setAccessible(true)
    field
  }

  private val threadPoolField = MachineAPI.getClass.getDeclaredField("threadPool")
}
