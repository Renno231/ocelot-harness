package ocelot.harness.core.project

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths}
import java.util.UUID

import scala.collection.mutable.Builder
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import com.typesafe.config.{Config, ConfigFactory, ConfigUtil}

private[harness] final case class DesktopImportMetadata(
    sourceSha256: String,
    workspaceSha256: String,
    computers: Map[String, String],
    screens: Map[String, String],
    keyboards: Map[String, String],
    diskBindings: Map[String, String]
)

private[harness] object DesktopImportMetadata {
  private val MaxMetadataBytes = 1024L * 1024L

  def read(projectRoot: Path): Either[ProjectErrors, DesktopImportMetadata] = {
    val path = projectRoot.resolve(DesktopWorkspaceProjects.MetadataRelativePath).normalize()
    try {
      if (!path.startsWith(projectRoot) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        failure("desktop-import.conf", "import_metadata_invalid", "import metadata is required")
      } else if (Files.size(path) > MaxMetadataBytes) {
        failure("desktop-import.conf", "import_metadata_invalid", "import metadata is too large")
      } else {
        val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        if (text.linesIterator.exists(_.trim.startsWith("include")) || text.contains("${")) {
          failure(
            "desktop-import.conf",
            "import_metadata_invalid",
            "metadata includes and substitutions are forbidden"
          )
        } else parse(ConfigFactory.parseString(text).resolve())
      }
    } catch {
      case NonFatal(error) =>
        failure("desktop-import.conf", "import_metadata_invalid", errorMessage(error))
    }
  }

  private def parse(config: Config): Either[ProjectErrors, DesktopImportMetadata] = {
    val errors = Vector.newBuilder[ProjectError]
    def mapping(path: String): Map[String, String] = {
      if (!config.hasPath(path)) {
        errors += ProjectError(path, "import_metadata_invalid", "mapping is required")
        Map.empty
      } else {
        config
          .getObject(path)
          .keySet()
          .asScala
          .toVector
          .sorted
          .map { key =>
            key -> config.getString(s"$path.${ConfigUtil.quoteString(key)}")
          }
          .toMap
      }
    }
    val allowedKeys = Set(
      "formatVersion",
      "sourceSha256",
      "workspaceSha256",
      "computers",
      "screens",
      "keyboards",
      "diskBindings"
    )
    config.root().keySet().asScala.toVector.sorted.filterNot(allowedKeys).foreach { key =>
      errors += ProjectError(key, "import_metadata_invalid", "unknown key")
    }
    if (!config.hasPath("formatVersion") || config.getInt("formatVersion") != 1) {
      errors += ProjectError("formatVersion", "import_metadata_invalid", "format version must be 1")
    }
    val sourceSha256 =
      if (config.hasPath("sourceSha256")) config.getString("sourceSha256") else ""
    if (!sourceSha256.matches("[0-9a-f]{64}")) {
      errors += ProjectError(
        "sourceSha256",
        "import_metadata_invalid",
        "source checksum must be lowercase SHA-256"
      )
    }
    val workspaceSha256 =
      if (config.hasPath("workspaceSha256")) config.getString("workspaceSha256") else ""
    if (!workspaceSha256.matches("[0-9a-f]{64}")) {
      errors += ProjectError(
        "workspaceSha256",
        "import_metadata_invalid",
        "workspace checksum must be lowercase SHA-256"
      )
    }
    val computers = mapping("computers")
    val screens = mapping("screens")
    val keyboards = mapping("keyboards")
    val disks = mapping("diskBindings")
    (computers.keys ++ screens.keys ++ keyboards.values ++ disks.keys).foreach { value =>
      try UUID.fromString(value)
      catch {
        case NonFatal(_) =>
          errors += ProjectError(
            "desktop-import.conf",
            "import_metadata_invalid",
            s"invalid entity UUID: $value"
          )
      }
    }
    computers.values.foreach(value => validateId("computers", value, ComputerId.parse, errors))
    screens.values.foreach(value => validateId("screens", value, ScreenId.parse, errors))
    keyboards.keys.foreach(value => validateId("keyboards", value, ScreenId.parse, errors))
    if (computers.values.toVector.distinct.size != computers.size) {
      errors += ProjectError("computers", "import_metadata_invalid", "computer IDs must be unique")
    }
    if (screens.values.toVector.distinct.size != screens.size) {
      errors += ProjectError("screens", "import_metadata_invalid", "screen IDs must be unique")
    }
    disks.values.foreach { relative =>
      val value = Paths.get(relative)
      if (value.isAbsolute || value.normalize().startsWith("..")) {
        errors += ProjectError(
          "diskBindings",
          "import_metadata_invalid",
          s"invalid relative path: $relative"
        )
      }
    }
    val result = errors.result()
    if (result.nonEmpty) Left(ProjectErrors.from(result))
    else
      Right(
        DesktopImportMetadata(
          sourceSha256,
          workspaceSha256,
          computers,
          screens,
          keyboards,
          disks
        )
      )
  }

  private def validateId[A](
      path: String,
      value: String,
      parse: String => Either[String, A],
      errors: Builder[ProjectError, Vector[ProjectError]]
  ): Unit = parse(value).left.foreach(message =>
    errors += ProjectError(path, "import_metadata_invalid", message)
  )

  private def failure[A](path: String, code: String, message: String): Left[ProjectErrors, A] =
    Left(ProjectErrors.from(Vector(ProjectError(path, code, message))))

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
