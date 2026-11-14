package ocelot.harness.core.workspace

/** One thing a computer did, as the flight recorder saw it.
  *
  * Kinds: `call` (a component call; `name` is `type.method`, `results` what it returned, `note`
  * `deferred` when the tick's direct-call budget ran out and the brain retried it on the tick
  * thread), `signal_queued` and `signal_taken` (`name` is the signal), `crash`, `started`,
  * `stopped`, and `input` (what the harness delivered).
  */
final case class TraceEvent(
    sequence: Long,
    tick: Long,
    computerId: Option[String],
    kind: String,
    name: String,
    arguments: Vector[String] = Vector.empty,
    results: Vector[String] = Vector.empty,
    durationNanos: Long = 0L,
    note: Option[String] = None
)

final case class TraceQuery(
    computerId: Option[String] = None,
    kinds: Set[String] = Set.empty,
    nameContains: Option[String] = None,
    afterSequence: Long = 0L,
    limit: Int = 200
)

final case class TracePage(events: Vector[TraceEvent], droppedCount: Long, latestSequence: Long)

final case class CallCount(name: String, count: Long, deferred: Long, totalNanos: Long)

/** How close a computer runs to OpenComputers' limits since the session began or it was reset. */
final case class MachineMetrics(
    computerId: String,
    memoryUsedBytes: Option[Long],
    memoryTotalBytes: Option[Long],
    memoryPeakBytes: Long,
    slices: Long,
    cpuNanos: Long,
    longestSliceNanos: Long,
    sliceTimeoutNanos: Long,
    callBudgetExhausted: Long,
    peakCallBudgetUsed: Double,
    signalsQueued: Long,
    signalsTaken: Long,
    signalQueueDepth: Int,
    signalQueuePeak: Int,
    signalQueueLimit: Int,
    longestSignalWaitTicks: Long,
    calls: Vector[CallCount]
)

final case class MetricsSample(
    tick: Long,
    computerId: String,
    memoryUsedBytes: Long,
    sliceNanos: Long,
    signalQueueDepth: Int,
    calls: Long
)

final case class ProfileEntry(stack: Vector[String], samples: Long)

final case class ProfileReport(
    computerId: Option[String],
    samples: Long,
    ticks: Long,
    hottest: Vector[(String, Long)],
    folded: Vector[ProfileEntry]
)

sealed trait ExecutionMode extends Product with Serializable { def name: String }
object ExecutionMode {
  case object Threaded extends ExecutionMode { override val name = "threaded" }

  /** Slices run on the tick thread in a fixed order; `serverTps` models server lag as the number of
    * slice rounds a computer gets per tick (more when the server runs slower).
    */
  final case class Deterministic(serverTps: Int) extends ExecutionMode {
    override val name = "deterministic"
  }
}

final case class DebugBreakpoint(file: String, line: Int)

final case class DebugFrame(
    index: Int,
    source: String,
    line: Int,
    function: String,
    hostPath: Option[String]
)

final case class DebugVariable(
    name: String,
    value: String,
    kind: String,
    reference: Option[String]
)

final case class DebugState(
    computerId: Option[String],
    paused: Boolean,
    reason: Option[String],
    pauseSequence: Long,
    breakpoints: Vector[DebugBreakpoint]
)

/** `limitReached`: the recording stopped growing at its bound; it replays up to there. */
final case class RecordingStatus(
    name: String,
    active: Boolean,
    startTick: Long,
    ticks: Long,
    inputs: Int,
    limitReached: Boolean
)

/** Replays a recording from its save state. `until` stops at the first tick it holds. */
final case class ReplayRequest(
    name: SnapshotName,
    until: Option[StopCondition],
    maxWallTime: scala.concurrent.duration.FiniteDuration,
    cancellation: RunCancellation = RunCancellation.create()
)

final case class ReplayResult(
    name: String,
    replayedTicks: Long,
    recordedTicks: Long,
    divergedAtTick: Option[Long],
    divergence: Option[String],
    stoppedAtTick: Long
)

/** Runs a save state again and again under different server lag. Each run gets seed `seed + n`; a
  * run passes when `condition` holds within `maxTicks`.
  */
final case class SweepRequest(
    snapshot: SnapshotName,
    condition: StopCondition,
    runs: Int,
    maxTicks: Int,
    minServerTps: Int,
    maxServerTps: Int,
    seed: Long,
    maxWallTime: scala.concurrent.duration.FiniteDuration,
    cancellation: RunCancellation = RunCancellation.create()
)

final case class SweepRun(
    seed: Long,
    passed: Boolean,
    ticks: Long,
    minServerTps: Int,
    maxServerTps: Int
)

final case class SweepResult(runs: Vector[SweepRun], failingSeeds: Vector[Long])

/** One difference between two save states. `path` names the device the way the project does
  * (`computer main/slot 5 HDDManaged/...`, `screen main/text line 3`); `change` is `added`,
  * `removed` or `changed`.
  */
final case class SnapshotChange(
    path: String,
    change: String,
    before: Option[String],
    after: Option[String]
)

/** `changes` holds at most the first 2000 of `totalChanges`, in path order. */
final case class SnapshotComparison(
    from: String,
    to: String,
    fromTick: Long,
    toTick: Long,
    changes: Vector[SnapshotChange],
    totalChanges: Int,
    notes: Vector[String]
)
