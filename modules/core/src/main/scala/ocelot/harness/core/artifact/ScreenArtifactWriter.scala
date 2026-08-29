package ocelot.harness.core.artifact

import java.nio.charset.StandardCharsets

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.InvalidCapture
import ocelot.harness.core.workspace.ScreenSnapshot

sealed trait ScreenArtifactFormat extends Product with Serializable
object ScreenArtifactFormat {
  case object Text extends ScreenArtifactFormat
  case object CellsJson extends ScreenArtifactFormat
  case object Png extends ScreenArtifactFormat
}

final case class ScreenArtifactRequest(
    relativePath: String,
    format: ScreenArtifactFormat,
    renderOptions: RenderOptions = RenderOptions()
)

private[harness] object ScreenArtifactWriter {
  def write(
      snapshot: ScreenSnapshot,
      request: ScreenArtifactRequest,
      store: ArtifactStore
  ): Either[HarnessError, ArtifactDescription] = {
    if (snapshot == null) Left(InvalidCapture("screen snapshot is required"))
    else if (request == null) Left(InvalidCapture("screen artifact request is required"))
    else if (request.format == null) Left(InvalidCapture("screen artifact format is required"))
    else
      request.format match {
        case ScreenArtifactFormat.Text =>
          Option(snapshot.text) match {
            case Some(text) =>
              store.writeBytes(
                request.relativePath,
                text.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8"
              )
            case None => Left(InvalidCapture("screen text is required"))
          }
        case ScreenArtifactFormat.CellsJson =>
          store.writeBytes(
            request.relativePath,
            cellsJson(snapshot).getBytes(StandardCharsets.UTF_8),
            "application/json"
          )
        case ScreenArtifactFormat.Png =>
          for {
            image <- ScreenRenderer.render(snapshot, request.renderOptions)
            artifact <- store.writePng(request.relativePath, image)
          } yield artifact
      }
  }

  private[artifact] def cellsJson(snapshot: ScreenSnapshot): String = {
    val palette = snapshot.palette.mkString("[", ",", "]")
    val cells = snapshot.cells
      .map { cell =>
        s"{\"codePoint\":${cell.codePoint},\"foreground\":${cell.foreground & 0xffffff}," +
          s"\"background\":${cell.background & 0xffffff},\"packedColor\":${cell.packedColor & 0xffff}}"
      }
      .mkString("[", ",", "]")
    s"{\"id\":\"${escape(snapshot.id.value)}\",\"revision\":${snapshot.revision}," +
      s"\"width\":${snapshot.width},\"height\":${snapshot.height}," +
      s"\"colorDepth\":${snapshot.colorDepth},\"powered\":${snapshot.powered}," +
      s"\"captureTick\":${snapshot.captureTick},\"palette\":$palette,\"cells\":$cells}\n"
  }

  private def escape(value: String): String = {
    val result = new StringBuilder
    value.foreach {
      case '"'                          => result.append("\\\"")
      case '\\'                         => result.append("\\\\")
      case '\b'                         => result.append("\\b")
      case '\f'                         => result.append("\\f")
      case '\n'                         => result.append("\\n")
      case '\r'                         => result.append("\\r")
      case '\t'                         => result.append("\\t")
      case character if character < ' ' => result.append(f"\\u${character.toInt}%04x")
      case character                    => result.append(character)
    }
    result.toString()
  }
}
