package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

import scala.collection.mutable.ArrayDeque
import scala.concurrent.duration._
import scala.util.control.NonFatal

import totoro.ocelot.brain.entity.machine.MachineInternals
import totoro.ocelot.brain.entity.{FloppyDiskDrive, Redstone, Screen}
import totoro.ocelot.brain.entity.traits.{Computer, Entity, Inventory}
import totoro.ocelot.brain.util.Direction
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError._
import ocelot.harness.core.artifact.{
  ArtifactDescription,
  ArtifactStore,
  DiagnosticBundleWriter,
  ScreenArtifactRequest,
  ScreenArtifactWriter
}
import ocelot.harness.core.project.{ComputerId, ScreenId, ValidatedProject}
import ocelot.harness.core.workspace._

private[runtime] final class BrainSession(
    val projectRoot: Path,
    initialWorkspace: Workspace,
    private val project: Option[ValidatedProject],
    initialConstructed: Option[ConstructedWorkspace],
    private val workspaceSource: Option[WorkspaceSourceAdapter],
    brainVersion: String,
    sessionClosed: BrainSession => Unit,
    scheduler: Option[MachineScheduler] = None
) extends HarnessSession {
  private val closed = new AtomicBoolean(false)
  private val lifecycleLock = new AnyRef
  private val lane = new SessionCommandLane(
    project.map(_.id.value).getOrElse("lifecycle-test")
  )
  private var workspace = initialWorkspace
  private var constructed = initialConstructed
  private var screenCapture = new ScreenCapture(workspace)
  private val artifactStore = project.map(value =>
    new ArtifactStore(
      value.paths.artifacts,
      16L * 1024L * 1024L,
      value.paths.projectRoot
    )
  )
  private val inputController = new InputController(() => !deterministic)
  private val eventBuffer =
    project.map(value => new SessionEventBuffer(value.runtime.limits.eventBufferSize))
  private var lastRunResult: Option[RunResult] = None
  // A snapshot restores the manifest's media, so one saved without it could not load.
  private var mediaEjected = false
  private val inspector = scheduler.map { value =>
    new SessionInspector(
      value,
      BrainSession.TraceCapacity,
      new SessionInspector.ClockControl {
        override def pause(): Boolean = {
          val wasRunning = simulationClock.status().state == SimulationClockState.Running
          if (wasRunning) simulationClock.pause()
          wasRunning
        }
        override def resume(): Unit = { simulationClock.resume(); () }
      }
    )
  }
  @volatile private var mode: ExecutionMode = ExecutionMode.Threaded
  @volatile private var slicesPerTick = 1
  private var recording: Option[ActiveRecording] = None
  private var replaying: Option[ReplayCursor] = None
  bindInspector()
  private val simulationClock = new SimulationClock(
    project.map(_.id.value).getOrElse("lifecycle-test"),
    project.map(_.runtime.tickRate).getOrElse(20),
    project.exists(value => value.schemaVersion == 2 && value.runtime.clockAutoStart),
    operation => lane.execute(operation()),
    () => tickWorkspace()
  )

  def isClosed: Boolean = closed.get()

  override def describe(): WorkspaceDescription = lifecycleLock.synchronized {
    if (closed.get()) throw new IllegalStateException("project session is closed")
    constructed
      .map(_.description)
      .getOrElse(throw new IllegalStateException("an empty lifecycle-test session has no topology"))
  }

  override def startMachine(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    machineCommand("start", id)
  }

  override def stopMachine(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    withManualTickBarrier(machineCommand("stop", id))
  }

  override def resetMachine(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    withManualTickBarrier(machineCommand("reset", id))
  }

  private def machineCommand(name: String, id: ComputerId): Either[HarnessError, MachineStatus] =
    machine(id).flatMap { computer =>
      noteInput(Some(id.value), RecordedInput.MachineCommand(name, id.value), Vector.empty)
      name match {
        case "start" =>
          computer.machine.start()
          Right(machineStatus(id, computer))
        case "stop" =>
          computer.machine.stop()
          settleStoppedMachine(computer).map(_ => machineStatus(id, computer))
        case _ =>
          computer.machine.stop()
          settleStoppedMachine(computer).map { _ =>
            computer.machine.start()
            machineStatus(id, computer)
          }
      }
    }

  override def machineStatus(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    machine(id).map(machineStatus(id, _))
  }

  override def run(request: RunRequest): Either[HarnessError, RunResult] = command {
    withManualTickBarrier {
      validateRunRequest(request).flatMap(runValidated).map { result =>
        lastRunResult = Some(result)
        result
      }
    }
  }

  override def startClock(tps: Option[Int]): Either[HarnessError, SimulationClockStatus] =
    command(simulationClock.start(tps))

  override def pauseClock(): Either[HarnessError, SimulationClockStatus] =
    command(Right(simulationClock.pause()))

  override def resumeClock(): Either[HarnessError, SimulationClockStatus] =
    command(Right(simulationClock.resume()))

  override def stepClock(count: Int): Either[HarnessError, SimulationClockStatus] =
    command(simulationClock.step(count))

  override def setClockRate(tps: Int): Either[HarnessError, SimulationClockStatus] =
    command(simulationClock.setRate(tps))

  override def clockStatus(): Either[HarnessError, SimulationClockStatus] =
    command(Right(simulationClock.status()))

  override def readScreen(id: ScreenId): Either[HarnessError, ScreenSnapshot] = command {
    screen(id).map(screenCapture.capture(id, _))
  }

  override def captureScreen(
      id: ScreenId,
      request: ScreenArtifactRequest
  ): Either[HarnessError, ArtifactDescription] = command {
    for {
      value <- screen(id)
      store <- artifactStore.toRight(SessionOperationFailed("artifact storage is unavailable"))
      artifact <- ScreenArtifactWriter.write(screenCapture.capture(id, value), request, store)
    } yield artifact
  }

  override def send(
      id: ScreenId,
      input: UserInput
  ): Either[HarnessError, InputResult] = command {
    withManualTickBarrier(sendInput(id, input))
  }

  private def sendInput(id: ScreenId, input: UserInput): Either[HarnessError, InputResult] =
    screen(id).flatMap { value =>
      constructed.flatMap(_.screenTiers.get(id)) match {
        case Some(screenTier) =>
          if (input != null)
            noteInput(None, RecordedInput.Screen(id.value, input), Vector(id.value, input.toString))
          inputController.send(
            value,
            constructed.flatMap(_.keyboards.get(id)),
            screenTier,
            input,
            () => advanceWorkspaceTick()
          )
        case None => Left(UnknownScreen(id.value))
      }
    }

  override def recentEvents(): Either[HarnessError, EventSnapshot] = command {
    Right(eventBuffer.map(_.snapshot()).getOrElse(EventSnapshot(Vector.empty, 0L)))
  }

  override def saveSnapshot(request: SnapshotRequest): Either[HarnessError, SnapshotDescription] =
    command {
      withManualTickBarrier(notPaused.flatMap(_ => saveSnapshotNow(request)))
    }

  private def saveSnapshotNow(request: SnapshotRequest): Either[HarnessError, SnapshotDescription] =
    for {
      _ <- Either.cond(
        !mediaEjected,
        (),
        SnapshotInvalid(
          "a snapshot can't be saved after media is ejected; load a snapshot first"
        )
      )
      value <- project.toRight(SnapshotInvalid("snapshot storage is unavailable"))
      topology <- constructed.toRight(SnapshotInvalid("snapshot topology is unavailable"))
      description <- SnapshotStore.save(value, topology, workspace, brainVersion, request)
    } yield description

  override def loadSnapshot(name: SnapshotName): Either[HarnessError, WorkspaceDescription] =
    command {
      withManualTickBarrier {
        for {
          _ <- notPaused
          _ <- notRecording("load a snapshot")
          description <- restoreSnapshot(name)
        } yield description
      }
    }

  private def restoreSnapshot(name: SnapshotName): Either[HarnessError, WorkspaceDescription] =
    project.toRight(SnapshotInvalid("snapshot storage is unavailable")).flatMap { value =>
      SnapshotStore.load(value, brainVersion, name).flatMap { loaded =>
        val candidate = new Workspace(value.paths.projectRoot)
        try {
          candidate.load(loaded.nbt)
          if (candidate.getIngameTime.toLong != loaded.description.captureTick) {
            disposeWorkspace(candidate)
            Left(SnapshotCorrupt("workspace tick does not match snapshot metadata"))
          } else {
            val candidateTopology = workspaceSource
              .toRight(SnapshotInvalid("snapshot workspace source is unavailable"))
              .map(_.restore(candidate, loaded.identity))
              .fold(error => throw new IllegalArgumentException(error.message), identity)
            val revisionBase =
              screenSnapshots().valuesIterator.map(_.revision).foldLeft(0L)(math.max)
            val previous = workspace
            // Slices kept for the old world's computers must not run.
            scheduler.foreach(_.discardPending())
            workspace = candidate
            constructed = Some(candidateTopology)
            screenCapture = new ScreenCapture(candidate, revisionBase)
            lastRunResult = None
            mediaEjected = false
            disposeWorkspace(previous)
            bindInspector()
            // The next fingerprint starts from the restored world.
            inspector.foreach(_.recorder.takeTickChecksum())
            Right(candidateTopology.description)
          }
        } catch {
          case NonFatal(error) =>
            disposeWorkspace(candidate)
            Left(SnapshotCorrupt(errorMessage(error)))
        }
      }
    }

  override def ejectMedia(driveId: String): Either[HarnessError, MediaEjectResult] = command {
    ejectNow(driveId)
  }

  private def ejectNow(driveId: String): Either[HarnessError, MediaEjectResult] =
    constructed.flatMap(value => Option(driveId).flatMap(value.deviceEntities.get)) match {
      case Some(drive: FloppyDiskDrive) =>
        noteInput(None, RecordedInput.Eject(driveId), Vector(driveId))
        val ejected = drive.eject()
        if (ejected) mediaEjected = true
        Right(MediaEjectResult(driveId, ejected))
      case _ => Left(UnknownDrive(String.valueOf(driveId)))
    }

  // OC's side numbers 0-5, which are also brain's Direction ids.
  private val RedstoneSides = Vector("bottom", "top", "back", "front", "right", "left")

  // What a block next to the computer puts into its redstone card, as a DHD or lever would. A
  // rise to the card's wake threshold starts a stopped computer, as in game.
  override def setRedstoneInput(
      id: ComputerId,
      side: String,
      value: Int
  ): Either[HarnessError, RedstoneInputResult] = command(redstoneNow(id, side, value))

  private def redstoneNow(
      id: ComputerId,
      side: String,
      value: Int
  ): Either[HarnessError, RedstoneInputResult] =
    for {
      computer <- machine(id)
      index <- redstoneSide(side).toRight(
        InvalidInput(s"unknown side: $side (${RedstoneSides.mkString(", ")} or 0-5)")
      )
      _ <- Either.cond(
        value >= 0 && value <= 15,
        (),
        InvalidInput(s"redstone input must be 0-15: $value")
      )
      card <- (computer match {
        case inventory: Inventory =>
          inventory.inventory.entities.collectFirst { case card: Redstone.Tier1 => card }
        case _ => None
      }).toRight(InvalidInput(s"${id.value} has no redstone card"))
    } yield {
      noteInput(
        Some(id.value),
        RecordedInput.Redstone(id.value, RedstoneSides(index), value),
        Vector(RedstoneSides(index), value.toString)
      )
      card.setRedstoneInput(Direction(index), value)
      RedstoneInputResult(id.value, RedstoneSides(index), value)
    }

  private def redstoneSide(side: String): Option[Int] =
    Option(side).flatMap { value =>
      val named = RedstoneSides.indexOf(value.toLowerCase)
      if (named >= 0) Some(named)
      else value.toIntOption.filter(n => n >= 0 && n < RedstoneSides.size)
    }

  override def diagnostics(request: DiagnosticRequest): Either[HarnessError, DiagnosticBundle] =
    command {
      for {
        value <- project.toRight(DiagnosticFailed("diagnostics are unavailable"))
        topology <- constructed.toRight(DiagnosticFailed("diagnostic topology is unavailable"))
        store <- artifactStore.toRight(DiagnosticFailed("artifact storage is unavailable"))
        bundle <- DiagnosticBundleWriter.collect(
          value,
          topology.description,
          brainVersion,
          eventBuffer.map(_.snapshot()).getOrElse(EventSnapshot(Vector.empty, 0L)),
          screenSnapshots(),
          lastRunResult,
          simulationClock.status(),
          inspector.map(value =>
            value.recorder.read(
              TraceQuery(
                afterSequence =
                  (value.recorder.latestSequence - BrainSession.DiagnosticTraceEvents) max 0L,
                limit = BrainSession.DiagnosticTraceEvents
              )
            ) -> value.metrics()
          ),
          request,
          store
        )
      } yield bundle
    }

  override def close(): Unit = {
    var closedNow = false
    var failure: Option[Throwable] = None
    // A computer paused in the debugger holds a slice, and in deterministic mode the lane too.
    inspector.foreach(value =>
      try value.close()
      catch { case NonFatal(_) => }
    )
    lifecycleLock.synchronized {
      if (closed.compareAndSet(false, true)) {
        closedNow = true
        try simulationClock.close()
        catch { case NonFatal(error) => failure = Some(error) }
        try {
          lane.execute {
            eventBuffer.foreach(_.close())
            disposeWorkspace(workspace)
          } match {
            case Left(error) if failure.isEmpty =>
              failure = Some(new IllegalStateException(error.message))
            case _ =>
          }
        } catch {
          case NonFatal(error) if failure.isEmpty => failure = Some(error)
          case NonFatal(_)                        =>
        } finally lane.close()
      }
    }
    if (closedNow) sessionClosed(this)
    failure.foreach(throw _)
  }

  private def disposeWorkspace(value: Workspace): Unit = {
    val topLevel = value.getEntitiesIter.toVector
    val allComputers = topLevel.flatMap(nestedComputers).distinct
    allComputers.foreach(computer =>
      try computer.machine.stop()
      catch { case NonFatal(_) => }
    )
    topLevel.reverse.foreach { entity =>
      try value.remove(entity)
      catch {
        case NonFatal(_) =>
      }
    }
    allComputers
      .filterNot(computer => topLevel.exists(_ eq computer))
      .foreach(computer =>
        try computer.dispose()
        catch { case NonFatal(_) => }
      )
  }

  private def nestedComputers(entity: Entity): Vector[Computer] = {
    val nested = entity match {
      case inventory: Inventory => inventory.inventory.entities.toVector.flatMap(nestedComputers)
      case _                    => Vector.empty
    }
    entity match {
      case computer: Computer => computer +: nested
      case _                  => nested
    }
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private def command[A](operation: => Either[HarnessError, A]): Either[HarnessError, A] =
    // In deterministic mode a paused computer holds the session lane; fail rather than queue.
    if (deterministic && inspector.exists(_.isPaused))
      Left(
        DebuggerPaused(
          "a computer is paused in the debugger and holds the simulation; continue it first"
        )
      )
    else
      lifecycleLock.synchronized {
        if (closed.get()) Left(SessionClosed)
        else lane.execute(operation).flatMap(identity)
      }

  private def notPaused: Either[HarnessError, Unit] =
    if (inspector.exists(_.isPaused))
      Left(DebuggerPaused("a computer is paused in the debugger; continue it first"))
    else Right(())

  private def notRecording(action: String): Either[HarnessError, Unit] =
    recording match {
      case Some(value) =>
        Left(RecordingInvalid(s"stop recording ${value.name.value} before you $action"))
      case None => Right(())
    }

  private def deterministic: Boolean = scheduler.exists(_.isDeterministic)

  /** Every tick goes through here: the clock's, a run's and an input's. */
  private def tickWorkspace(): Unit = {
    inspector.foreach(_.beforeTick())
    workspace.update()
    scheduler.filter(_.isDeterministic).foreach(_.drain(slicesPerTick, sliceOrder))
    inspector.foreach { value =>
      value.afterTick(workspace.getIngameTime.toLong)
      // Taken every tick so each fingerprint covers exactly one tick.
      val fingerprint = value.tickFingerprint()
      recording.foreach(_.tick(fingerprint))
      replaying.foreach(_.tick(fingerprint))
    }
  }

  private def sliceOrder(task: Runnable): String =
    inspector.map(_.sliceOrder(task)).getOrElse(task.toString)

  private def noteInput(
      computerId: Option[String],
      input: RecordedInput,
      arguments: Vector[String]
  ): Unit = {
    inspector.foreach(_.recordInput(computerId, inputName(input), arguments))
    recording.foreach(_.input(workspace.getIngameTime.toLong, input))
  }

  private def inputName(input: RecordedInput): String = input match {
    case RecordedInput.Screen(_, value)           => value.productPrefix
    case RecordedInput.Redstone(_, _, _)          => "redstone"
    case RecordedInput.MachineCommand(command, _) => command
    case RecordedInput.Eject(_)                   => "eject"
    case RecordedInput.ServerTps(_)               => "serverTps"
  }

  private def bindInspector(): Unit =
    inspector.foreach { value =>
      val topology = constructed
      value.bind(
        topology
          .map(_.computers.map { case (id, computer) => id.value -> computer.machine })
          .getOrElse(Map.empty),
        topology
          .map(
            _.description.computers
              .map(computer => computer.id.value -> computer.disks.map(_.source))
              .toMap
          )
          .getOrElse(Map.empty)
      )
    }

  private def withManualTickBarrier[A](operation: => A): A = {
    simulationClock.beginManualTicks()
    try operation
    finally simulationClock.endManualTicks()
  }

  private def advanceWorkspaceTick(): Unit =
    simulationClock.recordManualTick(tickWorkspace())

  private def machine(id: ComputerId): Either[HarnessError, Computer] =
    if (id == null) Left(UnknownComputer("<null>"))
    else
      constructed.flatMap(_.computers.get(id)) match {
        case Some(value) => Right(value)
        case None        => Left(UnknownComputer(id.value))
      }

  private def screen(id: ScreenId): Either[HarnessError, Screen] =
    if (id == null) Left(UnknownScreen("<null>"))
    else
      constructed.flatMap(_.screens.get(id)) match {
        case Some(value) => Right(value)
        case None        => Left(UnknownScreen(id.value))
      }

  private def machineStatus(id: ComputerId, computer: Computer): MachineStatus = {
    val lastError = Option(computer.machine.lastError)
    val state =
      if (computer.machine.isPaused) MachineState.Paused
      else if (computer.machine.isRunning) MachineState.Running
      else if (lastError.nonEmpty) MachineState.Crashed
      else MachineState.Stopped
    // Zero total means the machine has not finished an execution slice since it was created.
    val (free, total) = computer.machine.latestMemoryUsage
    val memory = Option.when(total > 0)(MachineMemory(free.toLong, total.toLong))
    MachineStatus(id, state, lastError, memory)
  }

  private def settleStoppedMachine(computer: Computer): Either[HarnessError, Unit] = {
    val deadline = System.nanoTime() + 2.seconds.toNanos
    while (computer.machine.isExecuting && System.nanoTime() < deadline) {
      LockSupport.parkNanos(100000L)
    }
    if (computer.machine.isExecuting) {
      Left(SessionOperationFailed("machine worker did not stop within 2 seconds"))
    } else {
      advanceWorkspaceTick()
      Right(())
    }
  }

  private def validateRunRequest(request: RunRequest): Either[HarnessError, RunRequest] = {
    if (request == null) Left(InvalidRunRequest("run request is required"))
    else if (request.condition == null) Left(InvalidRunRequest("stop condition is required"))
    else if (request.maxTicks <= 0) Left(InvalidRunRequest("max ticks must be positive"))
    else if (request.maxWallTime == null || request.maxWallTime <= Duration.Zero)
      Left(InvalidRunRequest("max wall time must be positive"))
    else if (request.cancellation == null)
      Left(InvalidRunRequest("cancellation token is required"))
    else if (request.pace == null) Left(InvalidRunRequest("tick pace is required"))
    else
      request.pace match {
        case TickPace.FixedDelay(delay) if delay == null || delay <= Duration.Zero =>
          Left(InvalidRunRequest("fixed tick delay must be positive"))
        case _ => validateCondition(request.condition).map(_ => request)
      }
  }

  private def validateCondition(condition: StopCondition): Either[HarnessError, Unit] =
    condition match {
      case ScreenContains(_, null) => Left(InvalidRunRequest("screen text is required"))
      case ScreenRevisionAfter(_, revision) if revision < 0L =>
        Left(InvalidRunRequest("screen revision must not be negative"))
      case MachineReaches(_, null) => Left(InvalidRunRequest("machine state is required"))
      case EventOccurs(kind) if kind == null || kind.isEmpty =>
        Left(InvalidRunRequest("event kind is required"))
      case _ => Right(())
    }

  private def runValidated(request: RunRequest): Either[HarnessError, RunResult] = {
    val startedAt = System.nanoTime()
    val wallBudgetNanos = request.maxWallTime.toNanos
    val timeline = ArrayDeque.empty[RunObservation]
    val eventSequenceAtStart = eventBuffer.map(_.latestSequence).getOrElse(0L)
    var timelineDroppedCount = 0L
    var ticks = 0
    var result: Option[Either[HarnessError, RunStopReason]] = None

    def recordObservation(): Unit = {
      val screens = screenSnapshots()
      if (timeline.size == 256) {
        timeline.removeHead()
        timelineDroppedCount += 1L
      }
      timeline.append(
        RunObservation(
          ticks,
          (System.nanoTime() - startedAt).nanos,
          machineStates(),
          screens.view.mapValues(_.revision).toMap
        )
      )
    }

    recordObservation()
    evaluate(request.condition, eventSequenceAtStart) match {
      case Left(error)  => result = Some(Left(error))
      case Right(true)  => result = Some(Right(RunStopReason.ConditionSatisfied))
      case Right(false) =>
    }

    while (result.isEmpty) {
      if (request.cancellation.isCancelled) {
        result = Some(Right(RunStopReason.Cancelled))
      } else if (ticks >= request.maxTicks) {
        result = Some(Right(RunStopReason.ConditionTimedOut("max_ticks")))
      } else if (System.nanoTime() - startedAt >= wallBudgetNanos) {
        result = Some(Right(RunStopReason.ConditionTimedOut("max_wall_time")))
      } else {
        advanceWorkspaceTick()
        ticks += 1
        pace(request.pace, request.cancellation, startedAt, request.maxWallTime)
        recordObservation()
        evaluate(request.condition, eventSequenceAtStart) match {
          case Left(error)  => result = Some(Left(error))
          case Right(true)  => result = Some(Right(RunStopReason.ConditionSatisfied))
          case Right(false) =>
        }
      }
    }

    result.get.map { stopReason =>
      val elapsed = (System.nanoTime() - startedAt).nanos
      val screens = screenSnapshots()
      RunResult(
        stopReason,
        ticks,
        elapsed,
        machineStates(),
        screens.view.mapValues(_.revision).toMap,
        eventBuffer.map(_.snapshot()).getOrElse(EventSnapshot(Vector.empty, 0L)),
        screens,
        timeline.toVector,
        timelineDroppedCount
      )
    }
  }

  private def machineStates(): Vector[MachineStatus] =
    constructed.toVector.flatMap(_.computers.toVector).sortBy(_._1.value).map {
      case (id, computer) => machineStatus(id, computer)
    }

  private def screenSnapshots(): Map[ScreenId, ScreenSnapshot] =
    constructed.toVector
      .flatMap(_.screens.toVector)
      .map { case (id, value) =>
        id -> screenCapture.capture(id, value)
      }
      .toMap

  private def evaluate(
      condition: StopCondition,
      eventSequenceAtStart: Long
  ): Either[HarnessError, Boolean] = condition match {
    case ScreenContains(id, text) =>
      screen(id).map(value => screenCapture.capture(id, value).text.contains(text))
    case ScreenRevisionAfter(id, revision) =>
      screen(id).map(value => screenCapture.capture(id, value).revision > revision)
    case MachineReaches(id, state) =>
      machine(id).map(value => machineStatus(id, value).state == state)
    case EventOccurs(kind) =>
      Right(
        eventBuffer.exists(
          _.snapshot().events.exists(event =>
            event.sequence > eventSequenceAtStart && event.kind == kind
          )
        )
      )
  }

  private def pace(
      value: TickPace,
      cancellation: RunCancellation,
      startedAt: Long,
      wallBudget: FiniteDuration
  ): Unit = {
    val paceStartedAt = System.nanoTime()
    def waiting(delay: FiniteDuration): Boolean =
      !cancellation.isCancelled &&
        System.nanoTime() - paceStartedAt < delay.toNanos &&
        System.nanoTime() - startedAt < wallBudget.toNanos
    value match {
      // A game tick lasts 50 ms, time for the slices it gives the computers (one per queued
      // signal, executionDelay apart). An accelerated tick ends once no computer has a slice
      // due, or after those 50 ms, so a computer gets the work done in a tick that it would in
      // the game and idle computers still run ahead.
      // Deterministic slices already ran inside the tick.
      case TickPace.Accelerated if deterministic =>
      case TickPace.Accelerated =>
        val machines = constructed.toVector.flatMap(_.computers.values.map(_.machine))
        while (waiting(GameTick) && machines.exists(MachineInternals.sliceDue))
          LockSupport.parkNanos(200000L)
      case TickPace.FixedDelay(delay) =>
        while (waiting(delay)) {
          val remaining = delay.toNanos - (System.nanoTime() - paceStartedAt)
          LockSupport.parkNanos(math.min(remaining, 1.millis.toNanos))
        }
    }
  }

  // ------------------------------------------------------------------------------------------ //
  // Inspection: flight recorder, meters, profiler. Reads skip the lane so they work while a
  // computer is paused in the debugger or a long run holds the lane.

  private def inspecting[A](operation: SessionInspector => A): Either[HarnessError, A] =
    if (closed.get()) Left(SessionClosed)
    else
      inspector
        .toRight(SessionOperationFailed("inspection is unavailable in this session"))
        .map(operation)

  override def readTrace(query: TraceQuery): Either[HarnessError, TracePage] =
    if (query == null || query.limit < 1 || query.limit > BrainSession.MaxTracePage)
      Left(InvalidInput(s"trace limit must be 1 to ${BrainSession.MaxTracePage}"))
    else inspecting(_.recorder.read(query))

  override def machineMetrics(): Either[HarnessError, Vector[MachineMetrics]] =
    inspecting(_.metrics())

  override def metricsHistory(
      computerId: Option[ComputerId],
      afterTick: Long
  ): Either[HarnessError, Vector[MetricsSample]] =
    inspecting(_.history(computerId.map(_.value), afterTick))

  override def resetMetrics(): Either[HarnessError, Unit] = inspecting(_.resetMeters())

  override def startProfile(computerId: Option[ComputerId]): Either[HarnessError, Unit] =
    computerId match {
      case Some(id) if constructed.forall(!_.computers.contains(id)) =>
        Left(UnknownComputer(id.value))
      case _ => inspecting(_.startProfile(computerId.map(_.value)))
    }

  override def stopProfile(
      relativePath: Option[String]
  ): Either[HarnessError, (ProfileReport, Vector[ArtifactDescription])] =
    inspecting(_.stopProfile()).flatMap {
      case None => Left(InvalidInput("no profile is running"))
      case Some(report) =>
        relativePath match {
          case None => Right(report -> Vector.empty)
          case Some(path) =>
            for {
              store <- artifactStore.toRight(
                SessionOperationFailed("artifact storage is unavailable")
              )
              folded <- store.writeBytes(
                s"$path.folded.txt",
                FlameGraph.folded(report).getBytes(StandardCharsets.UTF_8),
                "text/plain"
              )
              svg <- store.writeBytes(
                s"$path.svg",
                FlameGraph.svg(report).getBytes(StandardCharsets.UTF_8),
                "image/svg+xml"
              )
            } yield report -> Vector(folded, svg)
        }
    }

  override def diffSnapshots(
      from: SnapshotName,
      to: SnapshotName
  ): Either[HarnessError, SnapshotComparison] =
    command {
      for {
        value <- project.toRight(SnapshotInvalid("snapshot storage is unavailable"))
        before <- SnapshotStore.load(value, brainVersion, from)
        after <- SnapshotStore.load(value, brainVersion, to)
        root = value.paths.snapshots.toAbsolutePath.normalize()
        comparison <- SnapshotDiff.compare(
          from,
          before,
          root.resolve(from.value),
          to,
          after,
          root.resolve(to.value)
        )
      } yield comparison
    }

  override def startJvmRecording(): Either[HarnessError, Unit] =
    if (closed.get()) Left(SessionClosed) else JvmFlightRecording.start()

  override def stopJvmRecording(relativePath: String): Either[HarnessError, ArtifactDescription] =
    for {
      store <- artifactStore.toRight(SessionOperationFailed("artifact storage is unavailable"))
      bytes <- JvmFlightRecording.stop()
      artifact <- store.writeBytes(relativePath, bytes, "application/octet-stream")
    } yield artifact

  // ------------------------------------------------------------------------------------------ //
  // Deterministic execution, recordings, replay and lag sweeps.

  override def executionMode(): Either[HarnessError, ExecutionMode] = command(Right(mode))

  override def setExecutionMode(mode: ExecutionMode): Either[HarnessError, ExecutionMode] =
    command {
      withManualTickBarrier {
        for {
          _ <- notPaused
          _ <- (mode, recording) match {
            case (ExecutionMode.Threaded, Some(value)) =>
              Left(
                RecordingInvalid(
                  s"stop recording ${value.name.value} before leaving deterministic mode"
                )
              )
            case _ => Right(())
          }
          _ = noteServerTps(mode)
          applied <- applyMode(mode)
        } yield applied
      }
    }

  /** A change of server speed during a recording is an input the replay repeats. */
  private def noteServerTps(requested: ExecutionMode): Unit = requested match {
    case ExecutionMode.Deterministic(tps) if mode != requested && tps >= 1 && tps <= 20 =>
      noteInput(None, RecordedInput.ServerTps(tps), Vector(tps.toString))
    case _ =>
  }

  private def applyMode(requested: ExecutionMode): Either[HarnessError, ExecutionMode] =
    for {
      value <- scheduler.toRight(
        SessionOperationFailed("execution modes are unavailable in this session")
      )
      _ <- requested match {
        case ExecutionMode.Deterministic(tps) if tps < 1 || tps > 20 =>
          Left(InvalidInput(s"server TPS must be 1 to 20: $tps"))
        case _ => Right(())
      }
    } yield {
      requested match {
        case ExecutionMode.Threaded =>
          value.setDeterministic(false)
          slicesPerTick = 1
        case ExecutionMode.Deterministic(tps) =>
          value.setDeterministic(true)
          slicesPerTick = BrainSession.slicesPerTick(tps)
      }
      mode = requested
      inspector.foreach(_.executionModeChanged())
      requested
    }

  override def startRecording(name: SnapshotName): Either[HarnessError, RecordingStatus] =
    command {
      withManualTickBarrier {
        for {
          _ <- notPaused
          _ <- notRecording("start another")
          tps <- mode match {
            case ExecutionMode.Deterministic(value) => Right(value)
            case ExecutionMode.Threaded =>
              Left(RecordingInvalid("recordings need deterministic mode; set it first"))
          }
          _ <- saveSnapshotNow(SnapshotRequest(name))
          // Run on from the save state itself, as a replay will.
          _ <- restoreSnapshot(name)
        } yield {
          val value = new ActiveRecording(name, workspace.getIngameTime.toLong, tps)
          recording = Some(value)
          value.status(active = true)
        }
      }
    }

  override def stopRecording(): Either[HarnessError, RecordingStatus] = command {
    recording match {
      case None => Left(RecordingInvalid("nothing is being recorded"))
      case Some(value) =>
        recording = None
        project
          .toRight(RecordingInvalid("recording storage is unavailable"))
          .flatMap(RecordingFile.write(_, value.data))
          .map(_ => value.status(active = false))
    }
  }

  override def replay(request: ReplayRequest): Either[HarnessError, ReplayResult] = command {
    withManualTickBarrier {
      for {
        _ <- Option(request).toRight(InvalidInput("replay request is required"))
        _ <- request.until.fold[Either[HarnessError, Unit]](Right(()))(validateCondition)
        _ <- notPaused
        _ <- notRecording("replay")
        value <- project.toRight(RecordingInvalid("recording storage is unavailable"))
        data <- RecordingFile.read(value, request.name)
        _ <- restoreSnapshot(request.name)
        _ <- applyMode(ExecutionMode.Deterministic(data.serverTps))
        result <- replayLoaded(data, request)
      } yield result
    }
  }

  private def replayLoaded(
      data: RecordingData,
      request: ReplayRequest
  ): Either[HarnessError, ReplayResult] = {
    val cursor = new ReplayCursor(data)
    val pending = ArrayDeque.from(data.inputs)
    val startedAt = System.nanoTime()
    val events = eventBuffer.map(_.latestSequence).getOrElse(0L)
    var failure: Option[HarnessError] = None
    var reached = false
    def holds(): Boolean = request.until.exists { condition =>
      evaluate(condition, events) match {
        case Left(error)  => failure = Some(error); false
        case Right(value) => value
      }
    }
    replaying = Some(cursor)
    try {
      reached = holds()
      while (
        !reached && failure.isEmpty && cursor.divergence.isEmpty && !cursor.done &&
        !request.cancellation.isCancelled && System
          .nanoTime() - startedAt < request.maxWallTime.toNanos
      ) {
        val now = workspace.getIngameTime.toLong
        if (pending.nonEmpty && pending.head._1 <= now) {
          val (_, input) = pending.removeHead()
          applyRecorded(input).left.foreach(error => failure = Some(error))
        } else tickWorkspace()
        reached = holds()
      }
    } finally replaying = None
    failure.toLeft(
      ReplayResult(
        data.name.value,
        cursor.replayed,
        data.ticks,
        cursor.divergence.map(_._1),
        cursor.divergence.map(_._2),
        workspace.getIngameTime.toLong
      )
    )
  }

  private def applyRecorded(input: RecordedInput): Either[HarnessError, Unit] = input match {
    case RecordedInput.Screen(id, value) =>
      ScreenId.parse(id).left.map(InvalidInput(_)).flatMap(sendInput(_, value)).map(_ => ())
    case RecordedInput.Redstone(id, side, value) =>
      ComputerId
        .parse(id)
        .left
        .map(InvalidInput(_))
        .flatMap(redstoneNow(_, side, value))
        .map(_ => ())
    case RecordedInput.MachineCommand(name, id) =>
      ComputerId.parse(id).left.map(InvalidInput(_)).flatMap(machineCommand(name, _)).map(_ => ())
    case RecordedInput.Eject(id) => ejectNow(id).map(_ => ())
    case RecordedInput.ServerTps(tps) =>
      val requested = ExecutionMode.Deterministic(tps)
      noteServerTps(requested)
      applyMode(requested).map(_ => ())
  }

  override def sweep(request: SweepRequest): Either[HarnessError, SweepResult] = command {
    withManualTickBarrier {
      for {
        _ <- validateSweep(request)
        _ <- notPaused
        _ <- notRecording("sweep")
        runs <- sweepRuns(request)
      } yield SweepResult(runs, runs.filterNot(_.passed).map(_.seed))
    }
  }

  private def validateSweep(request: SweepRequest): Either[HarnessError, Unit] =
    if (request == null) Left(InvalidInput("sweep request is required"))
    else if (request.runs < 1 || request.runs > BrainSession.MaxSweepRuns)
      Left(InvalidInput(s"sweep runs must be 1 to ${BrainSession.MaxSweepRuns}"))
    else if (request.maxTicks < 1) Left(InvalidInput("sweep max ticks must be positive"))
    else if (
      request.minServerTps < 1 || request.maxServerTps > 20 ||
      request.minServerTps > request.maxServerTps
    ) Left(InvalidInput("sweep server TPS must satisfy 1 <= min <= max <= 20"))
    else if (request.maxWallTime == null || request.maxWallTime <= Duration.Zero)
      Left(InvalidInput("sweep max wall time must be positive"))
    else if (request.condition == null) Left(InvalidInput("sweep condition is required"))
    else validateCondition(request.condition)

  private def sweepRuns(request: SweepRequest): Either[HarnessError, Vector[SweepRun]] = {
    val startedAt = System.nanoTime()
    val runs = Vector.newBuilder[SweepRun]
    var failure: Option[HarnessError] = None
    var index = 0
    def timeLeft = System.nanoTime() - startedAt < request.maxWallTime.toNanos
    while (
      failure.isEmpty && index < request.runs && timeLeft && !request.cancellation.isCancelled
    ) {
      val seed = request.seed + index
      val lag = new LagPattern(seed, request.minServerTps, request.maxServerTps)
      restoreSnapshot(request.snapshot).flatMap(_ =>
        applyMode(ExecutionMode.Deterministic(lag.tps))
      ) match {
        case Left(error) => failure = Some(error)
        case Right(_) =>
          val events = eventBuffer.map(_.latestSequence).getOrElse(0L)
          var ticks = 0L
          var lowest = lag.tps
          var highest = lag.tps
          var passed = false
          var stop = false
          while (!stop) {
            evaluate(request.condition, events) match {
              case Left(error) => failure = Some(error); stop = true
              case Right(true) => passed = true; stop = true
              case Right(false) =>
                if (ticks >= request.maxTicks || !timeLeft || request.cancellation.isCancelled)
                  stop = true
                else {
                  val tps = lag.next()
                  if (tps != slicesTps)
                    applyMode(ExecutionMode.Deterministic(tps)).left.foreach(error =>
                      failure = Some(error)
                    )
                  lowest = math.min(lowest, tps)
                  highest = math.max(highest, tps)
                  tickWorkspace()
                  ticks += 1L
                }
            }
          }
          if (failure.isEmpty) runs += SweepRun(seed, passed, ticks, lowest, highest)
      }
      index += 1
    }
    failure.toLeft(runs.result())
  }

  private def slicesTps: Int = mode match {
    case ExecutionMode.Deterministic(value) => value
    case ExecutionMode.Threaded             => 20
  }

  // ------------------------------------------------------------------------------------------ //
  // Debugger. These skip the lane: a paused computer may be holding it.

  override def setBreakpoints(
      file: Path,
      lines: Vector[Int]
  ): Either[HarnessError, Vector[DebugBreakpoint]] =
    if (file == null || lines == null || lines.exists(_ < 1))
      Left(InvalidInput("breakpoints need a file and line numbers from 1"))
    else inspecting(_.setBreakpoints(file, lines))

  override def debugPause(): Either[HarnessError, DebugState] =
    inspecting { value =>
      value.requestPause()
      value.debugState()
    }

  override def debugState(
      afterSequence: Option[Long],
      waitMillis: Long
  ): Either[HarnessError, DebugState] =
    if (waitMillis < 0L || waitMillis > BrainSession.MaxDebugWaitMillis)
      Left(InvalidInput(s"debug wait must be 0 to ${BrainSession.MaxDebugWaitMillis} ms"))
    else
      inspecting { value =>
        afterSequence match {
          case Some(sequence) if waitMillis > 0L => value.awaitPause(sequence, waitMillis)
          case _                                 => value.debugState()
        }
      }

  override def debugFrames(): Either[HarnessError, Vector[DebugFrame]] =
    inspecting(_.frames()).flatMap(identity)

  override def debugVariables(reference: String): Either[HarnessError, Vector[DebugVariable]] =
    Option(reference)
      .toRight(InvalidInput("variable reference is required"))
      .flatMap(value => inspecting(_.variables(value)).flatMap(identity))

  override def debugResume(mode: String): Either[HarnessError, DebugState] =
    inspecting(_.resume(mode)).flatMap(identity)

  private val GameTick = 50.millis
}

private[runtime] object BrainSession {

  /** The most recent flight recorder events a diagnostics bundle carries. */
  val DiagnosticTraceEvents = 2000

  val TraceCapacity = 65536
  val MaxTracePage = 5000
  val MaxSweepRuns = 100
  val MaxDebugWaitMillis = 60000L

  /** Slice rounds a computer gets per tick: a game tick lasts 1000/TPS ms and a computer can run a
    * slice every executionDelay (12 ms), so a lagging server gives each tick more.
    */
  def slicesPerTick(serverTps: Int): Int =
    math.max(
      1,
      ((1000.0 / serverTps) / math.max(1, totoro.ocelot.brain.Settings.get.executionDelay)).toInt
    )
}
