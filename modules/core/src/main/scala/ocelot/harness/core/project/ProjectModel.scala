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
    allowInternetTcp: Boolean = false
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
    limits: RuntimeLimits
)

sealed trait MemoryTier extends Product with Serializable {
  def value: BigDecimal
}

object MemoryTier {
  case object Three extends MemoryTier {
    override val value: BigDecimal = BigDecimal(3)
  }
  case object ThreeAndHalf extends MemoryTier {
    override val value: BigDecimal = BigDecimal("3.5")
  }
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

sealed trait CardKind extends Product with Serializable
object CardKind {
  case object Network extends CardKind
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

final case class ValidatedProject(
    schemaVersion: Int,
    id: ProjectId,
    paths: ProjectPaths,
    runtime: ProjectRuntime,
    computers: Vector[ComputerDefinition],
    screens: Vector[ScreenDefinition],
    connections: Vector[ConnectionDefinition]
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
  private[project] def from(errors: Vector[ProjectError]): ProjectErrors =
    new ProjectErrors(errors.sortBy(error => (error.path, error.code, error.message)))
}
