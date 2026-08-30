package ocelot.harness.core.runtime

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

import scala.collection.mutable.ArrayDeque
import scala.concurrent.duration._
import scala.util.control.NonFatal

import totoro.ocelot.brain.entity.Screen
import totoro.ocelot.brain.entity.traits.{Computer, Entity, Inventory}
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
    sessionClosed: BrainSession => Unit
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
  private val inputController = new InputController
  private val eventBuffer =
    project.map(value => new SessionEventBuffer(value.runtime.limits.eventBufferSize))
  private var lastRunResult: Option[RunResult] = None
  private val simulationClock = new SimulationClock(
    project.map(_.id.value).getOrElse("lifecycle-test"),
    project.map(_.runtime.tickRate).getOrElse(20),
    project.exists(value => value.schemaVersion == 2 && value.runtime.clockAutoStart),
    operation => lane.execute(operation()),
    () => workspace.update()
  )

  def isClosed: Boolean = closed.get()

  override def describe(): WorkspaceDescription = lifecycleLock.synchronized {
    if (closed.get()) throw new IllegalStateException("project session is closed")
    constructed
      .map(_.description)
      .getOrElse(throw new IllegalStateException("an empty lifecycle-test session has no topology"))
  }

  override def startMachine(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    machine(id).map { computer =>
      computer.machine.start()
      machineStatus(id, computer)
    }
  }

  override def stopMachine(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    withManualTickBarrier {
      machine(id).flatMap { computer =>
        computer.machine.stop()
        settleStoppedMachine(computer).map(_ => machineStatus(id, computer))
      }
    }
  }

  override def resetMachine(id: ComputerId): Either[HarnessError, MachineStatus] = command {
    withManualTickBarrier {
      machine(id).flatMap { computer =>
        computer.machine.stop()
        settleStoppedMachine(computer).map { _ =>
          computer.machine.start()
          machineStatus(id, computer)
        }
      }
    }
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
    withManualTickBarrier {
      screen(id).flatMap { value =>
        constructed.flatMap(_.screenTiers.get(id)) match {
          case Some(screenTier) =>
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
    }
  }

  override def recentEvents(): Either[HarnessError, EventSnapshot] = command {
    Right(eventBuffer.map(_.snapshot()).getOrElse(EventSnapshot(Vector.empty, 0L)))
  }

  override def saveSnapshot(request: SnapshotRequest): Either[HarnessError, SnapshotDescription] =
    command {
      withManualTickBarrier {
        for {
          value <- project.toRight(SnapshotInvalid("snapshot storage is unavailable"))
          topology <- constructed.toRight(SnapshotInvalid("snapshot topology is unavailable"))
          description <- SnapshotStore.save(value, topology, workspace, brainVersion, request)
        } yield description
      }
    }

  override def loadSnapshot(name: SnapshotName): Either[HarnessError, WorkspaceDescription] =
    command {
      withManualTickBarrier {
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
                workspace = candidate
                constructed = Some(candidateTopology)
                screenCapture = new ScreenCapture(candidate, revisionBase)
                lastRunResult = None
                disposeWorkspace(previous)
                Right(candidateTopology.description)
              }
            } catch {
              case NonFatal(error) =>
                disposeWorkspace(candidate)
                Left(SnapshotCorrupt(errorMessage(error)))
            }
          }
        }
      }
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
          request,
          store
        )
      } yield bundle
    }

  override def close(): Unit = {
    var closedNow = false
    var failure: Option[Throwable] = None
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
    lifecycleLock.synchronized {
      if (closed.get()) Left(SessionClosed)
      else lane.execute(operation).flatMap(identity)
    }

  private def withManualTickBarrier[A](operation: => A): A = {
    simulationClock.beginManualTicks()
    try operation
    finally simulationClock.endManualTicks()
  }

  private def advanceWorkspaceTick(): Unit =
    simulationClock.recordManualTick(workspace.update())

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
    MachineStatus(id, state, lastError)
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
    val requestedDelay = value match {
      case TickPace.Accelerated       => 1.millis
      case TickPace.FixedDelay(delay) => delay
    }
    val paceStartedAt = System.nanoTime()
    while (
      !cancellation.isCancelled &&
      System.nanoTime() - paceStartedAt < requestedDelay.toNanos &&
      System.nanoTime() - startedAt < wallBudget.toNanos
    ) {
      val remaining = requestedDelay.toNanos - (System.nanoTime() - paceStartedAt)
      LockSupport.parkNanos(math.min(remaining, 1.millis.toNanos))
    }
  }
}
