package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import com.typesafe.config.{ConfigFactory, ConfigUtil}
import totoro.ocelot.brain.nbt.{CompressedStreamTools, NBTReadLimiter, NBTTagCompound}

import ocelot.harness.core.{BuildIdentity, HarnessError}
import ocelot.harness.core.HarnessError._
import ocelot.harness.core.project.ValidatedProject
import ocelot.harness.core.workspace._

private[runtime] final case class SnapshotIdentity(
    computers: Map[String, String],
    screens: Map[String, String],
    keyboards: Map[String, String]
)

private[runtime] final case class LoadedSnapshot(
    nbt: NBTTagCompound,
    identity: SnapshotIdentity,
    description: SnapshotDescription
)

private[runtime] object SnapshotStore {
  val FormatVersion = 1
  private val WorkspaceFile = "workspace.nbt.gz"
  private val MetadataFile = "metadata.conf"
  private val MaxMetadataBytes = 1024L * 1024L
  private val HardMaxSnapshotBytes = 64L * 1024L * 1024L

  def save(
      project: ValidatedProject,
      constructed: ConstructedWorkspace,
      workspace: totoro.ocelot.brain.workspace.Workspace,
      brainVersion: String,
      request: SnapshotRequest
  ): Either[HarnessError, SnapshotDescription] = {
    validateRequest(request).flatMap { _ =>
      val root = project.paths.snapshots.toAbsolutePath.normalize()
      val destination = root.resolve(request.name.value).normalize()
      var temporary: Option[Path] = None
      try {
        val rootReal = createContainedRoot(project.paths.projectRoot, root)
        if (!destination.startsWith(root) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          Left(SnapshotWriteFailed("snapshot already exists or escapes the snapshot root"))
        } else {
          val nbt = new NBTTagCompound()
          workspace.save(nbt)
          val bytes = CompressedStreamTools.write(nbt)
          if (bytes.length.toLong > request.maxBytes) {
            Left(
              SnapshotLimitExceeded(s"workspace snapshot exceeds byte limit ${request.maxBytes}")
            )
          } else {
            val temp = Files.createTempDirectory(rootReal, ".ocelot-snapshot-")
            temporary = Some(temp)
            val workspacePath = temp.resolve(WorkspaceFile)
            Files.write(
              workspacePath,
              bytes,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE
            )
            val copiedBytes = request.hostDisks match {
              case HostDiskSnapshotPolicy.ReferenceOnly => 0L
              case HostDiskSnapshotPolicy.Copy =>
                copyHostDisks(project, temp.resolve("disks"), request.maxBytes - bytes.length)
            }
            if (bytes.length.toLong + copiedBytes > request.maxBytes) {
              throw new SnapshotLimitException(
                s"snapshot exceeds byte limit ${request.maxBytes} while copying host disks"
              )
            }
            val metadata = renderMetadata(
              project,
              constructed,
              brainVersion,
              request.hostDisks,
              workspace.getIngameTime.toLong,
              bytes.length.toLong,
              copiedBytes,
              sha256(bytes)
            )
            val metadataBytes = metadata.getBytes(StandardCharsets.UTF_8)
            if (
              bytes.length.toLong + copiedBytes + metadataBytes.length.toLong > request.maxBytes
            ) {
              throw new SnapshotLimitException(s"snapshot exceeds byte limit ${request.maxBytes}")
            }
            Files.write(
              temp.resolve(MetadataFile),
              metadataBytes,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE
            )
            moveAtomically(temp, destination)
            temporary = None
            Right(
              SnapshotDescription(
                request.name,
                s"${request.name.value}/$WorkspaceFile",
                bytes.length.toLong,
                sha256(bytes),
                request.hostDisks,
                workspace.getIngameTime.toLong,
                copiedBytes
              )
            )
          }
        }
      } catch {
        case error: SnapshotLimitException => Left(SnapshotLimitExceeded(error.getMessage))
        case NonFatal(error)               => Left(SnapshotWriteFailed(errorMessage(error)))
      } finally temporary.foreach(deleteRecursively)
    }
  }

  def load(
      project: ValidatedProject,
      brainVersion: String,
      name: SnapshotName,
      maxBytes: Long = HardMaxSnapshotBytes
  ): Either[HarnessError, LoadedSnapshot] = {
    if (name == null) Left(SnapshotInvalid("snapshot name is required"))
    else {
      val root = project.paths.snapshots.toAbsolutePath.normalize()
      val directory = root.resolve(name.value).normalize()
      try {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          Left(SnapshotInvalid(s"snapshot does not exist: ${name.value}"))
        } else {
          val allowedReal = project.paths.projectRoot.toRealPath()
          val rootReal = root.toRealPath()
          val directoryReal = directory.toRealPath()
          if (!rootReal.startsWith(allowedReal) || !directoryReal.startsWith(rootReal)) {
            Left(SnapshotInvalid("snapshot path escapes the snapshot root"))
          } else {
            readBounded(directoryReal.resolve(MetadataFile), MaxMetadataBytes).flatMap {
              metadataBytes =>
                parseMetadata(project, brainVersion, name, metadataBytes).flatMap {
                  case (identity, description) =>
                    readBounded(directoryReal.resolve(WorkspaceFile), maxBytes).flatMap { bytes =>
                      if (bytes.length.toLong != description.size) {
                        Left(SnapshotCorrupt("workspace size does not match snapshot metadata"))
                      } else if (sha256(bytes) != description.sha256) {
                        Left(SnapshotCorrupt("workspace checksum does not match snapshot metadata"))
                      } else {
                        try {
                          val nbt = CompressedStreamTools.read(bytes, new NBTReadLimiter(maxBytes))
                          Right(LoadedSnapshot(nbt, identity, description))
                        } catch {
                          case NonFatal(error) => Left(SnapshotCorrupt(errorMessage(error)))
                        }
                      }
                    }
                }
            }
          }
        }
      } catch {
        case NonFatal(error) => Left(SnapshotReadFailed(errorMessage(error)))
      }
    }
  }

  private def validateRequest(request: SnapshotRequest): Either[HarnessError, Unit] = {
    if (request == null) Left(SnapshotInvalid("snapshot request is required"))
    else if (request.name == null) Left(SnapshotInvalid("snapshot name is required"))
    else if (request.hostDisks == null) Left(SnapshotInvalid("host disk policy is required"))
    else if (request.maxBytes <= 0L) Left(SnapshotInvalid("snapshot byte limit must be positive"))
    else if (request.maxBytes > HardMaxSnapshotBytes)
      Left(SnapshotInvalid(s"snapshot byte limit must not exceed $HardMaxSnapshotBytes"))
    else Right(())
  }

  private def renderMetadata(
      project: ValidatedProject,
      constructed: ConstructedWorkspace,
      brainVersion: String,
      hostDisks: HostDiskSnapshotPolicy,
      captureTick: Long,
      workspaceSize: Long,
      copiedDiskBytes: Long,
      workspaceSha256: String
  ): String = {
    val computers = constructed.description.computers.sortBy(_.id.value).map { value =>
      s"  ${ConfigUtil.quoteString(value.id.value)}=${ConfigUtil.quoteString(value.runtimeAddress)}"
    }
    val screens = constructed.description.screens.sortBy(_.id.value).map { value =>
      val keyboard = value.keyboardRuntimeAddress
        .map(address => s", keyboard=${ConfigUtil.quoteString(address)}")
        .getOrElse("")
      s"  ${ConfigUtil.quoteString(value.id.value)} = { address=${ConfigUtil.quoteString(value.runtimeAddress)}$keyboard }"
    }
    Vector(
      s"formatVersion=$FormatVersion",
      s"harnessVersion=${ConfigUtil.quoteString(BuildIdentity.HarnessVersion)}",
      s"protocolVersion=${BuildIdentity.ProtocolVersion}",
      s"schemaVersion=${project.schemaVersion}",
      s"brainVersion=${ConfigUtil.quoteString(brainVersion)}",
      s"brainCommit=${ConfigUtil.quoteString(BuildIdentity.BrainCommit)}",
      s"projectId=${ConfigUtil.quoteString(project.id.value)}",
      s"manifestSha256=${ConfigUtil.quoteString(sha256(Files.readAllBytes(project.paths.manifest)))}",
      s"hostDisks=${ConfigUtil.quoteString(hostDisks.name)}",
      s"captureTick=$captureTick",
      s"workspaceSize=$workspaceSize",
      s"copiedDiskBytes=$copiedDiskBytes",
      s"workspaceSha256=${ConfigUtil.quoteString(workspaceSha256)}",
      "computers {",
      computers.mkString("\n"),
      "}",
      "screens {",
      screens.mkString("\n"),
      "}",
      ""
    ).mkString("\n")
  }

  private def parseMetadata(
      project: ValidatedProject,
      brainVersion: String,
      name: SnapshotName,
      bytes: Array[Byte]
  ): Either[HarnessError, (SnapshotIdentity, SnapshotDescription)] = {
    try {
      val metadata = new String(bytes, StandardCharsets.UTF_8)
      if (metadata.linesIterator.exists(_.trim.startsWith("include"))) {
        throw new IllegalArgumentException("snapshot metadata must not contain includes")
      }
      val config = ConfigFactory.parseString(metadata).resolve()
      val compatibility = Vector(
        "formatVersion" -> (config.getInt("formatVersion") == FormatVersion),
        "harnessVersion" -> (config.getString("harnessVersion") == BuildIdentity.HarnessVersion),
        "protocolVersion" -> (config.getInt("protocolVersion") == BuildIdentity.ProtocolVersion),
        "schemaVersion" -> (config.getInt("schemaVersion") == project.schemaVersion),
        "brainVersion" -> (config.getString("brainVersion") == brainVersion),
        "brainCommit" -> (config.getString("brainCommit") == BuildIdentity.BrainCommit),
        "projectId" -> (config.getString("projectId") == project.id.value),
        "manifestSha256" -> (config.getString("manifestSha256") == sha256(
          Files.readAllBytes(project.paths.manifest)
        ))
      )
      compatibility.collectFirst { case (field, false) => field } match {
        case Some(field) => Left(SnapshotIncompatible(s"snapshot $field is incompatible"))
        case None =>
          val hostDisks = config.getString("hostDisks") match {
            case "reference-only" => HostDiskSnapshotPolicy.ReferenceOnly
            case "copy"           => HostDiskSnapshotPolicy.Copy
            case other => throw new IllegalArgumentException(s"unknown host disk policy: $other")
          }
          val computers = config
            .getObject("computers")
            .keySet()
            .asScala
            .map(id => id -> config.getString(s"computers.${ConfigUtil.quoteString(id)}"))
            .toMap
          val screens = config
            .getObject("screens")
            .keySet()
            .asScala
            .map(id => id -> config.getString(s"screens.${ConfigUtil.quoteString(id)}.address"))
            .toMap
          val keyboards = config
            .getObject("screens")
            .keySet()
            .asScala
            .flatMap { id =>
              val path = s"screens.${ConfigUtil.quoteString(id)}.keyboard"
              if (config.hasPath(path)) Some(id -> config.getString(path)) else None
            }
            .toMap
          val size = config.getLong("workspaceSize")
          val copiedDiskBytes = config.getLong("copiedDiskBytes")
          val digest = config.getString("workspaceSha256")
          val captureTick = config.getLong("captureTick")
          if (
            size < 0L || copiedDiskBytes < 0L || !digest.matches("[0-9a-f]{64}") || captureTick < 0L
          ) {
            Left(SnapshotCorrupt("snapshot metadata contains invalid size, checksum, or tick"))
          } else {
            Right(
              SnapshotIdentity(computers, screens, keyboards) -> SnapshotDescription(
                name,
                s"${name.value}/$WorkspaceFile",
                size,
                digest,
                hostDisks,
                captureTick,
                copiedDiskBytes
              )
            )
          }
      }
    } catch {
      case NonFatal(error) => Left(SnapshotCorrupt(errorMessage(error)))
    }
  }

  private def readBounded(path: Path, maxBytes: Long): Either[HarnessError, Array[Byte]] = {
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        Left(SnapshotCorrupt(s"missing snapshot file: ${path.getFileName}"))
      } else if (Files.size(path) > maxBytes) {
        Left(SnapshotLimitExceeded(s"snapshot file exceeds byte limit $maxBytes"))
      } else Right(Files.readAllBytes(path))
    } catch {
      case NonFatal(error) => Left(SnapshotReadFailed(errorMessage(error)))
    }
  }

  private def copyHostDisks(project: ValidatedProject, destination: Path, remaining: Long): Long = {
    var total = 0L
    val excludedRoots = Vector(project.paths.artifacts, project.paths.snapshots)
      .map(_.toAbsolutePath.normalize())
    project.computers.foreach { computer =>
      computer.hardware.disks.foreach { disk =>
        val sourceRoot = disk.source.toAbsolutePath.normalize()
        val targetRoot = destination.resolve(computer.id.value).resolve(disk.id.value)
        Files.walkFileTree(
          sourceRoot,
          new SimpleFileVisitor[Path] {
            override def preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult = {
              val normalized = directory.toAbsolutePath.normalize()
              if (directory != sourceRoot && excludedRoots.exists(normalized.startsWith)) {
                FileVisitResult.SKIP_SUBTREE
              } else if (Files.isSymbolicLink(directory)) {
                throw new IllegalArgumentException("host disk copy does not follow symbolic links")
              } else {
                Files.createDirectories(
                  targetRoot.resolve(sourceRoot.relativize(directory).toString)
                )
                FileVisitResult.CONTINUE
              }
            }

            override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
              if (Files.isSymbolicLink(file)) {
                throw new IllegalArgumentException("host disk copy does not follow symbolic links")
              }
              total += attributes.size()
              if (total > remaining) {
                throw new SnapshotLimitException("host disk copy exceeds snapshot byte limit")
              }
              val target = targetRoot.resolve(sourceRoot.relativize(file).toString)
              Files.createDirectories(target.getParent)
              Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
              FileVisitResult.CONTINUE
            }
          }
        )
      }
    }
    total
  }

  private def createContainedRoot(allowedRoot: Path, root: Path): Path = {
    val normalizedAllowed = allowedRoot.toAbsolutePath.normalize()
    val normalizedRoot = root.toAbsolutePath.normalize()
    val allowedReal = normalizedAllowed.toRealPath()
    if (!normalizedRoot.startsWith(normalizedAllowed)) {
      throw new IllegalArgumentException("snapshot root escapes the project root")
    }
    var current = normalizedAllowed
    normalizedAllowed.relativize(normalizedRoot).iterator().asScala.foreach { part =>
      current = current.resolve(part)
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        val currentReal = current.toRealPath()
        if (!Files.isDirectory(currentReal) || !currentReal.startsWith(allowedReal)) {
          throw new IllegalArgumentException("snapshot root escapes the project root")
        }
      } else Files.createDirectory(current)
    }
    val rootReal = normalizedRoot.toRealPath()
    if (!rootReal.startsWith(allowedReal)) {
      throw new IllegalArgumentException("snapshot root escapes the project root")
    }
    rootReal
  }

  private def moveAtomically(source: Path, destination: Path): Unit =
    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(path => Files.deleteIfExists(path))
      catch {
        case NonFatal(_) =>
      } finally paths.close()
    }
  }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final class SnapshotLimitException(message: String) extends RuntimeException(message)
}
