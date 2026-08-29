package ocelot.harness.core.workspace

import ocelot.harness.core.artifact.ArtifactDescription

final class SnapshotName private (val value: String) extends Serializable {
  override def equals(other: Any): Boolean = other match {
    case that: SnapshotName => value == that.value
    case _                  => false
  }
  override def hashCode(): Int = value.hashCode
  override def toString: String = value
}

object SnapshotName {
  private val Pattern = "[a-z][a-z0-9-]{0,62}".r

  def parse(value: String): Either[String, SnapshotName] = Option(value) match {
    case Some(Pattern()) => Right(new SnapshotName(value))
    case _               => Left("must match [a-z][a-z0-9-]{0,62}")
  }
}

sealed trait HostDiskSnapshotPolicy extends Product with Serializable {
  def name: String
}

object HostDiskSnapshotPolicy {
  case object ReferenceOnly extends HostDiskSnapshotPolicy {
    override val name: String = "reference-only"
  }
  case object Copy extends HostDiskSnapshotPolicy {
    override val name: String = "copy"
  }
}

final case class SnapshotRequest(
    name: SnapshotName,
    hostDisks: HostDiskSnapshotPolicy = HostDiskSnapshotPolicy.ReferenceOnly,
    maxBytes: Long = 64L * 1024L * 1024L
)

final case class SnapshotDescription(
    name: SnapshotName,
    relativePath: String,
    size: Long,
    sha256: String,
    hostDisks: HostDiskSnapshotPolicy,
    captureTick: Long,
    copiedDiskBytes: Long = 0L
)

final case class DiagnosticFailure(
    code: String,
    message: String,
    stackTrace: Option[String] = None
)

final case class DiagnosticRequest(
    relativePath: String,
    maxBytes: Long = 16L * 1024L * 1024L,
    failure: Option[DiagnosticFailure] = None
)

final case class DiagnosticBundle(
    artifact: ArtifactDescription,
    entries: Vector[String]
)
