package ocelot.harness.core

import ocelot.harness.core.project.ProjectError

sealed trait HarnessError extends Product with Serializable {
  def code: String
  def message: String
}

object HarnessError {
  case object RuntimeAlreadyOwned extends HarnessError {
    override val code: String = "runtime_already_owned"
    override val message: String = "the Ocelot runtime has already been claimed in this process"
  }

  case object RuntimeClosed extends HarnessError {
    override val code: String = "runtime_closed"
    override val message: String = "the Ocelot runtime owner is closed"
  }

  case object ProjectAlreadyOpen extends HarnessError {
    override val code: String = "project_already_open"
    override val message: String = "this runtime owner already has an active project session"
  }

  final case class RuntimeInitializationFailed(message: String) extends HarnessError {
    override val code: String = "runtime_initialization_failed"
  }

  final case class ProjectValidationFailed(errors: Vector[ProjectError]) extends HarnessError {
    override val code: String = "project_validation_failed"
    override val message: String =
      errors.map(error => s"${error.path}: ${error.message}").mkString("; ")
  }

  final case class ProjectOpenFailed(projectRoot: String, message: String) extends HarnessError {
    override val code: String = "project_open_failed"
  }

  case object SessionClosed extends HarnessError {
    override val code: String = "session_closed"
    override val message: String = "the project session is closed"
  }

  final case class UnknownComputer(id: String) extends HarnessError {
    override val code: String = "unknown_computer"
    override val message: String = s"unknown computer: $id"
  }

  final case class UnknownScreen(id: String) extends HarnessError {
    override val code: String = "unknown_screen"
    override val message: String = s"unknown screen: $id"
  }

  final case class InvalidRunRequest(message: String) extends HarnessError {
    override val code: String = "invalid_run_request"
  }

  final case class InvalidClockRequest(message: String) extends HarnessError {
    override val code: String = "invalid_clock_request"
  }

  final case class InvalidInput(message: String) extends HarnessError {
    override val code: String = "invalid_input"
  }

  final case class InputUnavailable(message: String) extends HarnessError {
    override val code: String = "input_unavailable"
  }

  final case class SessionOperationFailed(message: String) extends HarnessError {
    override val code: String = "session_operation_failed"
  }

  final case class InvalidCapture(message: String) extends HarnessError {
    override val code: String = "invalid_capture"
  }

  final case class InvalidArtifactPath(message: String) extends HarnessError {
    override val code: String = "invalid_artifact_path"
  }

  final case class ArtifactLimitExceeded(message: String) extends HarnessError {
    override val code: String = "artifact_limit_exceeded"
  }

  final case class ArtifactWriteFailed(message: String) extends HarnessError {
    override val code: String = "artifact_write_failed"
  }

  final case class SnapshotInvalid(message: String) extends HarnessError {
    override val code: String = "snapshot_invalid"
  }

  final case class SnapshotIncompatible(message: String) extends HarnessError {
    override val code: String = "snapshot_incompatible"
  }

  final case class SnapshotCorrupt(message: String) extends HarnessError {
    override val code: String = "snapshot_corrupt"
  }

  final case class SnapshotLimitExceeded(message: String) extends HarnessError {
    override val code: String = "snapshot_limit_exceeded"
  }

  final case class SnapshotWriteFailed(message: String) extends HarnessError {
    override val code: String = "snapshot_write_failed"
  }

  final case class SnapshotReadFailed(message: String) extends HarnessError {
    override val code: String = "snapshot_read_failed"
  }

  final case class DiagnosticFailed(message: String) extends HarnessError {
    override val code: String = "diagnostic_failed"
  }
}
