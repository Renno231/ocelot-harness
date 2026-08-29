package ocelot.harness.core.workspace

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.FiniteDuration

import ocelot.harness.core.project.{ComputerId, ScreenId}

sealed trait MachineState extends Product with Serializable
object MachineState {
  case object Running extends MachineState
  case object Paused extends MachineState
  case object Stopped extends MachineState
  case object Crashed extends MachineState
}

final case class MachineStatus(
    id: ComputerId,
    state: MachineState,
    lastError: Option[String]
)

sealed trait TickPace extends Product with Serializable
object TickPace {
  case object Accelerated extends TickPace
  final case class FixedDelay(delay: FiniteDuration) extends TickPace
}

sealed trait StopCondition extends Product with Serializable
final case class ScreenContains(screenId: ScreenId, text: String) extends StopCondition
final case class ScreenRevisionAfter(screenId: ScreenId, revision: Long) extends StopCondition
final case class MachineReaches(computerId: ComputerId, state: MachineState) extends StopCondition
final case class EventOccurs(kind: String) extends StopCondition

final class RunCancellation private (private val cancelled: AtomicBoolean) {
  def cancel(): Unit = cancelled.set(true)
  def isCancelled: Boolean = cancelled.get()
}

object RunCancellation {
  def create(): RunCancellation = new RunCancellation(new AtomicBoolean(false))
}

final case class RunRequest(
    condition: StopCondition,
    maxTicks: Int,
    maxWallTime: FiniteDuration,
    pace: TickPace = TickPace.Accelerated,
    cancellation: RunCancellation = RunCancellation.create()
)

sealed trait RunStopReason extends Product with Serializable
object RunStopReason {
  case object ConditionSatisfied extends RunStopReason
  final case class ConditionTimedOut(limit: String) extends RunStopReason
  case object Cancelled extends RunStopReason
}

final case class SessionEvent(
    sequence: Long,
    kind: String,
    sourceAddress: Option[String],
    message: Option[String]
)

final case class EventSnapshot(events: Vector[SessionEvent], droppedCount: Long)

final case class RunObservation(
    elapsedTicks: Int,
    elapsedWallTime: FiniteDuration,
    machines: Vector[MachineStatus],
    screenRevisions: Map[ScreenId, Long]
)

final case class RunResult(
    stopReason: RunStopReason,
    elapsedTicks: Int,
    elapsedWallTime: FiniteDuration,
    machines: Vector[MachineStatus],
    screenRevisions: Map[ScreenId, Long],
    events: EventSnapshot,
    screens: Map[ScreenId, ScreenSnapshot] = Map.empty,
    timeline: Vector[RunObservation] = Vector.empty,
    timelineDroppedCount: Long = 0L
)

final case class ScreenCell(
    codePoint: Int,
    foreground: Int,
    background: Int,
    packedColor: Short
)

final case class ScreenSnapshot(
    id: ScreenId,
    revision: Long,
    width: Int,
    height: Int,
    text: String,
    cells: Vector[ScreenCell],
    palette: Vector[Int],
    colorDepth: Int,
    powered: Boolean,
    captureTick: Long,
    runtimeAddress: Option[String] = None,
    precisionMode: Boolean = false
)

sealed trait UserInput extends Product with Serializable {
  def user: String
}

object UserInput {
  final case class KeyDown(key: String, character: Char = 0, user: String = "agent")
      extends UserInput
  final case class KeyUp(key: String, character: Char = 0, user: String = "agent") extends UserInput
  final case class TypeText(text: String, interKeyTicks: Int = 1, user: String = "agent")
      extends UserInput
  final case class Paste(text: String, user: String = "agent") extends UserInput
  final case class Touch(x: Double, y: Double, button: Int = 0, user: String = "agent")
      extends UserInput
  final case class Drag(
      fromX: Double,
      fromY: Double,
      toX: Double,
      toY: Double,
      button: Int = 0,
      steps: Int = 1,
      user: String = "agent"
  ) extends UserInput
  final case class Drop(x: Double, y: Double, button: Int = 0, user: String = "agent")
      extends UserInput
  final case class Scroll(x: Double, y: Double, delta: Int, user: String = "agent")
      extends UserInput
}

final case class InputResult(eventsSent: Int, ticksAdvanced: Int)
