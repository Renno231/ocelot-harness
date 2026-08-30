package ocelot.harness.app.viewer

import scala.util.control.NonFatal

import ocelot.harness.core.project.ScreenId
import ocelot.harness.core.workspace.{ScreenCell, ScreenSnapshot}

private[app] final case class ViewerCell(x: Int, y: Int)

private[app] final case class ViewerClockStatus(
    state: String,
    targetTps: Int,
    measuredTps: Double,
    totalTicks: Long,
    overrunCount: Long,
    lastTickDurationNanos: Long
)

private[app] final case class ViewerGeometry(
    screenWidth: Int,
    screenHeight: Int,
    imageWidth: Int,
    imageHeight: Int
) {
  require(screenWidth > 0 && screenHeight > 0, "screen dimensions must be positive")
  require(imageWidth > 0 && imageHeight > 0, "image dimensions must be positive")

  def cellAt(pixelX: Int, pixelY: Int): Option[ViewerCell] =
    if (pixelX < 0 || pixelY < 0 || pixelX >= imageWidth || pixelY >= imageHeight) None
    else {
      val x = (pixelX.toLong * screenWidth / imageWidth).toInt + 1
      val y = (pixelY.toLong * screenHeight / imageHeight).toInt + 1
      Some(ViewerCell(x, y))
    }
}

private[app] object ViewerProtocol {
  private val User = "viewer"

  def decodeScreenIds(value: ujson.Value): Either[String, Vector[ScreenId]] =
    for {
      root <- asObject(value, "workspace")
      values <- array(root, "screens")
      ids <- traverse(values.zipWithIndex) { case (screen, index) =>
        for {
          entry <- asObject(screen, s"screens[$index]")
          raw <- string(entry, "screenId")
          id <- ScreenId.parse(raw).left.map(reason => s"screenId $reason")
        } yield id
      }
      _ <-
        if (ids.map(_.value).distinct.size == ids.size) Right(())
        else Left("workspace contains duplicate screen IDs")
    } yield ids

  def decodeClock(value: ujson.Value): Either[String, ViewerClockStatus] =
    try {
      for {
        root <- asObject(value, "clock status")
        state <- string(root, "state")
        _ <-
          if (state == "paused" || state == "running") Right(())
          else Left("clock state must be paused or running")
        targetTps <- boundedInt(root, "targetTps", 1, 1000)
        measuredTps <- finiteDouble(root, "measuredTps")
        _ <-
          if (measuredTps >= 0.0) Right(())
          else Left("measuredTps must not be negative")
        totalTicks <- nonNegativeInt64(root, "totalTicks")
        overrunCount <- nonNegativeInt64(root, "overrunCount")
        lastTickDurationNanos <- nonNegativeInt64(root, "lastTickDurationNanos")
      } yield ViewerClockStatus(
        state,
        targetTps,
        measuredTps,
        totalTicks,
        overrunCount,
        lastTickDurationNanos
      )
    } catch {
      case NonFatal(error) => Left(s"invalid clock response: ${message(error)}")
    }

  def decodeScreen(value: ujson.Value): Either[String, ScreenSnapshot] =
    try {
      for {
        root <- asObject(value, "screen")
        rawId <- string(root, "screenId")
        id <- ScreenId.parse(rawId).left.map(reason => s"screenId $reason")
        revision <- int64(root, "revision")
        width <- positiveInt(root, "width")
        height <- positiveInt(root, "height")
        text <- string(root, "text")
        cellValues <- array(root, "cells")
        _ <-
          if (BigInt(cellValues.size) == BigInt(width) * height) Right(())
          else Left("screen cell count does not match its dimensions")
        cells <- traverse(cellValues.zipWithIndex) { case (cell, index) =>
          decodeCell(cell, index)
        }
        paletteValues <- array(root, "palette")
        palette <- traverse(paletteValues.zipWithIndex) { case (color, index) =>
          colorInt(color, s"palette[$index]")
        }
        colorDepth <- positiveInt(root, "colorDepth")
        _ <-
          if (Set(1, 4, 8).contains(colorDepth)) Right(())
          else Left("colorDepth must be 1, 4, or 8")
        powered <- boolean(root, "powered")
        captureTick <- int64(root, "captureTick")
        runtimeAddress <- optionalString(root, "runtimeAddress")
        precisionMode <- boolean(root, "precisionMode")
      } yield ScreenSnapshot(
        id,
        revision,
        width,
        height,
        text,
        cells,
        palette,
        colorDepth,
        powered,
        captureTick,
        runtimeAddress,
        precisionMode
      )
    } catch {
      case NonFatal(error) => Left(s"invalid screen response: ${message(error)}")
    }

  def touch(screenId: String, cell: ViewerCell, button: Int): ujson.Obj =
    input(
      screenId,
      ujson.Obj(
        "type" -> "touch",
        "x" -> cell.x,
        "y" -> cell.y,
        "button" -> button,
        "user" -> User
      )
    )

  def drag(
      screenId: String,
      from: ViewerCell,
      to: ViewerCell,
      button: Int,
      steps: Int
  ): ujson.Obj =
    input(
      screenId,
      ujson.Obj(
        "type" -> "drag",
        "fromX" -> from.x,
        "fromY" -> from.y,
        "toX" -> to.x,
        "toY" -> to.y,
        "button" -> button,
        "steps" -> steps,
        "user" -> User
      )
    )

  def drop(screenId: String, cell: ViewerCell, button: Int): ujson.Obj =
    input(
      screenId,
      ujson.Obj(
        "type" -> "drop",
        "x" -> cell.x,
        "y" -> cell.y,
        "button" -> button,
        "user" -> User
      )
    )

  def scroll(screenId: String, cell: ViewerCell, delta: Int): ujson.Obj =
    input(
      screenId,
      ujson.Obj(
        "type" -> "scroll",
        "x" -> cell.x,
        "y" -> cell.y,
        "delta" -> delta,
        "user" -> User
      )
    )

  def typeText(screenId: String, text: String): ujson.Obj =
    input(
      screenId,
      ujson.Obj(
        "type" -> "type_text",
        "text" -> text,
        "interKeyTicks" -> 0,
        "user" -> User
      )
    )

  def paste(screenId: String, text: String): ujson.Obj =
    input(screenId, ujson.Obj("type" -> "paste", "text" -> text, "user" -> User))

  def key(screenId: String, key: String, down: Boolean): ujson.Obj =
    input(
      screenId,
      ujson.Obj(
        "type" -> (if (down) "key_down" else "key_up"),
        "key" -> key,
        "character" -> ujson.Null,
        "user" -> User
      )
    )

  private def input(screenId: String, value: ujson.Obj): ujson.Obj =
    ujson.Obj("screenId" -> screenId, "input" -> value)

  private def decodeCell(value: ujson.Value, index: Int): Either[String, ScreenCell] =
    for {
      cell <- asObject(value, s"cells[$index]")
      codePoint <- int(cell, "codePoint")
      _ <-
        if (Character.isValidCodePoint(codePoint)) Right(())
        else Left(s"cells[$index].codePoint is invalid")
      foreground <- color(cell, "foreground")
      background <- color(cell, "background")
      packed <- boundedInt(cell, "packedColor", 0, 0xffff)
    } yield ScreenCell(codePoint, foreground, background, packed.toShort)

  private def traverse[A, B](values: Iterable[A])(
      operation: A => Either[String, B]
  ): Either[String, Vector[B]] = {
    val builder = Vector.newBuilder[B]
    val iterator = values.iterator
    var failure: Option[String] = None
    while (iterator.hasNext && failure.isEmpty) {
      operation(iterator.next()) match {
        case Right(value) => builder += value
        case Left(error)  => failure = Some(error)
      }
    }
    failure.toLeft(builder.result())
  }

  private def asObject(value: ujson.Value, name: String): Either[String, ujson.Obj] = value match {
    case result: ujson.Obj => Right(result)
    case _                 => Left(s"$name must be an object")
  }

  private def array(value: ujson.Obj, field: String): Either[String, Vector[ujson.Value]] =
    value.value.get(field) match {
      case Some(result: ujson.Arr) => Right(result.value.toVector)
      case _                       => Left(s"$field must be an array")
    }

  private def string(value: ujson.Obj, field: String): Either[String, String] =
    value.value.get(field) match {
      case Some(ujson.Str(result)) => Right(result)
      case _                       => Left(s"$field must be a string")
    }

  private def optionalString(value: ujson.Obj, field: String): Either[String, Option[String]] =
    value.value.get(field) match {
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Str(result)) => Right(Some(result))
      case _                       => Left(s"$field must be a string or null")
    }

  private def boolean(value: ujson.Obj, field: String): Either[String, Boolean] =
    value.value.get(field) match {
      case Some(ujson.Bool(result)) => Right(result)
      case _                        => Left(s"$field must be a boolean")
    }

  private def finiteDouble(value: ujson.Obj, field: String): Either[String, Double] =
    value.value.get(field) match {
      case Some(ujson.Num(result)) if !result.isNaN && !result.isInfinite => Right(result)
      case _ => Left(s"$field must be a finite number")
    }

  private def int(value: ujson.Obj, field: String): Either[String, Int] =
    value.value.get(field) match {
      case Some(ujson.Num(result)) if result.isValidInt && result == result.toInt.toDouble =>
        Right(result.toInt)
      case _ => Left(s"$field must be an integer")
    }

  private def positiveInt(value: ujson.Obj, field: String): Either[String, Int] =
    int(value, field).flatMap(result =>
      if (result > 0) Right(result) else Left(s"$field must be positive")
    )

  private def boundedInt(
      value: ujson.Obj,
      field: String,
      minimum: Int,
      maximum: Int
  ): Either[String, Int] =
    int(value, field).flatMap(result =>
      if (result >= minimum && result <= maximum) Right(result)
      else Left(s"$field must be between $minimum and $maximum")
    )

  private def color(value: ujson.Obj, field: String): Either[String, Int] =
    value.value.get(field).toRight(s"$field is required").flatMap(colorInt(_, field))

  private def colorInt(value: ujson.Value, field: String): Either[String, Int] = value match {
    case ujson.Num(result)
        if result.isValidInt && result == result.toInt.toDouble && result >= 0 && result <= 0xffffff =>
      Right(result.toInt)
    case _ => Left(s"$field must be a 24-bit RGB integer")
  }

  private def int64(value: ujson.Obj, field: String): Either[String, Long] =
    value.value.get(field) match {
      case Some(ujson.Str(result)) =>
        try Right(java.lang.Long.parseLong(result))
        catch {
          case _: NumberFormatException => Left(s"$field must be a decimal int64 string")
        }
      case _ => Left(s"$field must be a decimal int64 string")
    }

  private def nonNegativeInt64(value: ujson.Obj, field: String): Either[String, Long] =
    int64(value, field).flatMap(result =>
      if (result >= 0L) Right(result) else Left(s"$field must not be negative")
    )

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
