package ocelot.harness.core.workspace

import java.nio.file.Path

import ocelot.harness.core.HarnessError
import ocelot.harness.core.artifact.{ArtifactDescription, ScreenArtifactRequest}
import ocelot.harness.core.project.{
  CardKind,
  ComputerId,
  ConnectionDefinition,
  DiskAccess,
  DiskId,
  ProjectId,
  ScreenId
}

sealed trait ComponentRole extends Product with Serializable {
  def name: String
}

object ComponentRole {
  case object Cpu extends ComponentRole {
    override val name: String = "cpu"
  }
  case object Memory extends ComponentRole {
    override val name: String = "memory"
  }
  case object Gpu extends ComponentRole {
    override val name: String = "gpu"
  }
  case object Eeprom extends ComponentRole {
    override val name: String = "eeprom"
  }
  final case class Other(override val name: String) extends ComponentRole
}

final case class ComponentDescription(
    role: ComponentRole,
    tier: String,
    runtimeAddress: Option[String]
)

final case class DiskDescription(
    id: DiskId,
    tier: Int,
    label: String,
    source: Path,
    access: DiskAccess,
    runtimeAddress: String
)

final case class CardDescription(
    kind: CardKind,
    tier: Int,
    runtimeAddress: String
)

final case class ComputerDescription(
    id: ComputerId,
    caseTier: Int,
    runtimeAddress: String,
    components: Vector[ComponentDescription],
    disks: Vector[DiskDescription],
    cards: Vector[CardDescription],
    kind: String = "computer"
)

final case class ScreenDescription(
    id: ScreenId,
    tier: Int,
    aspectRatio: (Int, Int),
    runtimeAddress: String,
    keyboardRuntimeAddress: Option[String]
)

final case class DeviceDescription(
    id: String,
    kind: String,
    tier: Option[Int],
    runtimeAddress: Option[String]
)

final case class WorkspaceDescription(
    projectId: ProjectId,
    computers: Vector[ComputerDescription],
    screens: Vector[ScreenDescription],
    connections: Vector[ConnectionDefinition],
    devices: Vector[DeviceDescription] = Vector.empty,
    deviceConnections: Vector[(String, String)] = Vector.empty
)

final case class MediaEjectResult(driveId: String, ejected: Boolean)

/** The input now on one side of a computer's redstone card (side: bottom, top, back, front, right
  * or left).
  */
final case class RedstoneInputResult(computerId: String, side: String, value: Int)

trait HarnessSession extends AutoCloseable {
  def describe(): WorkspaceDescription
  def startMachine(id: ComputerId): Either[HarnessError, MachineStatus]
  def stopMachine(id: ComputerId): Either[HarnessError, MachineStatus]
  def resetMachine(id: ComputerId): Either[HarnessError, MachineStatus]
  def machineStatus(id: ComputerId): Either[HarnessError, MachineStatus]
  def run(request: RunRequest): Either[HarnessError, RunResult]
  def startClock(tps: Option[Int] = None): Either[HarnessError, SimulationClockStatus]
  def pauseClock(): Either[HarnessError, SimulationClockStatus]
  def resumeClock(): Either[HarnessError, SimulationClockStatus]
  def stepClock(count: Int): Either[HarnessError, SimulationClockStatus]
  def setClockRate(tps: Int): Either[HarnessError, SimulationClockStatus]
  def clockStatus(): Either[HarnessError, SimulationClockStatus]
  def readScreen(id: ScreenId): Either[HarnessError, ScreenSnapshot]
  def captureScreen(
      id: ScreenId,
      request: ScreenArtifactRequest
  ): Either[HarnessError, ArtifactDescription]
  def send(id: ScreenId, input: UserInput): Either[HarnessError, InputResult]
  def recentEvents(): Either[HarnessError, EventSnapshot]
  def saveSnapshot(request: SnapshotRequest): Either[HarnessError, SnapshotDescription]
  def loadSnapshot(name: SnapshotName): Either[HarnessError, WorkspaceDescription]
  def ejectMedia(driveId: String): Either[HarnessError, MediaEjectResult]
  def setRedstoneInput(
      id: ComputerId,
      side: String,
      value: Int
  ): Either[HarnessError, RedstoneInputResult]
  def diagnostics(request: DiagnosticRequest): Either[HarnessError, DiagnosticBundle]

  // Observability: see docs/decisions/0005-observability.md.
  def readTrace(query: TraceQuery): Either[HarnessError, TracePage]
  def machineMetrics(): Either[HarnessError, Vector[MachineMetrics]]
  def metricsHistory(
      computerId: Option[ComputerId],
      afterTick: Long
  ): Either[HarnessError, Vector[MetricsSample]]
  def resetMetrics(): Either[HarnessError, Unit]
  def startProfile(computerId: Option[ComputerId]): Either[HarnessError, Unit]
  def stopProfile(
      relativePath: Option[String]
  ): Either[HarnessError, (ProfileReport, Vector[ArtifactDescription])]
  def executionMode(): Either[HarnessError, ExecutionMode]
  def setExecutionMode(mode: ExecutionMode): Either[HarnessError, ExecutionMode]
  def startRecording(name: SnapshotName): Either[HarnessError, RecordingStatus]
  def stopRecording(): Either[HarnessError, RecordingStatus]
  def replay(request: ReplayRequest): Either[HarnessError, ReplayResult]
  def sweep(request: SweepRequest): Either[HarnessError, SweepResult]
  def setBreakpoints(
      file: Path,
      lines: Vector[Int]
  ): Either[HarnessError, Vector[DebugBreakpoint]]
  def debugPause(): Either[HarnessError, DebugState]
  def debugState(afterSequence: Option[Long], waitMillis: Long): Either[HarnessError, DebugState]
  def debugFrames(): Either[HarnessError, Vector[DebugFrame]]
  def debugVariables(reference: String): Either[HarnessError, Vector[DebugVariable]]
  def debugResume(mode: String): Either[HarnessError, DebugState]
  def diffSnapshots(from: SnapshotName, to: SnapshotName): Either[HarnessError, SnapshotComparison]

  /** Starts a JVM flight recording of the service process (threads, allocation, GC, locks). */
  def startJvmRecording(): Either[HarnessError, Unit]
  def stopJvmRecording(relativePath: String): Either[HarnessError, ArtifactDescription]
}
