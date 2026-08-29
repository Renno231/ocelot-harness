package ocelot.harness.core.artifact

import java.awt.image.BufferedImage
import java.io.{BufferedReader, InputStreamReader, Reader}
import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.util.control.NonFatal

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.InvalidCapture
import ocelot.harness.core.workspace.ScreenSnapshot

final case class RenderOptions(
    scale: Int = 1,
    maxPixels: Long = 16777216L
)

private[artifact] final case class Glyph(width: Int, rows: Vector[Int])

private[artifact] final class GlyphFont private (private val glyphs: Map[Int, Glyph]) {
  private[artifact] def glyph(codePoint: Int): Option[Glyph] = glyphs.get(codePoint)
}

private[artifact] object GlyphFont {
  def parse(reader: Reader): Either[HarnessError, GlyphFont] = {
    if (reader == null) Left(InvalidCapture("font reader is required"))
    else {
      val input = new BufferedReader(reader)
      val glyphs = mutable.Map.empty[Int, Glyph]
      var lineNumber = 0
      var failure: Option[HarnessError] = None
      var line: String = null
      try {
        while (failure.isEmpty && { line = input.readLine(); line != null }) {
          lineNumber += 1
          if (line.nonEmpty) {
            parseLine(line, lineNumber) match {
              case Left(error) => failure = Some(error)
              case Right((codePoint, glyph)) =>
                if (glyphs.contains(codePoint)) {
                  failure = Some(InvalidCapture(s"duplicate font glyph at line $lineNumber"))
                } else glyphs.update(codePoint, glyph)
            }
          }
        }
      } catch {
        case NonFatal(error) =>
          failure = Some(InvalidCapture(s"cannot read font: ${errorMessage(error)}"))
      }
      failure match {
        case Some(error) => Left(error)
        case None        => Right(new GlyphFont(glyphs.toMap))
      }
    }
  }

  private def parseLine(line: String, lineNumber: Int): Either[HarnessError, (Int, Glyph)] = {
    val separator = line.indexOf(':')
    if (separator <= 0 || separator == line.length - 1) {
      Left(InvalidCapture(s"invalid font glyph at line $lineNumber"))
    } else {
      val code = line.substring(0, separator)
      val bitmap = line.substring(separator + 1)
      val rowDigits = bitmap.length match {
        case 32 => 2
        case 64 => 4
        case _  => 0
      }
      if (rowDigits == 0 || !code.matches("[0-9A-Fa-f]{4,6}") || !bitmap.matches("[0-9A-Fa-f]+")) {
        Left(InvalidCapture(s"invalid font glyph at line $lineNumber"))
      } else {
        try {
          val codePoint = Integer.parseInt(code, 16)
          if (!Character.isValidCodePoint(codePoint)) {
            Left(InvalidCapture(s"invalid font code point at line $lineNumber"))
          } else {
            val rows = Vector.tabulate(16) { row =>
              Integer.parseInt(bitmap.substring(row * rowDigits, (row + 1) * rowDigits), 16)
            }
            Right(codePoint -> Glyph(rowDigits / 2 * 8, rows))
          }
        } catch {
          case _: NumberFormatException =>
            Left(InvalidCapture(s"invalid font glyph at line $lineNumber"))
        }
      }
    }
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

object ScreenRenderer {
  private val CellWidth = 8
  private val CellHeight = 16
  private val HardPixelLimit = 16777216L

  private lazy val defaultFont: Either[HarnessError, GlyphFont] = {
    val stream = getClass.getResourceAsStream("/assets/opencomputers/font.hex")
    if (stream == null) Left(InvalidCapture("OpenComputers font.hex is unavailable"))
    else {
      try GlyphFont.parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
      finally stream.close()
    }
  }

  def render(
      snapshot: ScreenSnapshot,
      options: RenderOptions = RenderOptions()
  ): Either[HarnessError, BufferedImage] =
    defaultFont.flatMap(render(snapshot, options, _))

  private[artifact] def render(
      snapshot: ScreenSnapshot,
      options: RenderOptions,
      font: GlyphFont
  ): Either[HarnessError, BufferedImage] = {
    validate(snapshot, options, font).map { _ =>
      val pixelWidth = snapshot.width * CellWidth
      val pixelHeight = snapshot.height * CellHeight
      val unscaled = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB)
      if (!snapshot.powered) {
        fill(unscaled, 0, 0, pixelWidth, pixelHeight, 0)
      } else {
        var row = 0
        while (row < snapshot.height) {
          var column = 0
          while (column < snapshot.width) {
            val cell = snapshot.cells(row * snapshot.width + column)
            font.glyph(cell.codePoint) match {
              case Some(glyph) =>
                drawGlyph(
                  unscaled,
                  column * CellWidth,
                  row * CellHeight,
                  glyph,
                  cell.foreground,
                  cell.background
                )
                column += glyph.width / CellWidth
              case None =>
                fill(
                  unscaled,
                  column * CellWidth,
                  row * CellHeight,
                  CellWidth,
                  CellHeight,
                  cell.background
                )
                column += 1
            }
          }
          row += 1
        }
      }
      if (options.scale == 1) unscaled else scale(unscaled, options.scale)
    }
  }

  private def validate(
      snapshot: ScreenSnapshot,
      options: RenderOptions,
      font: GlyphFont
  ): Either[HarnessError, Unit] = {
    if (snapshot == null) Left(InvalidCapture("screen snapshot is required"))
    else if (options == null) Left(InvalidCapture("render options are required"))
    else if (font == null) Left(InvalidCapture("font is required"))
    else if (snapshot.width <= 0 || snapshot.height <= 0)
      Left(InvalidCapture("screen dimensions must be positive"))
    else if (BigInt(snapshot.cells.size) != BigInt(snapshot.width) * snapshot.height)
      Left(InvalidCapture("screen cell count does not match its dimensions"))
    else if (options.scale <= 0 || options.scale > 16)
      Left(InvalidCapture("render scale must be between 1 and 16"))
    else if (options.maxPixels <= 0L)
      Left(InvalidCapture("pixel limit must be positive"))
    else {
      val scaledWidth = BigInt(snapshot.width) * CellWidth * options.scale
      val scaledHeight = BigInt(snapshot.height) * CellHeight * options.scale
      val pixels = scaledWidth * scaledHeight
      val effectiveLimit = math.min(options.maxPixels, HardPixelLimit)
      if (scaledWidth > Int.MaxValue || scaledHeight > Int.MaxValue)
        Left(InvalidCapture("rendered image dimensions exceed platform limits"))
      else if (pixels > effectiveLimit)
        Left(InvalidCapture(s"rendered image exceeds pixel limit $effectiveLimit"))
      else Right(())
    }
  }

  private def drawGlyph(
      image: BufferedImage,
      left: Int,
      top: Int,
      glyph: Glyph,
      foreground: Int,
      background: Int
  ): Unit = {
    val visibleWidth = math.min(glyph.width, image.getWidth - left)
    fill(image, left, top, visibleWidth, CellHeight, background)
    var row = 0
    while (row < CellHeight) {
      var column = 0
      while (column < visibleWidth) {
        val mask = 1 << (glyph.width - column - 1)
        if ((glyph.rows(row) & mask) != 0)
          image.setRGB(left + column, top + row, opaque(foreground))
        column += 1
      }
      row += 1
    }
  }

  private def fill(
      image: BufferedImage,
      left: Int,
      top: Int,
      width: Int,
      height: Int,
      color: Int
  ): Unit = {
    var y = top
    while (y < top + height) {
      var x = left
      while (x < left + width) {
        image.setRGB(x, y, opaque(color))
        x += 1
      }
      y += 1
    }
  }

  private def scale(source: BufferedImage, factor: Int): BufferedImage = {
    val result = new BufferedImage(
      source.getWidth * factor,
      source.getHeight * factor,
      BufferedImage.TYPE_INT_RGB
    )
    var y = 0
    while (y < result.getHeight) {
      var x = 0
      while (x < result.getWidth) {
        result.setRGB(x, y, source.getRGB(x / factor, y / factor))
        x += 1
      }
      y += 1
    }
    result
  }

  private def opaque(color: Int): Int = 0xff000000.toInt | (color & 0xffffff)
}
