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
}
