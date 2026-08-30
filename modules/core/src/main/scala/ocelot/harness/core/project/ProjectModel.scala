package ocelot.harness.core.project

import java.nio.file.Path

sealed abstract class LogicalId private[project] (val value: String) extends Serializable {
  final override def equals(other: Any): Boolean =
    other != null && other.getClass == getClass && other.asInstanceOf[LogicalId].value == value

  final override def hashCode(): Int = 31 * getClass.hashCode() + value.hashCode
  final override def toString: String = value
}

final class ProjectId private (value: String) extends LogicalId(value)
object ProjectId {
  def parse(value: String): Either[String, ProjectId] =
    LogicalId.validate(value).map(new ProjectId(_))
}

final class ComputerId private (value: String) extends LogicalId(value)
object ComputerId {
  def parse(value: String): Either[String, ComputerId] =
    LogicalId.validate(value).map(new ComputerId(_))
}

final class ScreenId private (value: String) extends LogicalId(value)
object ScreenId {
  def parse(value: String): Either[String, ScreenId] =
    LogicalId.validate(value).map(new ScreenId(_))
}

final class DiskId private (value: String) extends LogicalId(value)
object DiskId {
  def parse(value: String): Either[String, DiskId] =
    LogicalId.validate(value).map(new DiskId(_))
}

final class DeviceId private (value: String) extends LogicalId(value)
object DeviceId {
  def parse(value: String): Either[String, DeviceId] =
    LogicalId.validate(value).map(new DeviceId(_))
}

private[project] object LogicalId {
  private val Pattern = "[a-z][a-z0-9-]{0,62}".r

  def validate(value: String): Either[String, String] =
    Option(value) match {
      case Some(Pattern()) => Right(value)
      case _               => Left("must match [a-z][a-z0-9-]{0,62}")
    }
}

final case class ServicePolicy(
    additionalReadWriteRoots: Vector[Path] = Vector.empty,
    allowInternetHttp: Boolean = false,
    allowInternetTcp: Boolean = false,
    maxComputers: Int = 16,
    maxScreens: Int = 16,
    maxConnections: Int = 64,
    maxManagedDisks: Int = 32,
    maxDevices: Int = 128,
    maxInventoryItems: Int = 512
)

final case class ProjectPaths(
    projectRoot: Path,
    manifest: Path,
    artifacts: Path,
    snapshots: Path
)

final case class RuntimeLimits(
    defaultMaxTicks: Int,
    defaultMaxWallTimeMillis: Long,
    eventBufferSize: Int
)

final case class InternetSettings(
    requestedHttp: Boolean,
    requestedTcp: Boolean,
    httpEnabled: Boolean,
    tcpEnabled: Boolean
)

final case class ProjectRuntime(
    tickRate: Int,
    internet: InternetSettings,
    limits: RuntimeLimits,
    clockAutoStart: Boolean = false
)

sealed trait WorkspaceSourceDefinition extends Product with Serializable
object WorkspaceSourceDefinition {
  case object Manifest extends WorkspaceSourceDefinition
  final case class Desktop(directory: Path) extends WorkspaceSourceDefinition
}

sealed trait MemoryTier extends Product with Serializable {
  def value: BigDecimal
}

object MemoryTier {
  case object One extends MemoryTier {
    override val value: BigDecimal = BigDecimal(1)
  }
  case object OneAndHalf extends MemoryTier {
    override val value: BigDecimal = BigDecimal("1.5")
  }
  case object Two extends MemoryTier {
    override val value: BigDecimal = BigDecimal(2)
  }
  case object TwoAndHalf extends MemoryTier {
    override val value: BigDecimal = BigDecimal("2.5")
  }
  case object Three extends MemoryTier {
    override val value: BigDecimal = BigDecimal(3)
  }
  case object ThreeAndHalf extends MemoryTier {
    override val value: BigDecimal = BigDecimal("3.5")
  }

  val values: Vector[MemoryTier] =
    Vector(One, OneAndHalf, Two, TwoAndHalf, Three, ThreeAndHalf)
}

sealed trait DiskAccess extends Product with Serializable
object DiskAccess {
  case object ReadWrite extends DiskAccess
  case object ReadOnly extends DiskAccess
}

final case class DiskDefinition(
    id: DiskId,
    tier: Int,
    label: String,
    source: Path,
    access: DiskAccess
)

sealed trait CardKind extends Product with Serializable { def name: String }
object CardKind {
  case object Network extends CardKind { val name = "network" }
  case object Wireless extends CardKind { val name = "wireless" }
  case object Linked extends CardKind { val name = "linked" }
  case object Data extends CardKind { val name = "data" }
  case object Redstone extends CardKind { val name = "redstone" }
  case object Internet extends CardKind { val name = "internet" }
}

final case class CardDefinition(kind: CardKind, tier: Int)

final case class HardwareDefinition(
    cpuTier: Int,
    memory: Vector[MemoryTier],
    gpuTier: Int,
    eepromBuiltin: String,
    disks: Vector[DiskDefinition],
    cards: Vector[CardDefinition]
)

final case class ComputerDefinition(
    id: ComputerId,
    caseTier: Int,
    hardware: HardwareDefinition
)

final case class ScreenDefinition(
    id: ScreenId,
    tier: Int,
    keyboard: Boolean,
    aspectRatio: (Int, Int)
)

sealed trait ConnectionEndpoint extends Product with Serializable {
  def value: String
}

object ConnectionEndpoint {
  final case class Computer(id: ComputerId) extends ConnectionEndpoint {
    override def value: String = s"computer:${id.value}"
  }
  final case class Screen(id: ScreenId) extends ConnectionEndpoint {
    override def value: String = s"screen:${id.value}"
  }
}

final case class ConnectionDefinition(
    from: ConnectionEndpoint,
    to: ConnectionEndpoint
)

sealed trait ManifestDeviceKind extends Product with Serializable {
  def name: String
}
object ManifestDeviceKind {
  case object Computer extends ManifestDeviceKind { val name = "computer" }
  case object Screen extends ManifestDeviceKind { val name = "screen" }
  case object Rack extends ManifestDeviceKind { val name = "rack" }
  case object Server extends ManifestDeviceKind { val name = "server" }
  case object DiskDrive extends ManifestDeviceKind { val name = "disk-drive" }
  case object Raid extends ManifestDeviceKind { val name = "raid" }
  case object Hologram extends ManifestDeviceKind { val name = "hologram" }
  case object NoteBlock extends ManifestDeviceKind { val name = "note-block" }
  case object IronNoteBlock extends ManifestDeviceKind { val name = "iron-note-block" }
  case object Microcontroller extends ManifestDeviceKind { val name = "microcontroller" }
  case object Relay extends ManifestDeviceKind { val name = "relay" }
  case object Cable extends ManifestDeviceKind { val name = "cable" }

  val values: Vector[ManifestDeviceKind] = Vector(
    Computer,
    Screen,
    Rack,
    Server,
    DiskDrive,
    Raid,
    Hologram,
    NoteBlock,
    IronNoteBlock,
    Microcontroller,
    Relay,
    Cable
  )
  def fromName(value: String): Option[ManifestDeviceKind] = values.find(_.name == value)
}

sealed trait InventoryKind extends Product with Serializable { def name: String }
object InventoryKind {
  case object Cpu extends InventoryKind { val name = "cpu" }
  case object Apu extends InventoryKind { val name = "apu" }
  case object Memory extends InventoryKind { val name = "memory" }
  case object Gpu extends InventoryKind { val name = "gpu" }
  case object Eeprom extends InventoryKind { val name = "eeprom" }
  case object ComponentBus extends InventoryKind { val name = "component-bus" }
  case object ManagedHdd extends InventoryKind { val name = "managed-hdd" }
  case object UnmanagedHdd extends InventoryKind { val name = "unmanaged-hdd" }
  case object ManagedFloppy extends InventoryKind { val name = "managed-floppy" }
  case object UnmanagedFloppy extends InventoryKind { val name = "unmanaged-floppy" }
  case object Network extends InventoryKind { val name = "network" }
  case object Wireless extends InventoryKind { val name = "wireless" }
  case object Linked extends InventoryKind { val name = "linked" }
  case object Data extends InventoryKind { val name = "data" }
  case object Redstone extends InventoryKind { val name = "redstone" }
  case object Internet extends InventoryKind { val name = "internet" }

  val values: Vector[InventoryKind] = Vector(
    Cpu,
    Apu,
    Memory,
    Gpu,
    Eeprom,
    ComponentBus,
    ManagedHdd,
    UnmanagedHdd,
    ManagedFloppy,
    UnmanagedFloppy,
    Network,
    Wireless,
    Linked,
    Data,
    Redstone,
    Internet
  )
  def fromName(value: String): Option[InventoryKind] = values.find(_.name == value)
}

final case class InventoryItemDefinition(
    slot: String,
    kind: InventoryKind,
    tier: Option[BigDecimal],
    id: Option[DiskId],
    label: Option[String],
    source: Option[Path],
    access: Option[DiskAccess],
    builtin: Option[String],
    tunnel: Option[String]
)

final case class ManifestDeviceDefinition(
    id: DeviceId,
    kind: ManifestDeviceKind,
    tier: Option[Int],
    keyboard: Boolean,
    aspectRatio: (Int, Int),
    inventory: Vector[InventoryItemDefinition],
    label: Option[String],
    source: Option[Path],
    access: Option[DiskAccess]
)

final case class DevicePortReference(device: DeviceId, port: String) {
  def value: String = s"${device.value}:$port"
}
final case class DeviceConnectionDefinition(from: DevicePortReference, to: DevicePortReference)
final case class ManifestTopologyDefinition(
    devices: Vector[ManifestDeviceDefinition],
    connections: Vector[DeviceConnectionDefinition]
)

final case class ValidatedProject(
    schemaVersion: Int,
    id: ProjectId,
    paths: ProjectPaths,
    runtime: ProjectRuntime,
    workspaceSource: WorkspaceSourceDefinition,
    computers: Vector[ComputerDefinition],
    screens: Vector[ScreenDefinition],
    connections: Vector[ConnectionDefinition],
    manifestTopology: Option[ManifestTopologyDefinition] = None
)

final case class ProjectError(path: String, code: String, message: String)

final class ProjectErrors private (val errors: Vector[ProjectError]) {
  require(errors.nonEmpty, "project errors must be non-empty")

  override def equals(other: Any): Boolean = other match {
    case that: ProjectErrors => errors == that.errors
    case _                   => false
  }

  override def hashCode(): Int = errors.hashCode()
  override def toString: String = errors.mkString("ProjectErrors(", ", ", ")")
}

object ProjectErrors {
  private[harness] def from(errors: Vector[ProjectError]): ProjectErrors =
    new ProjectErrors(errors.sortBy(error => (error.path, error.code, error.message)))
}
