package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

import scala.collection.mutable.ArrayDeque

import ocelot.harness.core.workspace.{TraceEvent, TracePage, TraceQuery}

/** The session's bounded log of what computers did: calls, signals, state changes and inputs.
  * Thread-safe; computers append from their slice threads. Also folds each tick's events into a
  * checksum that recordings compare to find where a replay stops matching.
  */
private[runtime] final class FlightRecorder(capacity: Int) {
  require(capacity > 0, "trace capacity must be positive")

  private val events = ArrayDeque.empty[TraceEvent]
  private var nextSequence = 1L
  private var dropped = 0L
  private val tickChecksum = new CRC32

  def append(
      tick: Long,
      computerId: Option[String],
      kind: String,
      name: String,
      arguments: Vector[String] = Vector.empty,
      results: Vector[String] = Vector.empty,
      durationNanos: Long = 0L,
      note: Option[String] = None
  ): Unit = synchronized {
    if (events.size == capacity) {
      events.removeHead()
      dropped += 1L
    }
    events.append(
      TraceEvent(
        nextSequence,
        tick,
        computerId,
        kind,
        name,
        arguments,
        results,
        durationNanos,
        note
      )
    )
    nextSequence += 1L
    // Durations are wall time; everything else repeats in a deterministic replay.
    val stable = (computerId.getOrElse("") +: kind +: name +: (arguments ++ results ++ note))
      .mkString("\u0001")
    tickChecksum.update(stable.getBytes(StandardCharsets.UTF_8))
  }

  def read(query: TraceQuery): TracePage = synchronized {
    val matching = events.iterator.filter { event =>
      event.sequence > query.afterSequence &&
      query.computerId.forall(id => event.computerId.contains(id)) &&
      (query.kinds.isEmpty || query.kinds.contains(event.kind)) &&
      query.nameContains.forall(text => event.name.contains(text))
    }
    TracePage(matching.take(query.limit).toVector, dropped, nextSequence - 1L)
  }

  def all(): TracePage = synchronized(TracePage(events.toVector, dropped, nextSequence - 1L))

  def latestSequence: Long = synchronized(nextSequence - 1L)

  /** The checksum of events since the last call. */
  def takeTickChecksum(): Long = synchronized {
    val value = tickChecksum.getValue
    tickChecksum.reset()
    value
  }

  def clear(): Unit = synchronized {
    events.clear()
    tickChecksum.reset()
  }
}

/** Renders values a computer passes for people to read: strings quoted with bytes escaped,
  * everything bounded.
  */
private[runtime] object TraceText {
  val MaxValueChars = 160
  val MaxValues = 16

  def bytes(value: Array[Byte]): String = {
    val builder = new StringBuilder("\"")
    var index = 0
    while (index < value.length && builder.length < MaxValueChars) {
      val byte = value(index) & 0xff
      byte match {
        case '"'                        => builder.append("\\\"")
        case '\\'                       => builder.append("\\\\")
        case '\n'                       => builder.append("\\n")
        case '\t'                       => builder.append("\\t")
        case b if b >= 0x20 && b < 0x7f => builder.append(b.toChar)
        case b if b >= 0x80             =>
          // Keep UTF-8 text readable: decode a whole sequence when it is one.
          val length = if (b >= 0xf0) 4 else if (b >= 0xe0) 3 else if (b >= 0xc0) 2 else 1
          if (length > 1 && index + length <= value.length) {
            val text = new String(value, index, length, StandardCharsets.UTF_8)
            if (text.length >= 1 && !text.contains('�')) {
              builder.append(text)
              index += length - 1
            } else builder.append(f"\\x$b%02x")
          } else builder.append(f"\\x$b%02x")
        case b => builder.append(f"\\x$b%02x")
      }
      index += 1
    }
    if (index < value.length) builder.append(s"...\" (${value.length} bytes)")
    else builder.append('"')
    builder.toString
  }

  def string(value: String): String = bytes(value.getBytes(StandardCharsets.UTF_8))

  def number(value: Double): String =
    if (value == math.rint(value) && !value.isInfinite && math.abs(value) < 1e15)
      value.toLong.toString
    else value.toString

  /** A value the brain holds for a signal or call result. */
  def javaValue(value: Any): String = value match {
    case null                           => "nil"
    case text: String                   => string(text)
    case bytes: Array[Byte]             => this.bytes(bytes)
    case flag: java.lang.Boolean        => flag.toString
    case number: java.lang.Double       => this.number(number.doubleValue)
    case number: java.lang.Float        => this.number(number.doubleValue)
    case number: java.lang.Number       => number.longValue.toString
    case character: java.lang.Character => character.toInt.toString
    case map: java.util.Map[_, _]       => s"table(${map.size})"
    case other                          => other.getClass.getSimpleName
  }

  def javaValues(values: Array[AnyRef]): Vector[String] =
    if (values == null) Vector.empty
    else
      values.iterator.take(MaxValues).map(javaValue).toVector ++
        (if (values.length > MaxValues) Vector(s"... ${values.length - MaxValues} more")
         else Vector.empty)
}
