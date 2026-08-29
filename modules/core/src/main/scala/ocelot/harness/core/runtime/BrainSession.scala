package ocelot.harness.core.runtime

import java.nio.file.Path

import totoro.ocelot.brain.workspace.Workspace

final class BrainSession private[runtime] (
    val projectRoot: Path,
    private val workspace: Workspace,
    sessionClosed: BrainSession => Unit
) extends AutoCloseable {
  private var closed = false

  def isClosed: Boolean = synchronized(closed)

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
