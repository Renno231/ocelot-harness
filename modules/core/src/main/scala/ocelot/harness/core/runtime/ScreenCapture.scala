package ocelot.harness.core.runtime

import scala.collection.mutable

import totoro.ocelot.brain.entity.Screen
import totoro.ocelot.brain.util.PackedColor
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.project.ScreenId
import ocelot.harness.core.workspace.{ScreenCell, ScreenSnapshot}

private final case class CapturedScreenContent(
    width: Int,
    height: Int,
    characters: Vector[Vector[Int]],
    colors: Vector[Vector[Short]],
    palette: Vector[Int],
    colorDepth: Int,
    powered: Boolean,
    precisionMode: Boolean
)

private[runtime] final class ScreenCapture(workspace: Workspace) {
  private val previous = mutable.Map.empty[ScreenId, CapturedScreenContent]
  private val revisions = mutable.Map.empty[ScreenId, Long]

  def capture(id: ScreenId, screen: Screen): ScreenSnapshot = screen.synchronized {
    val data = screen.data
    val width = data.width
    val height = data.height
    val characters = data.buffer.iterator.map(_.clone()).toVector
    val colors = data.color.iterator.map(_.clone()).toVector
    val format = data.format
    val palette = format match {
      case mutablePalette: PackedColor.MutablePaletteFormat =>
        Vector.tabulate(16)(mutablePalette.apply)
      case _ => Vector.empty
    }
    val content = CapturedScreenContent(
      width,
      height,
      characters.map(_.toVector),
      colors.map(_.toVector),
      palette,
      PackedColor.Depth.bits(format.depth),
      screen.getPowerState,
      screen.getPrecisionMode
    )
    val revision = previous.get(id) match {
      case Some(existing) if existing == content => revisions(id)
      case _                                     => revisions.getOrElse(id, 0L) + 1L
    }
    previous.update(id, content)
    revisions.update(id, revision)

    val text = characters.iterator
      .map { row =>
        val builder = new java.lang.StringBuilder(width)
        row.foreach(builder.appendCodePoint)
        builder.toString
      }
      .mkString("\n")
    val cells = characters.indices.iterator.flatMap { row =>
      characters(row).indices.iterator.map { column =>
        val packed = colors(row)(column)
        ScreenCell(
          characters(row)(column),
          PackedColor.unpackForeground(packed, format),
          PackedColor.unpackBackground(packed, format),
          packed
        )
      }
    }.toVector

    ScreenSnapshot(
      id,
      revision,
      width,
      height,
      text,
      cells,
      palette,
      content.colorDepth,
      content.powered,
      workspace.getIngameTime.toLong,
      Option(screen.node).flatMap(node => Option(node.address)),
      content.precisionMode
    )
  }
}
