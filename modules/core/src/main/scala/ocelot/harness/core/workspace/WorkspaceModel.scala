package ocelot.harness.core.workspace

import java.nio.file.Path

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
    cards: Vector[CardDescription]
)

final case class ScreenDescription(
    id: ScreenId,
    tier: Int,
    aspectRatio: (Int, Int),
    runtimeAddress: String,
    keyboardRuntimeAddress: Option[String]
)

final case class WorkspaceDescription(
    projectId: ProjectId,
    computers: Vector[ComputerDescription],
    screens: Vector[ScreenDescription],
    connections: Vector[ConnectionDefinition]
)

trait HarnessSession extends AutoCloseable {
  def describe(): WorkspaceDescription
}
