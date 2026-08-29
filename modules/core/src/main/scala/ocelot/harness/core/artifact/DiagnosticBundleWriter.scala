package ocelot.harness.core.artifact

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.util.control.NonFatal

import javax.imageio.ImageIO

import ocelot.harness.core.{BuildIdentity, HarnessError}
import ocelot.harness.core.HarnessError.{ArtifactLimitExceeded, DiagnosticFailed}
import ocelot.harness.core.project.ValidatedProject
import ocelot.harness.core.workspace._
private[harness] object DiagnosticBundleWriter {
  private val HardMaxDiagnosticBytes = 16L * 1024L * 1024L

  def collect(
      project: ValidatedProject,
      description: WorkspaceDescription,
      brainVersion: String,
      events: EventSnapshot,
      screens: Map[ocelot.harness.core.project.ScreenId, ScreenSnapshot],
      lastRun: Option[RunResult],
      request: DiagnosticRequest,
      store: ArtifactStore
  ): Either[HarnessError, DiagnosticBundle] = {
    validate(request).flatMap { _ =>
      try {
        val entries = Vector.newBuilder[(String, Array[Byte])]
        entries += "manifest.conf" -> utf8(sanitizedManifest(project))
        entries += "versions.txt" -> utf8(
          Vector(
            s"harness=${BuildIdentity.HarnessVersion}",
            s"brain=$brainVersion",
            s"brainCommit=${BuildIdentity.BrainCommit}",
            s"schema=${project.schemaVersion}",
            s"protocol=${BuildIdentity.ProtocolVersion}"
          ).mkString("\n") + "\n"
        )
        entries += "runtime.txt" -> utf8(runtimeSummary(project))
        entries += "topology.txt" -> utf8(topologySummary(description))
        entries += "events.txt" -> utf8(eventsSummary(project, events))
        entries += "timeline.txt" -> utf8(timelineSummary(lastRun))
        entries += "error.txt" -> utf8(errorSummary(project, request.failure))
        screens.toVector.sortBy(_._1.value).foreach { case (id, snapshot) =>
          entries += s"screens/${id.value}.txt" -> utf8(snapshot.text + "\n")
          entries += s"screens/${id.value}.cells.json" -> utf8(
            ScreenArtifactWriter.cellsJson(snapshot)
          )
          val image = ScreenRenderer
            .render(snapshot)
            .fold(error => throw new DiagnosticException(error.message), identity)
          val png = new ByteArrayOutputStream()
          if (!ImageIO.write(image, "png", png))
            throw new DiagnosticException("PNG writer is unavailable")
          entries += s"screens/${id.value}.png" -> png.toByteArray
        }
        val content = entries.result().sortBy(_._1)
        val checksums = content
          .map { case (name, bytes) => s"${sha256(bytes)}  $name" }
          .mkString("\n") + "\n"
        val complete = content :+ ("checksums.txt" -> utf8(checksums))
        val uncompressedSize = complete.foldLeft(0L)(_ + _._2.length)
        if (uncompressedSize > request.maxBytes) {
          Left(ArtifactLimitExceeded(s"diagnostic content exceeds byte limit ${request.maxBytes}"))
        } else {
          val archive = zip(complete)
          if (archive.length.toLong > request.maxBytes) {
            Left(
              ArtifactLimitExceeded(s"diagnostic archive exceeds byte limit ${request.maxBytes}")
            )
          } else {
            store
              .writeBytes(request.relativePath, archive, "application/zip")
              .map(artifact => DiagnosticBundle(artifact, complete.map(_._1)))
          }
        }
      } catch {
        case error: DiagnosticException => Left(DiagnosticFailed(error.getMessage))
        case NonFatal(error)            => Left(DiagnosticFailed(errorMessage(error)))
      }
    }
  }

  private def validate(request: DiagnosticRequest): Either[HarnessError, Unit] = {
    if (request == null) Left(DiagnosticFailed("diagnostic request is required"))
    else if (request.relativePath == null || request.relativePath.trim.isEmpty)
      Left(DiagnosticFailed("diagnostic artifact path is required"))
    else if (
      request.failure == null || request.failure.exists(value =>
        value == null || value.code == null || value.code.trim.isEmpty || value.message == null ||
          value.stackTrace == null || value.stackTrace.exists(_ == null)
      )
    ) Left(DiagnosticFailed("diagnostic failure context is invalid"))
    else if (request.maxBytes <= 0L)
      Left(DiagnosticFailed("diagnostic byte limit must be positive"))
    else if (request.maxBytes > HardMaxDiagnosticBytes)
      Left(DiagnosticFailed(s"diagnostic byte limit must not exceed $HardMaxDiagnosticBytes"))
    else Right(())
  }

  private def sanitizedManifest(project: ValidatedProject): String = {
    val root = project.paths.projectRoot.toString
    val text = new String(Files.readAllBytes(project.paths.manifest), StandardCharsets.UTF_8)
    text
      .split("\\r?\\n", -1)
      .map { line =>
        val lower = line.toLowerCase
        if (
          line.contains("=") &&
          Vector("token", "secret", "password", "source", "directory", "allowroot", "allow-root")
            .exists(lower.contains)
        ) {
          line.substring(0, line.indexOf('=') + 1) + " \"<redacted>\""
        } else line.replace(root, "<project-root>")
      }
      .mkString("\n")
  }

  private def runtimeSummary(project: ValidatedProject): String =
    Vector(
      s"project=${project.id.value}",
      s"tickRate=${project.runtime.tickRate}",
      s"internetHttp=${project.runtime.internet.httpEnabled}",
      s"internetTcp=${project.runtime.internet.tcpEnabled}",
      s"defaultMaxTicks=${project.runtime.limits.defaultMaxTicks}",
      s"defaultMaxWallTimeMillis=${project.runtime.limits.defaultMaxWallTimeMillis}",
      s"eventBufferSize=${project.runtime.limits.eventBufferSize}"
    ).mkString("\n") + "\n"

  private def topologySummary(description: WorkspaceDescription): String = {
    val computers = description.computers.sortBy(_.id.value).map { computer =>
      val disks =
        computer.disks.map(disk => s"${disk.id.value}:${disk.label}:${disk.access}").mkString(",")
      s"computer=${computer.id.value} stateAddress=${computer.runtimeAddress} disks=$disks"
    }
    val screens = description.screens
      .sortBy(_.id.value)
      .map(screen =>
        s"screen=${screen.id.value} tier=${screen.tier} address=${screen.runtimeAddress}"
      )
    (computers ++ screens).mkString("\n") + "\n"
  }

  private def eventsSummary(project: ValidatedProject, snapshot: EventSnapshot): String = {
    val lines = snapshot.events.map { event =>
      val message = redact(project, event.message.getOrElse(""))
      s"${event.sequence}\t${event.kind}\t${event.sourceAddress.getOrElse("")}\t$message"
    }
    (Vector(s"dropped=${snapshot.droppedCount}") ++ lines).mkString("\n") + "\n"
  }

  private def errorSummary(
      project: ValidatedProject,
      failure: Option[DiagnosticFailure]
  ): String = failure match {
    case None => "unavailable\n"
    case Some(value) =>
      Vector(
        s"code=${value.code}",
        s"message=${redact(project, value.message)}",
        "stackTrace=",
        value.stackTrace.map(redact(project, _)).getOrElse("unavailable")
      ).mkString("\n") + "\n"
  }

  private def redact(project: ValidatedProject, value: String): String = {
    val replacements =
      Vector(project.paths.projectRoot -> "<project-root>") ++
        project.computers.flatMap(_.hardware.disks).map(_.source -> "<allowed-root>") ++
        Vector(
          project.paths.artifacts -> "<artifact-root>",
          project.paths.snapshots -> "<snapshot-root>"
        )
    replacements
      .flatMap { case (path, replacement) =>
        val native = path.toString
        Vector(native, native.replace('\\', '/')).distinct.map(_ -> replacement)
      }
      .sortBy { case (path, _) => -path.length }
      .foldLeft(value) { case (redacted, (path, replacement)) =>
        redacted.replace(path, replacement)
      }
  }

  private def timelineSummary(lastRun: Option[RunResult]): String = lastRun match {
    case None => "unavailable\n"
    case Some(result) =>
      val header = Vector(
        s"stopReason=${result.stopReason}",
        s"elapsedTicks=${result.elapsedTicks}",
        s"elapsedWallNanos=${result.elapsedWallTime.toNanos}",
        s"dropped=${result.timelineDroppedCount}"
      )
      val observations = result.timeline.map(value =>
        s"${value.elapsedTicks}\t${value.elapsedWallTime.toNanos}\t${value.screenRevisions.toVector.sortBy(_._1.value).map { case (id, revision) => s"${id.value}:$revision" }.mkString(",")}"
      )
      (header ++ observations).mkString("\n") + "\n"
  }

  private def zip(entries: Vector[(String, Array[Byte])]): Array[Byte] = {
    val output = new ByteArrayOutputStream()
    val archive = new ZipOutputStream(output, StandardCharsets.UTF_8)
    try {
      entries.foreach { case (name, bytes) =>
        val entry = new ZipEntry(name)
        entry.setTime(0L)
        archive.putNextEntry(entry)
        archive.write(bytes)
        archive.closeEntry()
      }
    } finally archive.close()
    output.toByteArray
  }

  private def utf8(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final class DiagnosticException(message: String) extends RuntimeException(message)
}
