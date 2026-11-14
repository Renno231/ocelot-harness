package ocelot.harness.core.runtime

import java.lang.management.ManagementFactory
import java.nio.file.Files

import javax.management.ObjectName

import scala.util.control.NonFatal

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.SessionOperationFailed

/** The JVM's own flight recorder for the service process, driven through its diagnostic commands
  * (the same ones `jcmd JFR.start` uses), so no launch flags are needed.
  */
private[runtime] object JvmFlightRecording {
  private val Name = "ocelot-harness"

  /** Recordings keep their newest data within the artifact size limit. */
  val MaxBytes: Long = 16L * 1024L * 1024L

  def start(): Either[HarnessError, Unit] =
    command("jfrStart", s"name=$Name", "settings=profile", s"maxsize=$MaxBytes").map(_ => ())

  /** Ends the recording and returns its bytes. */
  def stop(): Either[HarnessError, Array[Byte]] = {
    val file = Files.createTempFile("ocelot-harness-", ".jfr")
    try
      command("jfrStop", s"name=$Name", s"filename=$file").flatMap { _ =>
        if (Files.size(file) > MaxBytes)
          Left(SessionOperationFailed(s"JVM recording exceeds $MaxBytes bytes"))
        else Right(Files.readAllBytes(file))
      }
    finally Files.deleteIfExists(file)
  }

  private def command(operation: String, arguments: String*): Either[HarnessError, String] =
    try {
      val result = ManagementFactory.getPlatformMBeanServer.invoke(
        new ObjectName("com.sun.management:type=DiagnosticCommand"),
        operation,
        Array[AnyRef](arguments.toArray),
        Array(classOf[Array[String]].getName)
      )
      Right(String.valueOf(result))
    } catch {
      case NonFatal(error) =>
        val cause =
          Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null).toVector.last
        Left(
          SessionOperationFailed(
            s"JVM flight recording: ${Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName).trim}"
          )
        )
    }
}
