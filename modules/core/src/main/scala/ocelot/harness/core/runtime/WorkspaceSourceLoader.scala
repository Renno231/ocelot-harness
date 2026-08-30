package ocelot.harness.core.runtime

import java.nio.file.{Files, LinkOption, Path}

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import totoro.ocelot.brain.entity.{Case => BrainCase, Keyboard, Screen}
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.nbt.{NBT, NBTBase, NBTTagCompound, NBTTagList}
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.{ProjectOpenFailed, ProjectValidationFailed}
import ocelot.harness.core.project.{
  ComputerId,
  ConnectionDefinition,
  ConnectionEndpoint,
  DesktopImportMetadata,
  DesktopWorkspaceProjects,
  ScreenId,
  ServicePolicy,
  ValidatedProject,
  WorkspaceSourceDefinition
}
import ocelot.harness.core.workspace.{ComputerDescription, ScreenDescription, WorkspaceDescription}

private[runtime] final case class LoadedWorkspace(
    workspace: Workspace,
    constructed: ConstructedWorkspace,
    source: WorkspaceSourceAdapter
)

private[runtime] trait WorkspaceSourceAdapter {
  def restore(workspace: Workspace, identity: SnapshotIdentity): ConstructedWorkspace
}

private[runtime] object WorkspaceSourceLoader {
  def load(
      project: ValidatedProject,
      policy: ServicePolicy
  ): Either[HarnessError, LoadedWorkspace] =
    project.workspaceSource match {
      case WorkspaceSourceDefinition.Manifest =>
        val source = new ManifestWorkspaceSource(project)
        source.open()
      case desktop: WorkspaceSourceDefinition.Desktop =>
        DesktopWorkspaceSource.create(project, desktop.directory, policy).flatMap(_.open())
    }

  private final class ManifestWorkspaceSource(project: ValidatedProject)
      extends WorkspaceSourceAdapter {
    def open(): Either[HarnessError, LoadedWorkspace] = {
      val workspace = new Workspace(project.paths.projectRoot)
      try {
        val constructed = HardwareCatalog.construct(project, workspace)
        Right(LoadedWorkspace(workspace, constructed, this))
      } catch {
        case NonFatal(error) =>
          dispose(workspace)
          Left(ProjectOpenFailed(project.paths.projectRoot.toString, errorMessage(error)))
      }
    }

    override def restore(
        workspace: Workspace,
        identity: SnapshotIdentity
    ): ConstructedWorkspace = project.manifestTopology match {
      case Some(topology) => ManifestHardwareCatalog.restore(project, topology, workspace, identity)
      case None           => HardwareCatalog.restore(project, workspace, identity)
    }
  }

  private final class DesktopWorkspaceSource(
      project: ValidatedProject,
      directory: Path,
      policy: ServicePolicy,
      metadata: DesktopImportMetadata,
      importedRoot: NBTTagCompound
  ) extends WorkspaceSourceAdapter {
    def open(): Either[HarnessError, LoadedWorkspace] = {
      val workspace = new Workspace(directory)
      try {
        rewriteDiskPaths(importedRoot, directory, metadata.diskBindings)
        workspace.load(importedRoot.getCompoundTag("back"))
        val constructed = bind(workspace, None)
        Right(LoadedWorkspace(workspace, constructed, this))
      } catch {
        case NonFatal(error) =>
          dispose(workspace)
          Left(ProjectOpenFailed(project.paths.projectRoot.toString, errorMessage(error)))
      }
    }

    override def restore(
        workspace: Workspace,
        identity: SnapshotIdentity
    ): ConstructedWorkspace = bind(workspace, Some(identity))

    private def bind(
        workspace: Workspace,
        snapshotIdentity: Option[SnapshotIdentity]
    ): ConstructedWorkspace = {
      val entities = workspace.getEntitiesIter.toVector
      val allEntities = entities.flatMap(flattenEntity)
      val duplicateEntityIds = allEntities
        .groupBy(_.entityId.toString)
        .collect { case (entityId, values) if values.size > 1 => entityId }
      if (duplicateEntityIds.nonEmpty) {
        throw new IllegalArgumentException(
          s"imported workspace has duplicate entity UUIDs: ${duplicateEntityIds.toVector.sorted.mkString(",")}"
        )
      }
      val byId = entities.map(entity => entity.entityId.toString -> entity).toMap
      val computers = metadata.computers.toVector
        .sortBy(_._2)
        .map { case (entityId, rawId) =>
          val id = ComputerId
            .parse(rawId)
            .fold(message => throw new IllegalArgumentException(message), identity)
          val computer = byId.get(entityId) match {
            case Some(value: BrainCase) => value
            case _ =>
              throw new IllegalArgumentException(s"imported computer entity is missing: $rawId")
          }
          id -> computer
        }
        .toMap
      val screens = metadata.screens.toVector
        .sortBy(_._2)
        .map { case (entityId, rawId) =>
          val id = ScreenId
            .parse(rawId)
            .fold(message => throw new IllegalArgumentException(message), identity)
          val screen = byId.get(entityId) match {
            case Some(value: Screen) => value
            case _ =>
              throw new IllegalArgumentException(s"imported screen entity is missing: $rawId")
          }
          id -> screen
        }
        .toMap
      if (computers.size > policy.maxComputers) {
        throw new IllegalArgumentException(
          s"imported computers exceed service limit ${policy.maxComputers}"
        )
      }
      if (screens.size > policy.maxScreens) {
        throw new IllegalArgumentException(
          s"imported screens exceed service limit ${policy.maxScreens}"
        )
      }
      val keyboards = metadata.keyboards.toVector.map { case (rawScreenId, entityId) =>
        val screenId = ScreenId
          .parse(rawScreenId)
          .fold(
            message => throw new IllegalArgumentException(message),
            identity
          )
        val keyboard = byId.get(entityId) match {
          case Some(value: Keyboard) => value
          case _ =>
            throw new IllegalArgumentException(s"imported keyboard is missing: $rawScreenId")
        }
        screenId -> keyboard
      }.toMap
      if (!keyboards.keySet.subsetOf(screens.keySet)) {
        throw new IllegalArgumentException("imported keyboard refers to an unknown screen")
      }
      if (metadata.diskBindings.size > policy.maxManagedDisks) {
        throw new IllegalArgumentException(
          s"imported managed disks exceed service limit ${policy.maxManagedDisks}"
        )
      }
      val directoryReal = directory.toRealPath()
      val reboundDisks = metadata.diskBindings.toVector
        .sortBy(_._1)
        .map { case (entityId, relative) =>
          val disk = findEntity(entities, entityId)
            .collect { case value: totoro.ocelot.brain.entity.traits.DiskRealPathAware =>
              value
            }
            .getOrElse(
              throw new IllegalArgumentException(s"imported managed disk is missing: $entityId")
            )
          val candidate = directory.resolve(relative).normalize()
          val rebound = candidate.toRealPath()
          if (
            !candidate.startsWith(directory) || !rebound.startsWith(directoryReal) ||
            !Files.isDirectory(rebound, LinkOption.NOFOLLOW_LINKS)
          ) {
            throw new IllegalArgumentException(s"imported managed disk path is invalid: $relative")
          }
          if (!disk.customRealPath.exists(_.toAbsolutePath.normalize() == rebound)) {
            disk.customRealPath = Some(rebound)
          }
          entityId -> rebound
        }
        .toMap

      snapshotIdentity.foreach { identity =>
        if (
          identity.computers.keySet != computers.keys.map(_.value).toSet ||
          identity.screens.keySet != screens.keys.map(_.value).toSet ||
          identity.keyboards.keySet != keyboards.keys.map(_.value).toSet
        )
          throw new IllegalArgumentException(
            "snapshot logical identity set does not match import metadata"
          )
        computers.foreach { case (id, computer) =>
          requireAddress(computer) match {
            case address if identity.computers(id.value) == address =>
            case _ =>
              throw new IllegalArgumentException(
                s"snapshot computer identity mismatch: ${id.value}"
              )
          }
        }
        screens.foreach { case (id, screen) =>
          if (identity.screens(id.value) != requireAddress(screen)) {
            throw new IllegalArgumentException(s"snapshot screen identity mismatch: ${id.value}")
          }
        }
        keyboards.foreach { case (id, keyboard) =>
          if (identity.keyboards(id.value) != requireAddress(keyboard)) {
            throw new IllegalArgumentException(s"snapshot keyboard identity mismatch: ${id.value}")
          }
        }
      }

      val connections =
        computers.toVector.sortBy(_._1.value).flatMap { case (computerId, computer) =>
          screens.toVector.sortBy(_._1.value).collect {
            case (screenId, screen) if computer.node.isNeighborOf(screen.node) =>
              ConnectionDefinition(
                ConnectionEndpoint.Computer(computerId),
                ConnectionEndpoint.Screen(screenId)
              )
          }
        }
      if (connections.size > policy.maxConnections) {
        throw new IllegalArgumentException(
          s"imported connections exceed service limit ${policy.maxConnections}"
        )
      }
      val computerDescriptions = computers.toVector.sortBy(_._1.value).map { case (id, value) =>
        ComputerDescription(
          id,
          value.tier.num,
          requireAddress(value),
          Vector.empty,
          Vector.empty,
          Vector.empty
        )
      }
      val screenDescriptions = screens.toVector.sortBy(_._1.value).map { case (id, value) =>
        val width = math.max(1, math.round(value.aspectRatio._1).toInt)
        val height = math.max(1, math.round(value.aspectRatio._2).toInt)
        ScreenDescription(
          id,
          value.tier.num,
          width -> height,
          requireAddress(value),
          keyboards.get(id).map(requireAddress)
        )
      }
      ConstructedWorkspace(
        WorkspaceDescription(project.id, computerDescriptions, screenDescriptions, connections),
        computers,
        screens,
        keyboards,
        screens.view.mapValues(_.tier.num).toMap,
        reboundDisks.toVector.sortBy(_._1).map { case (entityId, source) =>
          s"desktop/$entityId" -> source
        }
      )
    }
  }

  private object DesktopWorkspaceSource {
    def create(
        project: ValidatedProject,
        directory: Path,
        policy: ServicePolicy
    ): Either[HarnessError, DesktopWorkspaceSource] =
      DesktopWorkspaceProjects
        .loadValidatedImport(project)
        .left
        .map(errors => ProjectValidationFailed(errors.errors): HarnessError)
        .map(validated =>
          new DesktopWorkspaceSource(
            project,
            directory,
            policy,
            validated.metadata,
            validated.root
          )
        )
  }

  private def rewriteDiskPaths(
      root: NBTTagCompound,
      directory: Path,
      bindings: Map[String, String]
  ): Unit =
    visit(root) {
      case compound: NBTTagCompound if compound.hasKey("entity_id") && compound.hasKey("rp") =>
        bindings.get(compound.getString("entity_id")).foreach { relative =>
          val path = directory.resolve(relative).normalize()
          if (!path.startsWith(directory)) {
            throw new IllegalArgumentException("managed disk binding escapes imported source")
          }
          compound.setString("rp", path.toString)
        }
      case _ =>
    }

  private def findEntity(entities: Vector[Entity], entityId: String): Option[Entity] =
    entities.flatMap(flattenEntity).find(_.entityId.toString == entityId)

  private def flattenEntity(entity: Entity): Vector[Entity] = entity match {
    case inventory: totoro.ocelot.brain.entity.traits.Inventory =>
      entity +: inventory.inventory.entities.toVector.flatMap(flattenEntity)
    case _ => Vector(entity)
  }

  private def visit(root: NBTBase)(operation: NBTBase => Unit): Unit = {
    operation(root)
    root match {
      case compound: NBTTagCompound =>
        compound.getKeySet.asScala.toVector.sorted.foreach { key =>
          Option(compound.getTag(key)).foreach(visit(_)(operation))
        }
      case list: NBTTagList if list.getType == NBT.TAG_COMPOUND =>
        var index = 0
        while (index < list.tagCount()) {
          visit(list.getCompoundTagAt(index))(operation)
          index += 1
        }
      case _ =>
    }
  }

  private def requireAddress(value: Environment): String =
    Option(value.node)
      .flatMap(node => Option(node.address))
      .filter(_.nonEmpty)
      .getOrElse(
        throw new IllegalArgumentException("imported entity has no runtime address")
      )

  private def dispose(workspace: Workspace): Unit =
    workspace.getEntitiesIter.toVector.reverse.foreach { entity =>
      try workspace.remove(entity)
      catch { case NonFatal(_) => }
    }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
