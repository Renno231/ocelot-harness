package ocelot.harness.core.project

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.{Locale, UUID}

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import com.typesafe.config.ConfigUtil
import totoro.ocelot.brain.entity.{Case => BrainCase, Keyboard, Screen}
import totoro.ocelot.brain.nbt.{
  CompressedStreamTools,
  NBT,
  NBTBase,
  NBTReadLimiter,
  NBTTagCompound,
  NBTTagList
}

final case class DesktopLogicalDevice(
    logicalId: String,
    label: Option[String]
)

final case class DesktopWorkspaceInspection(
    source: Path,
    sha256: String,
    entityCount: Int,
    edgeCount: Int,
    computers: Vector[DesktopLogicalDevice],
    screens: Vector[DesktopLogicalDevice],
    additionalEntityCount: Int
)

final case class DesktopImportResult(
    projectRoot: Path,
    inspection: DesktopWorkspaceInspection
)

private[harness] final case class ValidatedDesktopImport(
    root: NBTTagCompound,
    inspection: DesktopWorkspaceInspection,
    metadata: DesktopImportMetadata
)

object DesktopWorkspaceProjects {
  val WorkspaceFileName = "workspace.nbt"
  val CopiedSourceDirectory = "desktop"
  val MetadataRelativePath = ".ocelot-harness/desktop-import.conf"

  private val MaxCompressedBytes = 64L * 1024L * 1024L
  private val MaxDecompressedBytes = 64L * 1024L * 1024L
  private val MaxSourceBytes = 512L * 1024L * 1024L
  private val MaxSourceFiles = 4096
  private val MaxEntities = 4096
  private val MaxEdges = 16384
  private val MetadataFormatVersion = 1
  private val RegisteredPersistenceClasses = Set(
    "totoro.ocelot.brain.entity.Case",
    "totoro.ocelot.brain.entity.Server",
    "totoro.ocelot.brain.entity.Microcontroller",
    "totoro.ocelot.brain.entity.Screen",
    "totoro.ocelot.brain.entity.HologramProjector",
    "totoro.ocelot.brain.entity.CPU",
    "totoro.ocelot.brain.entity.APU",
    "totoro.ocelot.brain.entity.GraphicsCard",
    "totoro.ocelot.brain.entity.HDDManaged",
    "totoro.ocelot.brain.entity.HDDUnmanaged",
    "totoro.ocelot.brain.entity.Memory",
    "totoro.ocelot.brain.entity.ComponentBus"
  )

  def inspect(source: Path): Either[ProjectErrors, DesktopWorkspaceInspection] =
    inspectInternal(source).map(_.inspection)

  private[harness] def validateImportedProject(
      project: ValidatedProject
  ): Either[ProjectErrors, DesktopWorkspaceInspection] =
    loadValidatedImport(project).map(_.inspection)

  private[harness] def loadValidatedImport(
      project: ValidatedProject
  ): Either[ProjectErrors, ValidatedDesktopImport] =
    project.workspaceSource match {
      case WorkspaceSourceDefinition.Desktop(directory) =>
        for {
          inspected <- inspectInternal(directory)
          metadata <- DesktopImportMetadata.read(project.paths.projectRoot)
          expectedComputers = inspected.computerBindings
            .map(value => value.entityId -> value.logicalId)
            .toMap
          expectedScreens = inspected.screenBindings
            .map(value => value.entityId -> value.logicalId)
            .toMap
          _ <-
            if (
              metadata.workspaceSha256 == inspected.inspection.sha256 &&
              metadata.computers == expectedComputers && metadata.screens == expectedScreens
            ) Right(())
            else
              failure(
                "desktop-import.conf",
                "import_metadata_invalid",
                "logical identity metadata does not match the imported workspace"
              )
        } yield ValidatedDesktopImport(inspected.root, inspected.inspection, metadata)
      case WorkspaceSourceDefinition.Manifest =>
        failure("workspace.kind", "source_conflict", "project is not a Desktop workspace import")
    }

  def importProject(
      source: Path,
      destination: Path
  ): Either[ProjectErrors, DesktopImportResult] =
    inspectInternal(source).flatMap { inspected =>
      canonicalDestination(destination, inspected.inspection.source).flatMap { target =>
        val parent = target.getParent
        var temporary: Option[Path] = None
        try {
          val temp = Files.createTempDirectory(parent, ".ocelot-harness-import-")
          temporary = Some(temp)
          val copiedSource = temp.resolve(CopiedSourceDirectory)
          val currentWorkspaceSha = sha256(
            Files.readAllBytes(inspected.inspection.source.resolve(WorkspaceFileName))
          )
          if (currentWorkspaceSha != inspected.inspection.sha256) {
            throw new IllegalArgumentException("Desktop workspace changed during import")
          }
          copySource(inspected.inspection.source, copiedSource)
          rewriteDiskPaths(
            inspected.root,
            target.resolve(CopiedSourceDirectory),
            inspected.diskBindings
          )
          val importedWorkspaceBytes = CompressedStreamTools.write(inspected.root)
          Files.write(
            copiedSource.resolve(WorkspaceFileName),
            importedWorkspaceBytes,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
          Files.write(
            temp.resolve(ProjectLoader.ManifestFileName),
            renderManifest(projectId(target)).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          )
          val metadata = temp.resolve(MetadataRelativePath)
          Files.createDirectories(metadata.getParent)
          Files.write(
            metadata,
            renderMetadata(inspected, sha256(importedWorkspaceBytes))
              .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          )
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
          temporary = None
          Right(DesktopImportResult(target.toRealPath(), inspected.inspection))
        } catch {
          case error: ImportLimitException =>
            failure("<import>", "import_limit_exceeded", error.getMessage)
          case NonFatal(error) =>
            failure("<import>", "desktop_import_failed", errorMessage(error))
        } finally temporary.foreach(deleteRecursively)
      }
    }

  private def readDesktopFile(
      workspaceFile: Path
  ): Either[ProjectErrors, ParsedDesktop] = {
    try {
      if (!Files.isRegularFile(workspaceFile, LinkOption.NOFOLLOW_LINKS)) {
        failure("workspace.nbt", "desktop_format_invalid", "workspace.nbt is required")
      } else if (Files.size(workspaceFile) > MaxCompressedBytes) {
        failure(
          "workspace.nbt",
          "desktop_limit_exceeded",
          s"compressed workspace exceeds $MaxCompressedBytes bytes"
        )
      } else {
        val bytes = Files.readAllBytes(workspaceFile)
        val root = CompressedStreamTools.read(bytes, new NBTReadLimiter(MaxDecompressedBytes))
        validateRoot(root).map(_ => ParsedDesktop(root, sha256(bytes)))
      }
    } catch {
      case NonFatal(error) =>
        failure("workspace.nbt", "desktop_format_invalid", errorMessage(error))
    }
  }

  private def inspectInternal(source: Path): Either[ProjectErrors, InspectedDesktop] =
    canonicalSource(source).flatMap { canonical =>
      scanSource(canonical).flatMap { _ =>
        readDesktopFile(canonical.resolve(WorkspaceFileName)).flatMap { parsed =>
          val root = parsed.root
          val back = root.getCompoundTag("back")
          val front = root.getCompoundTag("front")
          val entities = compoundList(back, "entities")
          val edges = compoundList(back, "edges")
          if (entities.size > MaxEntities) {
            failure("back.entities", "desktop_limit_exceeded", s"entity count exceeds $MaxEntities")
          } else if (edges.size > MaxEdges) {
            failure("back.edges", "desktop_limit_exceeded", s"edge count exceeds $MaxEdges")
          } else {
            val allPersisted = collectPersisted(root)
            val topLevelEntities = entities.map(persistedEntity)
            val malformedTopLevel =
              if (topLevelEntities.exists(_.isEmpty))
                Vector(
                  ProjectError(
                    "back.entities",
                    "desktop_identity_invalid",
                    "every workspace entity must contain a serialized class and entity UUID"
                  )
                )
              else Vector.empty
            val identityErrors = malformedTopLevel ++ allPersisted.flatMap { value =>
              try {
                UUID.fromString(value.entityId)
                None
              } catch {
                case NonFatal(_) =>
                  Some(
                    ProjectError(
                      "back.entities",
                      "desktop_identity_invalid",
                      s"invalid entity UUID: ${value.entityId}"
                    )
                  )
              }
            } ++ allPersisted
              .groupBy(_.entityId)
              .collect {
                case (entityId, values) if values.size > 1 =>
                  ProjectError(
                    "back.entities",
                    "desktop_identity_invalid",
                    s"duplicate entity UUID: $entityId"
                  )
              }
              .toVector
            val classNames = collectSerializedClasses(root)
            val unavailable = classNames.filterNot(classAvailable)
            if (identityErrors.nonEmpty) {
              Left(ProjectErrors.from(identityErrors))
            } else if (unavailable.nonEmpty) {
              Left(
                ProjectErrors.from(
                  unavailable.map(name =>
                    ProjectError(
                      "back.entities",
                      "desktop_class_unavailable",
                      s"serialized class is unavailable: $name"
                    )
                  )
                )
              )
            } else {
              val labels = frontLabels(front)
              val topLevel = topLevelEntities.flatten
              val computerBindings = logicalDevices(
                topLevel.filter(_.className == classOf[BrainCase].getName),
                "computer",
                labels
              )
              val screenBindings = logicalDevices(
                topLevel.filter(_.className == classOf[Screen].getName),
                "screen",
                labels
              )
              val keyboardByAddress = topLevel
                .filter(_.className == classOf[Keyboard].getName)
                .flatMap(value => value.address.map(_ -> value.entityId))
                .toMap
              val neighbors = edges
                .flatMap { edge =>
                  val left = edge.getString("left")
                  val right = edge.getString("right")
                  if (left.nonEmpty && right.nonEmpty) Vector(left -> right, right -> left)
                  else Vector.empty
                }
                .groupMap(_._1)(_._2)
              val keyboardBindings = screenBindings.flatMap { screen =>
                topLevel
                  .find(_.entityId == screen.entityId)
                  .flatMap(_.address)
                  .toVector
                  .flatMap(address => neighbors.getOrElse(address, Vector.empty))
                  .flatMap(keyboardByAddress.get)
                  .sorted
                  .headOption
                  .map(screen.logicalId -> _)
              }.toMap
              val diskBindings = allPersisted.flatMap { value =>
                value.realPath.map { raw =>
                  canonicalDiskBinding(canonical, raw).map(relative => value.entityId -> relative)
                }
              }
              val diskErrors = diskBindings.collect { case Left(error) => error }
              if (diskErrors.nonEmpty) Left(ProjectErrors.from(diskErrors))
              else {
                val inspection = DesktopWorkspaceInspection(
                  canonical,
                  parsed.sha256,
                  entities.size,
                  edges.size,
                  computerBindings.map(_.device),
                  screenBindings.map(_.device),
                  entities.size - computerBindings.size - screenBindings.size
                )
                Right(
                  InspectedDesktop(
                    root,
                    inspection,
                    computerBindings,
                    screenBindings,
                    keyboardBindings,
                    diskBindings.collect { case Right(value) => value }.toMap
                  )
                )
              }
            }
          }
        }
      }
    }

  private def validateRoot(root: NBTTagCompound): Either[ProjectErrors, Unit] = {
    val errors = Vector.newBuilder[ProjectError]
    if (!root.hasKeyOfType("back", NBT.TAG_COMPOUND)) {
      errors += ProjectError("back", "desktop_format_invalid", "back must be a compound")
    }
    if (!root.hasKeyOfType("front", NBT.TAG_COMPOUND)) {
      errors += ProjectError("front", "desktop_format_invalid", "front must be a compound")
    }
    if (root.hasKeyOfType("back", NBT.TAG_COMPOUND)) {
      val back = root.getCompoundTag("back")
      if (back.getTagType("entities") != NBT.TAG_LIST) {
        errors += ProjectError("back.entities", "desktop_format_invalid", "entities must be a list")
      }
      if (back.getTagType("edges") != NBT.TAG_LIST) {
        errors += ProjectError("back.edges", "desktop_format_invalid", "edges must be a list")
      }
    }
    val result = errors.result()
    if (result.isEmpty) Right(()) else Left(ProjectErrors.from(result))
  }

  private def canonicalSource(source: Path): Either[ProjectErrors, Path] =
    try {
      if (source == null) failure("<source>", "invalid_path", "Desktop source is required")
      else {
        val canonical = source.toRealPath()
        if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
          failure("<source>", "invalid_path", "Desktop source must be a directory")
        } else Right(canonical)
      }
    } catch {
      case NonFatal(error) => failure("<source>", "invalid_path", errorMessage(error))
    }

  private def canonicalDestination(
      destination: Path,
      source: Path
  ): Either[ProjectErrors, Path] =
    try {
      if (destination == null)
        failure("<destination>", "invalid_path", "destination is required")
      else {
        val target = destination.toAbsolutePath.normalize()
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          failure("<destination>", "destination_exists", "destination already exists")
        } else {
          val parent = Option(target.getParent).getOrElse(target)
          Files.createDirectories(parent)
          val parentReal = parent.toRealPath()
          val canonicalTarget = parentReal.resolve(target.getFileName).normalize()
          if (canonicalTarget.startsWith(source) || source.startsWith(canonicalTarget)) {
            failure(
              "<destination>",
              "invalid_path",
              "source and destination must not contain each other"
            )
          } else Right(canonicalTarget)
        }
      }
    } catch {
      case NonFatal(error) => failure("<destination>", "invalid_path", errorMessage(error))
    }

  private def scanSource(source: Path): Either[ProjectErrors, Unit] =
    try {
      var files = 0
      var bytes = 0L
      Files.walkFileTree(
        source,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(
              directory: Path,
              attributes: BasicFileAttributes
          ): FileVisitResult = {
            if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(source)) {
              throw new IllegalArgumentException("Desktop source contains a directory escape")
            }
            FileVisitResult.CONTINUE
          }

          override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
            if (Files.isSymbolicLink(file) || !attributes.isRegularFile) {
              throw new IllegalArgumentException("Desktop source contains a link or special file")
            }
            files += 1
            bytes = Math.addExact(bytes, attributes.size())
            if (files > MaxSourceFiles || bytes > MaxSourceBytes) {
              throw new ImportLimitException(
                s"Desktop source exceeds $MaxSourceFiles files or $MaxSourceBytes bytes"
              )
            }
            FileVisitResult.CONTINUE
          }
        }
      )
      Right(())
    } catch {
      case error: ImportLimitException =>
        failure("<source>", "import_limit_exceeded", error.getMessage)
      case NonFatal(error) => failure("<source>", "invalid_path", errorMessage(error))
    }

  private def copySource(source: Path, destination: Path): Unit =
    Files.walkFileTree(
      source,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult = {
          if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(source)) {
            throw new IllegalArgumentException("Desktop source changed to a directory escape")
          }
          Files.createDirectories(destination.resolve(source.relativize(directory).toString))
          FileVisitResult.CONTINUE
        }

        override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
          if (Files.isSymbolicLink(file) || !attributes.isRegularFile) {
            throw new IllegalArgumentException("Desktop source changed to a link or special file")
          }
          val target = destination.resolve(source.relativize(file).toString)
          Files.createDirectories(target.getParent)
          Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
          FileVisitResult.CONTINUE
        }
      }
    )

  private def rewriteDiskPaths(
      root: NBTTagCompound,
      copiedSource: Path,
      bindings: Map[String, String]
  ): Unit =
    visit(root) {
      case compound: NBTTagCompound if compound.hasKey("entity_id") && compound.hasKey("rp") =>
        bindings.get(compound.getString("entity_id")).foreach { relative =>
          val rebound = copiedSource.resolve(relative).normalize()
          if (!rebound.startsWith(copiedSource)) {
            throw new IllegalArgumentException("managed disk binding escapes copied source")
          }
          compound.setString("rp", rebound.toString)
        }
      case _ =>
    }

  private def canonicalDiskBinding(
      source: Path,
      raw: String
  ): Either[ProjectError, String] =
    try {
      val parsed = java.nio.file.Paths.get(raw)
      val candidate = if (parsed.isAbsolute) parsed else source.resolve(parsed)
      val canonical = candidate.toRealPath()
      if (!Files.isDirectory(canonical) || !canonical.startsWith(source)) {
        Left(
          ProjectError(
            "back.entities",
            "path_not_allowed",
            s"managed disk path is outside the Desktop source: $canonical"
          )
        )
      } else Right(source.relativize(canonical).toString.replace('\\', '/'))
    } catch {
      case NonFatal(error) =>
        Left(ProjectError("back.entities", "invalid_path", errorMessage(error)))
    }

  private def collectSerializedClasses(root: NBTBase): Vector[String] = {
    val values = Vector.newBuilder[String]
    visit(root) {
      case compound: NBTTagCompound
          if compound.hasKeyOfType("type", NBT.TAG_STRING) &&
            compound.hasKeyOfType("data", NBT.TAG_COMPOUND) =>
        Option(compound.getString("type")).filter(_.nonEmpty).foreach(values += _)
      case _ =>
    }
    values.result().distinct.sorted
  }

  private def collectPersisted(root: NBTBase): Vector[PersistedEntity] = {
    val values = Vector.newBuilder[PersistedEntity]
    visit(root) {
      case compound: NBTTagCompound
          if compound.hasKeyOfType("type", NBT.TAG_STRING) &&
            compound.hasKeyOfType("data", NBT.TAG_COMPOUND) =>
        persistedEntity(compound).foreach(values += _)
      case _ =>
    }
    values.result()
  }

  private def persistedEntity(compound: NBTTagCompound): Option[PersistedEntity] = {
    if (
      !compound
        .hasKeyOfType("type", NBT.TAG_STRING) || !compound.hasKeyOfType("data", NBT.TAG_COMPOUND)
    ) {
      None
    } else {
      val data = compound.getCompoundTag("data")
      val className = compound.getString("type")
      val entityId = data.getString("entity_id")
      val address =
        if (data.hasKeyOfType("node", NBT.TAG_COMPOUND))
          Option(data.getCompoundTag("node").getString("address")).filter(_.nonEmpty)
        else None
      val realPath = Option(data.getString("rp")).filter(_.nonEmpty)
      if (className.isEmpty || entityId.isEmpty) None
      else Some(PersistedEntity(className, entityId, address, realPath))
    }
  }

  private def frontLabels(front: NBTTagCompound): Map[String, String] =
    compoundList(front, "nodes").flatMap { node =>
      val address = node.getString("address")
      val label = node.getString("label").trim
      if (address.nonEmpty && label.nonEmpty) Some(address -> label) else None
    }.toMap

  private def logicalDevices(
      values: Vector[PersistedEntity],
      kind: String,
      labels: Map[String, String]
  ): Vector[LogicalBinding] = {
    val sorted = values.sortBy(_.entityId)
    val used = mutable.Set.empty[String]
    sorted.zipWithIndex.map { case (value, index) =>
      val label = value.address.flatMap(labels.get)
      val base = label.map(normalizeId).filter(_.nonEmpty).getOrElse(s"$kind-${index + 1}")
      val prefixed = if (base.headOption.exists(_.isLetter)) base else s"$kind-$base"
      var suffix = 1
      var candidate = prefixed.take(63)
      while (used.contains(candidate)) {
        suffix += 1
        val ending = s"-$suffix"
        candidate = prefixed.take(63 - ending.length) + ending
      }
      used += candidate
      LogicalBinding(candidate, value.entityId, label)
    }
  }

  private def normalizeId(value: String): String =
    value
      .toLowerCase(Locale.ROOT)
      .map(character =>
        if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9'))
          character
        else '-'
      )
      .mkString
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")

  private def classAvailable(name: String): Boolean =
    try {
      val value = Class.forName(name, false, getClass.getClassLoader)
      classOf[totoro.ocelot.brain.util.Persistable].isAssignableFrom(value) &&
      (RegisteredPersistenceClasses.contains(name) || value.getConstructors.exists(
        _.getParameterCount == 0
      ))
    } catch {
      case NonFatal(_)     => false
      case _: LinkageError => false
    }

  private def compoundList(compound: NBTTagCompound, key: String): Vector[NBTTagCompound] = {
    val list = compound.getTagList(key, NBT.TAG_COMPOUND)
    Vector.tabulate(list.tagCount())(list.getCompoundTagAt)
  }

  private def visit(root: NBTBase)(operation: NBTBase => Unit): Unit = {
    operation(root)
    root match {
      case compound: NBTTagCompound =>
        compound.getKeySet.asScala.toVector.sorted.foreach { key =>
          Option(compound.getTag(key)).foreach(visit(_)(operation))
        }
      case list: NBTTagList =>
        var index = 0
        while (index < list.tagCount()) {
          val value = list.getType match {
            case NBT.TAG_COMPOUND => list.getCompoundTagAt(index)
            case _                => null
          }
          if (value != null) visit(value)(operation)
          index += 1
        }
      case _ =>
    }
  }

  private def renderManifest(id: ProjectId): String =
    s"""schemaVersion = 2
       |project { id = ${ConfigUtil.quoteString(id.value)} }
       |workspace { kind = "desktop", directory = "./$CopiedSourceDirectory" }
       |runtime { }
       |""".stripMargin

  private def renderMetadata(
      inspected: InspectedDesktop,
      workspaceSha256: String
  ): String = {
    def mapping(values: Vector[LogicalBinding]): String =
      values
        .sortBy(_.entityId)
        .map(value =>
          s"  ${ConfigUtil.quoteString(value.entityId)}=${ConfigUtil.quoteString(value.logicalId)}"
        )
        .mkString("\n")
    def plainMapping(values: Map[String, String]): String =
      values.toVector.sorted
        .map { case (key, value) =>
          s"  ${ConfigUtil.quoteString(key)}=${ConfigUtil.quoteString(value)}"
        }
        .mkString("\n")
    Vector(
      s"formatVersion=$MetadataFormatVersion",
      s"sourceSha256=${ConfigUtil.quoteString(inspected.inspection.sha256)}",
      s"workspaceSha256=${ConfigUtil.quoteString(workspaceSha256)}",
      "computers {",
      mapping(inspected.computerBindings),
      "}",
      "screens {",
      mapping(inspected.screenBindings),
      "}",
      "keyboards {",
      plainMapping(inspected.keyboardBindings),
      "}",
      "diskBindings {",
      plainMapping(inspected.diskBindings),
      "}",
      ""
    ).mkString("\n")
  }

  private def projectId(destination: Path): ProjectId = {
    val raw = normalizeId(destination.getFileName.toString)
    val candidate = if (raw.headOption.exists(_.isLetter)) raw else s"project-$raw"
    ProjectId
      .parse(candidate.take(63))
      .fold(
        _ => throw new IllegalArgumentException("destination name cannot form a project ID"),
        identity
      )
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(path => Files.deleteIfExists(path))
      catch { case NonFatal(_) => }
      finally paths.close()
    }
  }

  private[harness] def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def failure[A](path: String, code: String, message: String): Left[ProjectErrors, A] =
    Left(ProjectErrors.from(Vector(ProjectError(path, code, message))))

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final case class ParsedDesktop(root: NBTTagCompound, sha256: String)

  private final case class PersistedEntity(
      className: String,
      entityId: String,
      address: Option[String],
      realPath: Option[String]
  )

  private final case class LogicalBinding(
      logicalId: String,
      entityId: String,
      label: Option[String]
  ) {
    def device: DesktopLogicalDevice = DesktopLogicalDevice(logicalId, label)
  }

  private final case class InspectedDesktop(
      root: NBTTagCompound,
      inspection: DesktopWorkspaceInspection,
      computerBindings: Vector[LogicalBinding],
      screenBindings: Vector[LogicalBinding],
      keyboardBindings: Map[String, String],
      diskBindings: Map[String, String]
  )

  private final class ImportLimitException(message: String) extends RuntimeException(message)
}
