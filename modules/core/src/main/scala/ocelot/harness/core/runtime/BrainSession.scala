package ocelot.harness.core.runtime

import java.nio.file.Path

import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.workspace.{HarnessSession, WorkspaceDescription}

private[runtime] final class BrainSession(
    val projectRoot: Path,
    private val workspace: Workspace,
    private val description: Option[WorkspaceDescription],
    sessionClosed: BrainSession => Unit
) extends HarnessSession {
  private var closed = false

  def isClosed: Boolean = synchronized(closed)

  override def describe(): WorkspaceDescription = synchronized {
    if (closed) throw new IllegalStateException("project session is closed")
    description.getOrElse(
      throw new IllegalStateException("an empty lifecycle-test session has no topology")
    )
  }

  override def close(): Unit = synchronized {
    if (!closed) {
      closed = true
      try {
        workspace.getEntitiesIter.toVector.reverse.foreach(workspace.remove)
      } finally {
        sessionClosed(this)
      }
    }
  }
}
