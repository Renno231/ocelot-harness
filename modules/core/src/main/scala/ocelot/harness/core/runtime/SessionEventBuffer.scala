package ocelot.harness.core.runtime

import scala.collection.mutable.ArrayDeque

import totoro.ocelot.brain.event.{Event, EventBus, MachineCrashEvent, NodeEvent}

import ocelot.harness.core.workspace.{EventSnapshot, SessionEvent}

private[runtime] final class SessionEventBuffer(capacity: Int) extends AutoCloseable {
  require(capacity > 0, "event buffer capacity must be positive")

  private val events = ArrayDeque.empty[SessionEvent]
  private var nextSequence = 1L
  private var droppedCount = 0L
  private var closed = false
  private val subscription = EventBus.subscribe { case event => record(event) }

  def snapshot(): EventSnapshot = synchronized {
    EventSnapshot(events.toVector, droppedCount)
  }

  def latestSequence: Long = synchronized(nextSequence - 1L)

  override def close(): Unit = synchronized {
    if (!closed) {
      closed = true
      subscription.cancel()
      events.clear()
    }
  }

  private def record(event: Event): Unit = synchronized {
    if (!closed) {
      if (events.size == capacity) {
        events.removeHead()
        droppedCount += 1L
      }
      events.append(
        SessionEvent(
          nextSequence,
          eventKind(event),
          event match {
            case nodeEvent: NodeEvent => Option(nodeEvent.address)
            case _                    => None
          },
          event match {
            case crash: MachineCrashEvent => Option(crash.message)
            case _                        => None
          }
        )
      )
      nextSequence += 1L
    }
  }

  private def eventKind(event: Event): String = event match {
    case _: MachineCrashEvent                                           => "machine_crash"
    case value if value.getClass.getSimpleName.startsWith("TextBuffer") => "screen_changed"
    case value if value.getClass.getSimpleName.startsWith("FileSystem") => "filesystem_activity"
    case value if value.getClass.getSimpleName.startsWith("Network")    => "network_activity"
    case value if value.getClass.getSimpleName.startsWith("Beep")       => "beep"
    case _                                                              => "other"
  }
}
