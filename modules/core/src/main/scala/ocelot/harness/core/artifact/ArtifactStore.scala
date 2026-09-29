package ocelot.harness.core.artifact

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}
import java.security.MessageDigest

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import javax.imageio.stream.ImageOutputStream

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.{
  ArtifactLimitExceeded,
  ArtifactWriteFailed,
  InvalidArtifactPath
}

final case class ArtifactDescription(
    relativePath: String,
    size: Long,
    mediaType: String,
    sha256: String
)

private final class ArtifactPathException(message: String) extends RuntimeException(message)

private[harness] final class ArtifactStore(
    root: Path,
    maxArtifactBytes: Long,
    allowedRoot: Path
) {
  require(root != null, "artifact root is required")
  require(maxArtifactBytes > 0L, "artifact byte limit must be positive")
  require(allowedRoot != null, "artifact allowed root is required")

  private val normalizedRoot = root.toAbsolutePath.normalize()
  private val normalizedAllowedRoot = allowedRoot.toAbsolutePath.normalize()

  def writePng(
      relativePath: String,
      image: BufferedImage
  ): Either[HarnessError, ArtifactDescription] = {
    if (image == null) Left(ArtifactWriteFailed("image is required"))
    else {
      try {
        val writers = ImageIO.getImageWritersByFormatName("png")
        if (!writers.hasNext) Left(ArtifactWriteFailed("PNG writer is unavailable"))
        else {
          val writer = writers.next()
          val output = new ByteArrayOutputStream()
          var imageOutput = Option.empty[ImageOutputStream]
          try {
            imageOutput = Option(ImageIO.createImageOutputStream(output))
            imageOutput match {
              case None => Left(ArtifactWriteFailed("PNG writer is unavailable"))
              case Some(stream) =>
                writer.setOutput(stream)
                val parameters = writer.getDefaultWriteParam
                if (parameters.canWriteCompressed) {
                  parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
                  parameters.setCompressionQuality(0.0f)
                }
                writer.write(null, new IIOImage(image, null, null), parameters)
                stream.flush()
                writeBytes(relativePath, output.toByteArray, "image/png")
            }
          } finally {
            try imageOutput.foreach(_.close())
            finally {
              try writer.dispose()
              finally output.close()
            }
          }
        }
      } catch {
        case NonFatal(error) => Left(ArtifactWriteFailed(errorMessage(error)))
      }
    }
  }

  def writeBytes(
      relativePath: String,
      bytes: Array[Byte],
      mediaType: String
  ): Either[HarnessError, ArtifactDescription] = {
    if (bytes == null) Left(ArtifactWriteFailed("artifact bytes are required"))
    else if (mediaType == null || mediaType.trim.isEmpty)
      Left(ArtifactWriteFailed("artifact media type is required"))
    else if (bytes.length.toLong > maxArtifactBytes)
      Left(ArtifactLimitExceeded(s"artifact exceeds byte limit $maxArtifactBytes"))
    else {
      resolve(relativePath).flatMap { destination =>
        var temporary: Option[Path] = None
        try {
          val rootReal = createContainedRoot()
          val parentReal = createContainedParent(destination.getParent, rootReal)
          if (
            Files
              .exists(destination, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(destination)
          ) {
            Left(InvalidArtifactPath("artifact destination must not be a symbolic link"))
          } else {
            val temp = Files.createTempFile(parentReal, ".ocelot-artifact-", ".tmp")
            temporary = Some(temp)
            Files.write(temp, bytes)
            moveAtomically(temp, destination)
            temporary = None
            val portable = rootReal
              .relativize(destination.toRealPath())
              .iterator()
              .asScala
              .map(_.toString)
              .mkString("/")
            Right(
              ArtifactDescription(
                portable,
                bytes.length.toLong,
                mediaType,
                sha256(bytes)
              )
            )
          }
        } catch {
          case error: ArtifactPathException => Left(InvalidArtifactPath(error.getMessage))
          case NonFatal(error)              => Left(ArtifactWriteFailed(errorMessage(error)))
        } finally {
          temporary.foreach(path =>
            try Files.deleteIfExists(path)
            catch {
              case NonFatal(_) =>
            }
          )
        }
      }
    }
  }

  private def resolve(relativePath: String): Either[HarnessError, Path] = {
    if (relativePath == null || relativePath.trim.isEmpty) {
      Left(InvalidArtifactPath("artifact path is required"))
    } else {
      try {
        val relative = Paths.get(relativePath)
        val normalized = relative.normalize()
        if (
          relative.isAbsolute || normalized.getNameCount == 0 || normalized.startsWith("..") ||
          normalized
            .iterator()
            .asScala
            .exists(part => part.toString == "." || part.toString.isEmpty)
        ) {
          Left(InvalidArtifactPath("artifact path must be a contained relative path"))
        } else {
          val destination = normalizedRoot.resolve(normalized).normalize()
          if (destination == normalizedRoot || !destination.startsWith(normalizedRoot))
            Left(InvalidArtifactPath("artifact path escapes the artifact root"))
          else Right(destination)
        }
      } catch {
        case NonFatal(error) => Left(InvalidArtifactPath(errorMessage(error)))
      }
    }
  }

  private def createContainedRoot(): Path = {
    val allowedReal = normalizedAllowedRoot.toRealPath()
    if (!normalizedRoot.startsWith(normalizedAllowedRoot)) {
      throw new ArtifactPathException("artifact root escapes the allowed root")
    }
    var current = normalizedAllowedRoot
    normalizedAllowedRoot.relativize(normalizedRoot).iterator().asScala.foreach { part =>
      current = current.resolve(part)
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        val currentReal = current.toRealPath()
        if (!Files.isDirectory(currentReal) || !currentReal.startsWith(allowedReal)) {
          throw new ArtifactPathException("artifact root escapes the allowed root")
        }
      } else Files.createDirectory(current)
    }
    val rootReal = normalizedRoot.toRealPath()
    if (!rootReal.startsWith(allowedReal)) {
      throw new ArtifactPathException("artifact root escapes the allowed root")
    }
    rootReal
  }

  private def createContainedParent(parent: Path, rootReal: Path): Path = {
    var current = normalizedRoot
    val parts = normalizedRoot.relativize(parent).iterator().asScala
    parts.foreach { part =>
      current = current.resolve(part)
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        val currentReal = current.toRealPath()
        if (!Files.isDirectory(currentReal) || !currentReal.startsWith(rootReal)) {
          throw new ArtifactPathException("artifact path escapes the artifact root")
        }
      } else {
        Files.createDirectory(current)
      }
    }
    val parentReal = parent.toRealPath()
    if (!parentReal.startsWith(rootReal)) {
      throw new ArtifactPathException("artifact path escapes the artifact root")
    }
    parentReal
  }

  private def moveAtomically(source: Path, destination: Path): Unit =
    Files.move(
      source,
      destination,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(value => f"${value & 0xff}%02x")
      .mkString

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
