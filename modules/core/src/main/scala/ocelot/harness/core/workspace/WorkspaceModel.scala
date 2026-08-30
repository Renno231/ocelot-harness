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

trait HarnessSession extends AutoCloseable {
  def describe(): WorkspaceDescription
  def startMachine(id: ComputerId): Either[HarnessError, MachineStatus]
  def stopMachine(id: ComputerId): Either[HarnessError, MachineStatus]
  def resetMachine(id: ComputerId): Either[HarnessError, MachineStatus]
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
  def diagnostics(request: DiagnosticRequest): Either[HarnessError, DiagnosticBundle]
}
