package ocelot.harness.core.runtime

import scala.collection.mutable

import ocelot.harness.core.workspace.ProfileReport

/** Renders a profile as folded stacks (the input flame graph tools take) and as a standalone flame
  * graph picture: each box is a function, its width the share of samples it was on the stack,
  * callers below callees.
  */
private[runtime] object FlameGraph {
  private val Width = 1200.0
  private val RowHeight = 17
  private val MaxDepth = 64

  def folded(report: ProfileReport): String =
    report.folded
      .map(entry => s"${entry.stack.map(_.replace(';', ',')).mkString(";")} ${entry.samples}")
      .mkString("", "\n", "\n")

  def svg(report: ProfileReport): String = {
    val root = new Node("all")
    report.folded.foreach { entry =>
      var node = root
      node.samples += entry.samples
      entry.stack.take(MaxDepth).foreach { frame =>
        node = node.children.getOrElseUpdate(frame, new Node(frame))
        node.samples += entry.samples
      }
    }
    val depth = math.max(1, maxDepth(root))
    val height = (depth + 1) * RowHeight + 40
    val boxes = new StringBuilder
    def draw(node: Node, x: Double, level: Int): Unit = {
      val width = if (root.samples == 0L) 0.0 else Width * node.samples / root.samples
      if (width >= 0.5) {
        val y = height - 20 - (level + 1) * RowHeight
        val share = if (root.samples == 0L) 0.0 else 100.0 * node.samples / root.samples
        val label = escape(node.name)
        boxes.append(
          f"""<g><title>$label (${node.samples} samples, $share%.1f%%)</title>""" +
            f"""<rect x="$x%.1f" y="$y" width="$width%.1f" height="${RowHeight - 1}" fill="${colour(
                node.name
              )}"/>"""
        )
        val characters = ((width - 6) / 7).toInt
        if (characters >= 3) {
          val text =
            if (node.name.length <= characters) label
            else escape(node.name.take(characters - 1) + "…")
          boxes.append(f"""<text x="${x + 3}%.1f" y="${y + RowHeight - 5}">$text</text>""")
        }
        boxes.append("</g>")
        var childX = x
        node.children.values.toVector.sortBy(_.name).foreach { child =>
          draw(child, childX, level + 1)
          childX += Width * child.samples / math.max(1L, root.samples)
        }
      }
    }
    draw(root, 0.0, 0)
    val title = escape(
      s"Lua profile${report.computerId.fold("")(id => s" of $id")}: ${report.samples} samples over ${report.ticks} ticks"
    )
    s"""<svg xmlns="http://www.w3.org/2000/svg" width="${Width.toInt}" height="$height" font-family="monospace" font-size="12">""" +
      s"""<rect width="100%" height="100%" fill="#f8f8f8"/><text x="4" y="16">$title</text>$boxes</svg>\n"""
  }

  private final class Node(val name: String) {
    var samples = 0L
    val children = mutable.LinkedHashMap.empty[String, Node]
  }

  private def maxDepth(node: Node): Int =
    if (node.children.isEmpty) 0 else 1 + node.children.values.map(maxDepth).max

  private def colour(name: String): String = {
    val hash = name.hashCode & 0x7fffffff
    val red = 205 + hash % 50
    val green = 80 + (hash / 50) % 130
    f"rgb($red,$green,60)"
  }

  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
