package totoro.ocelot.brain.entity.machine

// What the harness reads of a computer that ocelot-brain keeps internal. It sits in the brain's
// package so the compiler checks the state access against the pinned brain; the signal queue is
// private to Machine, so that one is read by reflection. KeyDeliverySpec covers both.
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
    val queue = signalQueue.get(machine).asInstanceOf[scala.collection.mutable.Queue[GenericSignal]]
    queue.synchronized(queue.exists { signal =>
      names.contains(signal.name) && signal.args.headOption.contains(sender)
    })
  }

  private val signalQueue = {
    val field = classOf[Machine].getDeclaredFields
      .find(field => field.getName == "signals" || field.getName.endsWith("$$signals"))
      .getOrElse(throw new IllegalStateException("ocelot-brain Machine has no signal queue"))
    field.setAccessible(true)
    field
  }
}
