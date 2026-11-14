package ocelot.harness.core.runtime

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}

import scala.util.control.NonFatal

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.RecordingInvalid
import ocelot.harness.core.project.ValidatedProject
import ocelot.harness.core.workspace.{RecordingStatus, SnapshotName, UserInput}

/** Something the harness did to the world during a recording, replayed at the same tick. */
private[runtime] sealed trait RecordedInput extends Product with Serializable

private[runtime] object RecordedInput {
  final case class Screen(screenId: String, input: UserInput) extends RecordedInput
  final case class Redstone(computerId: String, side: String, value: Int) extends RecordedInput
  final case class MachineCommand(command: String, computerId: String) extends RecordedInput
  final case class Eject(driveId: String) extends RecordedInput
  final case class ServerTps(tps: Int) extends RecordedInput
}

/** A recording: a save state (the snapshot of the same name), every input with the tick it came at,
  * and a fingerprint of each tick that followed.
  */
private[runtime] final case class RecordingData(
    name: SnapshotName,
    startTick: Long,
    serverTps: Int,
    inputs: Vector[(Long, RecordedInput)],
    fingerprints: Vector[String]
) {
  def ticks: Long = fingerprints.size.toLong
}

/** Stores a recording as `recording.txt` beside the snapshot that starts it. */
private[runtime] object RecordingFile {
  val FileName = "recording.txt"
  private val Header = "ocelot-harness-recording 1"
  private val MaxBytes = 64L * 1024L * 1024L

  def write(project: ValidatedProject, data: RecordingData): Either[HarnessError, Unit] =
    directory(project, data.name).flatMap { dir =>
      try {
        val lines = Vector.newBuilder[String]
        lines += Header
        lines += s"start ${data.startTick}"
        lines += s"serverTps ${data.serverTps}"
        data.inputs.foreach { case (tick, input) => lines += s"input $tick ${encode(input)}" }
        data.fingerprints.zipWithIndex.foreach { case (fingerprint, index) =>
          lines += s"tick ${index + 1} ${escape(fingerprint)}"
        }
        val temporary = Files.createTempFile(dir, ".recording-", ".tmp")
        try {
          Files.write(
            temporary,
            lines.result().mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
          )
          Files.move(
            temporary,
            dir.resolve(FileName),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
          Right(())
        } finally Files.deleteIfExists(temporary)
      } catch {
        case NonFatal(error) => Left(RecordingInvalid(s"could not write the recording: $error"))
      }
    }

  def read(project: ValidatedProject, name: SnapshotName): Either[HarnessError, RecordingData] =
    directory(project, name).flatMap { dir =>
      val file = dir.resolve(FileName)
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        Left(RecordingInvalid(s"${name.value} is a snapshot without a recording"))
      else if (Files.size(file) > MaxBytes)
        Left(RecordingInvalid(s"${name.value}'s recording exceeds $MaxBytes bytes"))
      else
        try {
          val lines =
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split('\n').toVector
          if (lines.headOption.forall(_ != Header))
            Left(RecordingInvalid(s"${name.value}'s recording has an unknown format"))
          else {
            var start = 0L
            var serverTps = 20
            val inputs = Vector.newBuilder[(Long, RecordedInput)]
            val fingerprints = Vector.newBuilder[String]
            lines.tail.filter(_.nonEmpty).foreach { line =>
              line.split(' ').toVector match {
                case Vector("start", value)     => start = value.toLong
                case Vector("serverTps", value) => serverTps = value.toInt
                case "input" +: tick +: fields  => inputs += tick.toLong -> decode(fields)
                case Vector("tick", _, value)   => fingerprints += unescape(value)
                case _ => throw new IllegalArgumentException(s"unreadable line: ${line.take(80)}")
              }
            }
            Right(RecordingData(name, start, serverTps, inputs.result(), fingerprints.result()))
          }
        } catch {
          case NonFatal(error) =>
            Left(RecordingInvalid(s"${name.value}'s recording is unreadable: ${error.getMessage}"))
        }
    }

  def exists(project: ValidatedProject, name: SnapshotName): Boolean =
    directory(project, name).exists(dir => Files.isRegularFile(dir.resolve(FileName)))

  private def directory(
      project: ValidatedProject,
      name: SnapshotName
  ): Either[HarnessError, Path] = {
    val root = project.paths.snapshots.toAbsolutePath.normalize()
    val dir = root.resolve(name.value).normalize()
    if (!dir.startsWith(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
      Left(RecordingInvalid(s"no snapshot named ${name.value}"))
    else Right(dir)
  }

  private def encode(input: RecordedInput): String = {
    val fields: Vector[Any] = input match {
      case RecordedInput.Screen(screen, value) =>
        Vector("screen", screen) ++ (value match {
          case UserInput.KeyDown(key, character, user) =>
            Vector("key_down", key, character.toInt, user)
          case UserInput.KeyUp(key, character, user) => Vector("key_up", key, character.toInt, user)
          case UserInput.TypeText(text, ticks, user) => Vector("type", text, ticks, user)
          case UserInput.Paste(text, user)           => Vector("paste", text, user)
          case UserInput.Touch(x, y, button, user)   => Vector("touch", x, y, button, user)
          case UserInput.Drag(fx, fy, tx, ty, button, steps, user) =>
            Vector("drag", fx, fy, tx, ty, button, steps, user)
          case UserInput.Drop(x, y, button, user)  => Vector("drop", x, y, button, user)
          case UserInput.Scroll(x, y, delta, user) => Vector("scroll", x, y, delta, user)
        })
      case RecordedInput.Redstone(computer, side, value) =>
        Vector("redstone", computer, side, value)
      case RecordedInput.MachineCommand(command, computer) => Vector("machine", command, computer)
      case RecordedInput.Eject(drive)                      => Vector("eject", drive)
      case RecordedInput.ServerTps(tps)                    => Vector("serverTps", tps)
    }
    fields.map(value => escape(value.toString)).mkString(" ")
  }

  private def decode(raw: Vector[String]): RecordedInput = {
    val f = raw.map(unescape)
    f match {
      case "screen" +: screen +: rest =>
        val input = rest match {
          case Vector("key_down", key, character, user) =>
            UserInput.KeyDown(key, character.toInt.toChar, user)
          case Vector("key_up", key, character, user) =>
            UserInput.KeyUp(key, character.toInt.toChar, user)
          case Vector("type", text, ticks, user) => UserInput.TypeText(text, ticks.toInt, user)
          case Vector("paste", text, user)       => UserInput.Paste(text, user)
          case Vector("touch", x, y, button, user) =>
            UserInput.Touch(x.toDouble, y.toDouble, button.toInt, user)
          case Vector("drag", fx, fy, tx, ty, button, steps, user) =>
            UserInput.Drag(
              fx.toDouble,
              fy.toDouble,
              tx.toDouble,
              ty.toDouble,
              button.toInt,
              steps.toInt,
              user
            )
          case Vector("drop", x, y, button, user) =>
            UserInput.Drop(x.toDouble, y.toDouble, button.toInt, user)
          case Vector("scroll", x, y, delta, user) =>
            UserInput.Scroll(x.toDouble, y.toDouble, delta.toInt, user)
          case other =>
            throw new IllegalArgumentException(s"unknown screen input ${other.headOption}")
        }
        RecordedInput.Screen(screen, input)
      case Vector("redstone", computer, side, value) =>
        RecordedInput.Redstone(computer, side, value.toInt)
      case Vector("machine", command, computer) => RecordedInput.MachineCommand(command, computer)
      case Vector("eject", drive)               => RecordedInput.Eject(drive)
      case Vector("serverTps", tps)             => RecordedInput.ServerTps(tps.toInt)
      case other => throw new IllegalArgumentException(s"unknown input ${other.headOption}")
    }
  }

  private def escape(value: String): String = URLEncoder.encode(value, "UTF-8")
  private def unescape(value: String): String = URLDecoder.decode(value, "UTF-8")
}

/** The recording being made: inputs and per-tick fingerprints, bounded. */
private[runtime] final class ActiveRecording(
    val name: SnapshotName,
    val startTick: Long,
    val serverTps: Int
) {
  private val inputs = Vector.newBuilder[(Long, RecordedInput)]
  private val fingerprints = Vector.newBuilder[String]
  private var inputCount = 0
  private var tickCount = 0L
  private var full = false

  def input(tick: Long, value: RecordedInput): Unit =
    if (!full) {
      if (inputCount >= ActiveRecording.MaxInputs) full = true
      else {
        inputs += tick -> value
        inputCount += 1
      }
    }

  def tick(fingerprint: String): Unit =
    if (!full) {
      if (tickCount >= ActiveRecording.MaxTicks) full = true
      else {
        fingerprints += fingerprint
        tickCount += 1L
      }
    }

  def status(active: Boolean): RecordingStatus =
    RecordingStatus(name.value, active, startTick, tickCount, inputCount, full)

  def data: RecordingData =
    RecordingData(name, startTick, serverTps, inputs.result(), fingerprints.result())
}

private[runtime] object ActiveRecording {
  // About 14 hours at 20 TPS; a fingerprint line is under 100 bytes.
  val MaxTicks = 1000000L
  val MaxInputs = 100000
}

/** Compares a replay's ticks with the recording's. */
private[runtime] final class ReplayCursor(val data: RecordingData) {
  var replayed = 0L
  var divergence: Option[(Long, String)] = None

  def tick(fingerprint: String): Unit = {
    if (divergence.isEmpty && replayed < data.ticks) {
      val expected = data.fingerprints(replayed.toInt)
      if (expected != fingerprint)
        divergence = Some(
          (data.startTick + replayed + 1L, ReplayCursor.explain(expected, fingerprint))
        )
    }
    replayed += 1L
  }

  def done: Boolean = replayed >= data.ticks
}

private[runtime] object ReplayCursor {

  /** Says what differs: the trace of calls and signals, or a computer's state or memory. */
  def explain(expected: String, actual: String): String = {
    val was = expected.split(' ').toVector
    val now = actual.split(' ').toVector
    val trace =
      if (was.headOption != now.headOption) Vector("calls, signals or inputs differ")
      else Vector.empty
    val machines = (was.drop(1) ++ now.drop(1)).map(_.takeWhile(_ != '=')).distinct.flatMap { id =>
      val before = was.drop(1).find(_.startsWith(s"$id=")).map(_.drop(id.length + 1))
      val after = now.drop(1).find(_.startsWith(s"$id=")).map(_.drop(id.length + 1))
      if (before == after) None
      else Some(s"$id was ${before.getOrElse("absent")}, now ${after.getOrElse("absent")}")
    }
    (trace ++ machines).mkString("; ")
  }
}
