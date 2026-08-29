package ocelot.harness.core.runtime

import totoro.ocelot.brain.entity.{Keyboard, Screen}
import totoro.ocelot.brain.user.User
import totoro.ocelot.brain.util.ClipboardSplitter

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.{InputUnavailable, InvalidInput}
import ocelot.harness.core.workspace.{InputResult, UserInput}

private[runtime] final class InputController {
  private val clipboardSplitter = new ClipboardSplitter

  def send(
      screen: Screen,
      keyboard: Option[Keyboard],
      screenTier: Int,
      input: UserInput,
      advanceTick: () => Unit
  ): Either[HarnessError, InputResult] = {
    if (input == null) Left(InvalidInput("input is required"))
    else if (input.user == null || input.user.isEmpty || input.user.length > 32)
      Left(InvalidInput("user must contain 1 to 32 characters"))
    else {
      val player = User(input.user)
      input match {
        case UserInput.KeyDown(key, character, _) =>
          withKeyboard(keyboard) { _ =>
            keyCode(key).map { code =>
              screen.keyDown(character, code, player)
              InputResult(1, 0)
            }
          }
        case UserInput.KeyUp(key, character, _) =>
          withKeyboard(keyboard) { _ =>
            keyCode(key).map { code =>
              screen.keyUp(character, code, player)
              InputResult(1, 0)
            }
          }
        case UserInput.TypeText(text, interKeyTicks, _) =>
          if (text == null) Left(InvalidInput("typed text is required"))
          else if (text.length > 4096) Left(InvalidInput("typed text exceeds 4096 characters"))
          else if (interKeyTicks < 0) Left(InvalidInput("inter-key ticks must not be negative"))
          else if (text.length.toLong * interKeyTicks > 10000L)
            Left(InvalidInput("typed text exceeds the 10000-tick input bound"))
          else
            withKeyboard(keyboard) { _ =>
              val characters = text.toVector
              val unknown = characters.find(character => keyCodeForCharacter(character).isEmpty)
              unknown match {
                case Some(character) =>
                  Left(InvalidInput(f"no scan-code mapping for character U+${character.toInt}%04X"))
                case None =>
                  var ticks = 0
                  characters.foreach { character =>
                    val code = keyCodeForCharacter(character).get
                    screen.keyDown(character, code, player)
                    screen.keyUp(character, code, player)
                    (0 until interKeyTicks).foreach { _ =>
                      advanceTick()
                      ticks += 1
                    }
                  }
                  Right(InputResult(characters.size * 2, ticks))
              }
            }
        case UserInput.Paste(text, _) =>
          if (text == null) Left(InvalidInput("clipboard text is required"))
          else
            withKeyboard(keyboard) { _ =>
              clipboardSplitter.split(text) match {
                case None => Left(InvalidInput("clipboard input exceeds limits or is cooling down"))
                case Some(chunks) =>
                  val values = chunks.toVector
                  values.foreach(screen.clipboard(_, player))
                  Right(InputResult(values.size, 0))
              }
            }
        case UserInput.Touch(x, y, button, _) =>
          validateButton(button).flatMap { _ =>
            withTouch(screen, screenTier, x, y) {
              screen.mouseDown(x - 1, y - 1, button, player)
              InputResult(1, 0)
            }
          }
        case UserInput.Drag(fromX, fromY, toX, toY, button, steps, _) =>
          if (steps < 1 || steps > 1024)
            Left(InvalidInput("drag steps must be between 1 and 1024"))
          else if (button < 0 || button > 1) Left(InvalidInput("button must be 0 or 1"))
          else if (screenTier < 2)
            Left(InputUnavailable("the screen tier does not support touch input"))
          else
            validateCoordinates(screen, fromX, fromY)
              .flatMap(_ => validateCoordinates(screen, toX, toY))
              .map { _ =>
                screen.mouseDown(fromX - 1, fromY - 1, button, player)
                (1 to steps).foreach { step =>
                  val x = fromX + (toX - fromX) * step.toDouble / steps
                  val y = fromY + (toY - fromY) * step.toDouble / steps
                  screen.mouseDrag(x - 1, y - 1, button, player)
                }
                screen.mouseUp(toX - 1, toY - 1, button, player)
                InputResult(steps + 2, 0)
              }
        case UserInput.Drop(x, y, button, _) =>
          validateButton(button).flatMap { _ =>
            withTouch(screen, screenTier, x, y) {
              screen.mouseUp(x - 1, y - 1, button, player)
              InputResult(1, 0)
            }
          }
        case UserInput.Scroll(x, y, delta, _) =>
          withTouch(screen, screenTier, x, y) {
            screen.mouseScroll(x - 1, y - 1, delta, player)
            InputResult(1, 0)
          }
      }
    }
  }

  private def validateButton(button: Int): Either[HarnessError, Unit] =
    if (button == 0 || button == 1) Right(())
    else Left(InvalidInput("button must be 0 or 1"))

  private def withKeyboard[A](
      keyboard: Option[Keyboard]
  )(operation: Keyboard => Either[HarnessError, A]): Either[HarnessError, A] =
    keyboard match {
      case Some(value) => operation(value)
      case None        => Left(InputUnavailable("the screen has no attached keyboard"))
    }

  private def withTouch[A](screen: Screen, tier: Int, x: Double, y: Double)(
      operation: => A
  ): Either[HarnessError, A] = {
    if (tier < 2) Left(InputUnavailable("the screen tier does not support touch input"))
    else validateCoordinates(screen, x, y).map(_ => operation)
  }

  private def validateCoordinates(
      screen: Screen,
      x: Double,
      y: Double
  ): Either[HarnessError, Unit] = screen.synchronized {
    val (width, height) = screen.data.size
    if (x.isNaN || x.isInfinity || y.isNaN || y.isInfinity) {
      Left(InvalidInput("screen coordinates must be finite"))
    } else if (x < 1 || x > width || y < 1 || y > height) {
      Left(InvalidInput(s"screen coordinates ($x,$y) are outside 1..$width,1..$height"))
    } else if ((x != x.toInt || y != y.toInt) && !screen.getPrecisionMode) {
      Left(InputUnavailable("fractional coordinates require screen precision mode"))
    } else Right(())
  }

  private def keyCode(name: String): Either[HarnessError, Int] =
    KeyCodes.get(Option(name).getOrElse("").toLowerCase) match {
      case Some(code) => Right(code)
      case None       => Left(InvalidInput(s"unknown key: ${String.valueOf(name)}"))
    }

  private def keyCodeForCharacter(character: Char): Option[Int] = character match {
    case '\n' | '\r'             => KeyCodes.get("enter")
    case '\t'                    => KeyCodes.get("tab")
    case ' '                     => KeyCodes.get("space")
    case value if value.isLetter => KeyCodes.get(value.toLower.toString)
    case value if value.isDigit  => KeyCodes.get(value.toString)
    case value                   => CharacterKeys.get(value)
  }

  private val CharacterKeys: Map[Char, Int] = Map(
    '!' -> 2,
    '@' -> 3,
    '#' -> 4,
    '$' -> 5,
    '%' -> 6,
    '^' -> 7,
    '&' -> 8,
    '*' -> 9,
    '(' -> 10,
    ')' -> 11,
    '-' -> 12,
    '_' -> 12,
    '=' -> 13,
    '+' -> 13,
    '[' -> 26,
    '{' -> 26,
    ']' -> 27,
    '}' -> 27,
    ';' -> 39,
    ':' -> 39,
    '\'' -> 40,
    '"' -> 40,
    '`' -> 41,
    '~' -> 41,
    '\\' -> 43,
    '|' -> 43,
    ',' -> 51,
    '<' -> 51,
    '.' -> 52,
    '>' -> 52,
    '/' -> 53,
    '?' -> 53
  )

  private val KeyCodes: Map[String, Int] = {
    val letters = "abcdefghijklmnopqrstuvwxyz"
      .zip(
        Vector(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22,
          47, 17, 45, 21, 44)
      )
      .map { case (character, code) => character.toString -> code }
    val digits = "1234567890".zip(2 to 11).map { case (character, code) =>
      character.toString -> code
    }
    (letters ++ digits).toMap ++ Map(
      "escape" -> 1,
      "backspace" -> 14,
      "tab" -> 15,
      "enter" -> 28,
      "left-control" -> 29,
      "left-shift" -> 42,
      "space" -> 57,
      "up" -> 200,
      "left" -> 203,
      "right" -> 205,
      "down" -> 208,
      "delete" -> 211
    )
  }
}
