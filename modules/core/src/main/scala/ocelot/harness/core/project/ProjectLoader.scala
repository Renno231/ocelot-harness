package ocelot.harness.core.project

import java.io.File
import java.nio.file.{Files, LinkOption, Path}
import java.util.concurrent.TimeUnit

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import com.typesafe.config._

import ocelot.harness.core.project.CardKind.Network
import ocelot.harness.core.project.ConnectionEndpoint.{Computer, Screen}
import ocelot.harness.core.project.DiskAccess.{ReadOnly, ReadWrite}
import ocelot.harness.core.project.MemoryTier.{
  One,
  OneAndHalf,
  Three,
  ThreeAndHalf,
  Two,
  TwoAndHalf
}

object ProjectLoader {
  val ManifestFileName: String = "ocelot-harness.conf"
  private val SupportedSchemaVersions = Set(1, 2)

  def load(
      root: Path,
      policy: ServicePolicy = ServicePolicy()
  ): Either[ProjectErrors, ValidatedProject] = {
    canonicalProject(root).flatMap { projectRoot =>
      val manifest = projectRoot.resolve(ManifestFileName)
      if (!Files.isRegularFile(manifest)) {
        Left(
          ProjectErrors.from(
            Vector(ProjectError(ManifestFileName, "manifest_missing", "manifest file is required"))
          )
        )
      } else {
        parseManifest(projectRoot, manifest).flatMap { config =>
          schemaVersion(config) match {
            case Left(error) => Left(ProjectErrors.from(Vector(error)))
            case Right(version) if !SupportedSchemaVersions.contains(version) =>
              Left(
                ProjectErrors.from(
                  Vector(
                    ProjectError(
                      "schemaVersion",
                      "unsupported_schema",
                      s"schema version $version is unsupported; expected 1 or 2"
                    )
                  )
                )
              )
            case Right(version) =>
              new ManifestReader(config, projectRoot, manifest.toRealPath(), policy, version).read()
          }
        }
      }
    }
  }

  private def canonicalProject(root: Path): Either[ProjectErrors, Path] = {
    if (root == null) {
      Left(
        ProjectErrors.from(
          Vector(ProjectError("$project", "invalid_project_root", "project root is required"))
        )
      )
    } else {
      try {
        val canonical = root.toRealPath()
        if (Files.isDirectory(canonical)) Right(canonical)
        else
          Left(
            ProjectErrors.from(
              Vector(
                ProjectError(
                  "$project",
                  "invalid_project_root",
                  "project root must be a directory"
                )
              )
            )
          )
      } catch {
        case NonFatal(error) =>
          Left(
            ProjectErrors.from(
              Vector(
                ProjectError(
                  "$project",
                  "invalid_project_root",
                  errorMessage(error)
                )
              )
            )
          )
      }
    }
  }

  private def parseManifest(
      projectRoot: Path,
      manifest: Path
  ): Either[ProjectErrors, Config] = {
    try {
      val options = ConfigParseOptions
        .defaults()
        .setAllowMissing(false)
        .setIncluder(new ProjectIncluder(projectRoot))
      Right(ConfigFactory.parseFile(manifest.toFile, options).resolve())
    } catch {
      case NonFatal(error) =>
        Left(
          ProjectErrors.from(
            Vector(ProjectError(ManifestFileName, "manifest_parse_failed", errorMessage(error)))
          )
        )
    }
  }

  private def schemaVersion(config: Config): Either[ProjectError, Int] = {
    try Right(config.getInt("schemaVersion"))
    catch {
      case _: ConfigException.Missing =>
        Left(ProjectError("schemaVersion", "missing_key", "schemaVersion is required"))
      case NonFatal(error) =>
        Left(ProjectError("schemaVersion", "invalid_value", errorMessage(error)))
    }
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final class ProjectIncluder(projectRoot: Path)
      extends ConfigIncluder
      with ConfigIncluderFile {
    override def withFallback(fallback: ConfigIncluder): ConfigIncluder = this

    override def include(context: ConfigIncludeContext, what: String): ConfigObject =
      parseRelative(context, what)

    override def includeFile(context: ConfigIncludeContext, file: File): ConfigObject =
      parseRelative(context, file.getPath)

    private def parseRelative(
        context: ConfigIncludeContext,
        path: String
    ): ConfigObject = {
      val parseable = context.relativeTo(path)
      validateOrigin(parseable.origin())
      parseable.parse(context.parseOptions().setAllowMissing(false).setIncluder(this))
    }

    private def validateOrigin(origin: ConfigOrigin): Unit = {
      val filename = Option(origin.filename()).getOrElse {
        throw new ConfigException.Parse(
          origin,
          "manifest includes must be project-local files"
        )
      }
      val canonical = new File(filename).toPath.toRealPath()
      requireInsideProject(canonical, origin.description())
    }

    private def requireInsideProject(path: Path, description: String): Unit = {
      if (!path.startsWith(projectRoot)) {
        throw new ConfigException.IO(
          ConfigOriginFactory.newSimple(description),
          s"manifest include escapes the project root: $path"
        )
      }
    }
  }

  private final class ManifestReader(
      config: Config,
      projectRoot: Path,
      manifest: Path,
      policy: ServicePolicy,
      version: Int
  ) {
    private val errors = ArrayBuffer.empty[ProjectError]
    private lazy val canonicalAllowedRoots: Vector[Path] = {
      val additional = policy.additionalReadWriteRoots.zipWithIndex.flatMap { case (path, index) =>
        try {
          val canonical = path.toRealPath()
          if (Files.isDirectory(canonical)) Some(canonical)
          else {
            invalid(
              s"$$service.additionalReadWriteRoots[$index]",
              "allowed root must be a directory",
              "invalid_path"
            )
            None
          }
        } catch {
          case NonFatal(error) =>
            invalid(
              s"$$service.additionalReadWriteRoots[$index]",
              errorMessage(error),
              "invalid_path"
            )
            None
        }
      }
      (projectRoot +: additional).distinct
    }

    def read(): Either[ProjectErrors, ValidatedProject] = {
      validateServiceLimits()
      canonicalAllowedRoots
      val rootKeys = Set(
        "schemaVersion",
        "project",
        "runtime",
        "computers",
        "screens",
        "connections",
        "extensions"
      ) ++ (if (version == 2) Set("workspace", "devices") else Set.empty)
      rejectUnknown("", rootKeys)
      rejectUnknown("project", Set("id", "artifactDirectory", "snapshotDirectory"))
      if (version == 2) rejectUnknown("workspace", Set("kind", "directory"))
      rejectUnknown(
        "runtime",
        Set("tickRate", "internet", "limits") ++ (if (version == 2) Set("clock") else Set.empty)
      )
      if (version == 2) rejectUnknown("runtime.clock", Set("autoStart"))
      rejectUnknown("runtime.internet", Set("http", "tcp"))
      rejectUnknown(
        "runtime.limits",
        Set("defaultMaxTicks", "defaultMaxWallTime", "eventBufferSize")
      )

      val id = requiredString("project.id").flatMap(parseProjectId("project.id", _))
      val paths = readPaths()
      val runtime = readRuntime()
      val workspaceSource = readWorkspaceSource()
      val desktopSelected =
        version == 2 && config.hasPath("workspace.kind") &&
          (try config.getString("workspace.kind") == "desktop"
          catch { case NonFatal(_) => false })
      if (desktopSelected) {
        Vector("computers", "screens", "devices", "connections").foreach { path =>
          if (config.hasPath(path)) {
            invalid(
              path,
              "Desktop workspace sources cannot declare manifest topology",
              "source_conflict"
            )
          }
        }
      }
      val v2TopologySelected =
        version == 2 && !desktopSelected && config.hasPath("devices")
      if (v2TopologySelected) {
        Vector("computers", "screens").foreach { path =>
          if (config.hasPath(path)) {
            invalid(
              path,
              "schema-v2 devices cannot mix with legacy manifest topology",
              "source_conflict"
            )
          }
        }
      }
      val computers = if (desktopSelected || v2TopologySelected) Vector.empty else readComputers()
      val screens = if (desktopSelected || v2TopologySelected) Vector.empty else readScreens()
      val connections =
        if (desktopSelected || v2TopologySelected) Vector.empty
        else readConnections(computers, screens)
      val manifestTopology = if (v2TopologySelected) readManifestTopology(runtime) else None

      if (errors.nonEmpty) Left(ProjectErrors.from(errors.toVector))
      else {
        Right(
          ValidatedProject(
            version,
            id.get,
            paths.get,
            runtime,
            workspaceSource.get,
            computers,
            screens,
            connections,
            manifestTopology
          )
        )
      }
    }

    private def readWorkspaceSource(): Option[WorkspaceSourceDefinition] = {
      if (version == 1) Some(WorkspaceSourceDefinition.Manifest)
      else {
        requiredString("workspace.kind").flatMap {
          case "manifest" =>
            if (config.hasPath("workspace.directory")) {
              invalid("workspace.directory", "manifest sources do not use a directory")
            }
            Some(WorkspaceSourceDefinition.Manifest)
          case "desktop" =>
            requiredString("workspace.directory").flatMap { value =>
              canonicalDesktopSource("workspace.directory", value)
                .map(WorkspaceSourceDefinition.Desktop)
            }
          case other =>
            invalid("workspace.kind", s"unsupported workspace source: $other")
            None
        }
      }
    }

    private def readPaths(): Option[ProjectPaths] = {
      val artifactsRaw = optionalString(
        "project.artifactDirectory",
        ".ocelot-harness/artifacts"
      )
      val snapshotsRaw = optionalString(
        "project.snapshotDirectory",
        ".ocelot-harness/snapshots"
      )
      val artifacts = outputPath("project.artifactDirectory", artifactsRaw)
      val snapshots = outputPath("project.snapshotDirectory", snapshotsRaw)
      for {
        artifactPath <- artifacts
        snapshotPath <- snapshots
      } yield ProjectPaths(projectRoot, manifest, artifactPath, snapshotPath)
    }

    private def readRuntime(): ProjectRuntime = {
      val tickRate = optionalInt("runtime.tickRate", 20)
      if (tickRate < 1 || tickRate > 1000)
        invalid("runtime.tickRate", "must be between 1 and 1000")
      val clockAutoStart =
        if (version == 2) optionalBoolean("runtime.clock.autoStart", default = true)
        else false

      val requestedHttp = optionalBoolean("runtime.internet.http", default = false)
      val requestedTcp = optionalBoolean("runtime.internet.tcp", default = false)
      val maxTicks = optionalInt("runtime.limits.defaultMaxTicks", 1000)
      val maxWallTime = optionalDurationMillis(
        "runtime.limits.defaultMaxWallTime",
        30000L
      )
      val eventBufferSize = optionalInt("runtime.limits.eventBufferSize", 10000)
      if (maxTicks <= 0) invalid("runtime.limits.defaultMaxTicks", "must be positive")
      if (maxWallTime <= 0) invalid("runtime.limits.defaultMaxWallTime", "must be positive")
      if (eventBufferSize <= 0) invalid("runtime.limits.eventBufferSize", "must be positive")

      ProjectRuntime(
        tickRate,
        InternetSettings(
          requestedHttp,
          requestedTcp,
          requestedHttp && policy.allowInternetHttp,
          requestedTcp && policy.allowInternetTcp
        ),
        RuntimeLimits(maxTicks, maxWallTime, eventBufferSize),
        clockAutoStart
      )
    }

    private def readComputers(): Vector[ComputerDefinition] = {
      val ids = objectKeys("computers", required = true)
      if (ids.isEmpty && hasObject("computers")) {
        invalid("computers", "at least one computer is required")
      }
      if (ids.size > policy.maxComputers) {
        profile("computers", s"must not exceed service limit ${policy.maxComputers}")
      }
      val computers = ids.flatMap(readComputer)
      val diskCount = computers.map(_.hardware.disks.size).sum
      if (diskCount > policy.maxManagedDisks) {
        profile(
          "computers",
          s"managed disks must not exceed service limit ${policy.maxManagedDisks}"
        )
      }
      computers
    }

    private def readComputer(rawId: String): Option[ComputerDefinition] = {
      val path = s"computers.$rawId"
      rejectUnknown(path, Set("caseTier", "hardware"))
      val id = parseComputerId(path, rawId)
      val caseTier = requiredInt(s"$path.caseTier")
      caseTier.foreach { tier =>
        if (tier < 1 || tier > 3) profile(s"$path.caseTier", "must be tier 1, 2, or 3")
      }
      val hardware = readHardware(s"$path.hardware", caseTier.getOrElse(3))
      for {
        computerId <- id
        tier <- caseTier
        validatedHardware <- hardware
      } yield ComputerDefinition(computerId, tier, validatedHardware)
    }

    private def readHardware(path: String, caseTier: Int): Option[HardwareDefinition] = {
      rejectUnknown(path, Set("cpu", "memory", "gpu", "eeprom", "disks", "cards"))
      rejectUnknown(s"$path.cpu", Set("tier"))
      rejectUnknown(s"$path.gpu", Set("tier"))
      rejectUnknown(s"$path.eeprom", Set("builtin"))

      val cpuTier = requiredProfileInt(s"$path.cpu.tier")
      val gpuTier = requiredProfileInt(s"$path.gpu.tier")
      val eeprom = requiredProfileString(s"$path.eeprom.builtin")
      cpuTier.foreach(tier => requireComponentTier(s"$path.cpu.tier", tier, caseTier))
      gpuTier.foreach(tier => requireComponentTier(s"$path.gpu.tier", tier, caseTier))
      eeprom.foreach { builtin =>
        if (builtin != "lua-bios") profile(s"$path.eeprom.builtin", "only lua-bios is supported")
      }

      val memory = readMemory(s"$path.memory", caseTier)
      val disks = readDisks(s"$path.disks", caseTier)
      val cards = readCards(s"$path.cards", caseTier)
      for {
        cpu <- cpuTier
        gpu <- gpuTier
        bios <- eeprom
      } yield HardwareDefinition(cpu, memory, gpu, bios, disks, cards)
    }

    private def readMemory(path: String, caseTier: Int): Vector[MemoryTier] = {
      val configs = configList(path, required = true)
      val maxModules = if (caseTier == 1) 1 else 2
      if (configs.isEmpty) profile(path, "at least one memory module is required")
      if (configs.size > maxModules)
        profile(path, s"tier-$caseTier cases support at most $maxModules memory module(s)")
      configs.zipWithIndex.flatMap { case (entry, index) =>
        val entryPath = s"$path[$index]"
        rejectUnknown(entryPath, Set("tier"), Some(entry))
        configNumber(entry, "tier", s"$entryPath.tier").flatMap { value =>
          val requested = BigDecimal(value.toString)
          val tier = requested match {
            case MemoryTier.One.value          => Some(One)
            case MemoryTier.OneAndHalf.value   => Some(OneAndHalf)
            case MemoryTier.Two.value          => Some(Two)
            case MemoryTier.TwoAndHalf.value   => Some(TwoAndHalf)
            case MemoryTier.Three.value        => Some(Three)
            case MemoryTier.ThreeAndHalf.value => Some(ThreeAndHalf)
            case _                             => None
          }
          tier.filter(_.value <= BigDecimal(caseTier) + BigDecimal("0.5")) match {
            case valid @ Some(_) => valid
            case None =>
              profile(
                s"$entryPath.tier",
                s"must be a supported memory tier no greater than $caseTier.5"
              )
              None
          }
        }
      }
    }

    private def readDisks(path: String, caseTier: Int): Vector[DiskDefinition] = {
      val ids = objectKeys(path, required = false)
      val maxDisks = if (caseTier == 1) 1 else 2
      if (ids.size > maxDisks)
        profile(path, s"tier-$caseTier cases support at most $maxDisks managed disk(s)")
      ids.zipWithIndex.flatMap { case (rawId, slotIndex) =>
        val diskPath = s"$path.$rawId"
        rejectUnknown(diskPath, Set("kind", "tier", "label", "source", "access"))
        val id = parseDiskId(diskPath, rawId)
        val kind = requiredString(s"$diskPath.kind")
        val tier = requiredInt(s"$diskPath.tier")
        val label = requiredString(s"$diskPath.label")
        val source = requiredString(s"$diskPath.source").flatMap(
          canonicalDiskSource(s"$diskPath.source", _)
        )
        val access = requiredString(s"$diskPath.access").flatMap {
          case "read-write" => Some(ReadWrite)
          case "read-only"  => Some(ReadOnly)
          case other =>
            invalid(s"$diskPath.access", s"unsupported access mode: $other")
            None
        }
        kind.foreach { value =>
          if (value != "hdd") profile(s"$diskPath.kind", "only managed hdd is supported")
        }
        tier.foreach { value =>
          val maxTier = if (slotIndex == 0) caseTier else math.max(1, caseTier - 1)
          if (value < 1 || value > maxTier)
            profile(
              s"$diskPath.tier",
              s"disk tier must fit the tier-$caseTier case slot (maximum $maxTier)"
            )
        }
        label.foreach { value =>
          if (value.isEmpty || value.length > 64)
            invalid(s"$diskPath.label", "label must contain 1 to 64 characters")
        }
        for {
          diskId <- id
          diskTier <- tier
          diskLabel <- label
          diskSource <- source
          diskAccess <- access
        } yield DiskDefinition(diskId, diskTier, diskLabel, diskSource, diskAccess)
      }
    }

    private def readCards(path: String, caseTier: Int): Vector[CardDefinition] = {
      val entries = configList(path, required = false)
      val maxCards = if (caseTier == 1) 1 else 2
      if (entries.size > maxCards)
        profile(path, s"tier-$caseTier cases support at most $maxCards addon card(s)")
      entries.zipWithIndex.flatMap { case (entry, index) =>
        val cardPath = s"$path[$index]"
        rejectUnknown(cardPath, Set("kind", "tier"), Some(entry))
        val kind = configString(entry, "kind", s"$cardPath.kind")
        val tier = configInt(entry, "tier", s"$cardPath.tier")
        kind.foreach { value =>
          if (value != "network") profile(s"$cardPath.kind", "only network cards are supported")
        }
        val maxCardTier = math.min(2, caseTier)
        tier.foreach { value =>
          if (value < 1 || value > maxCardTier)
            profile(
              s"$cardPath.tier",
              s"network card tier must fit the tier-$caseTier card slot"
            )
        }
        for {
          cardKind <- kind if cardKind == "network"
          cardTier <- tier if cardTier >= 1 && cardTier <= maxCardTier
        } yield CardDefinition(Network, cardTier)
      }
    }

    private def readScreens(): Vector[ScreenDefinition] = {
      val ids = objectKeys("screens", required = true)
      if (ids.isEmpty && hasObject("screens")) {
        invalid("screens", "at least one screen is required")
      }
      if (ids.size > policy.maxScreens) {
        profile("screens", s"must not exceed service limit ${policy.maxScreens}")
      }
      ids.flatMap { rawId =>
        val path = s"screens.$rawId"
        rejectUnknown(path, Set("tier", "keyboard", "aspectRatio"))
        val id = parseScreenId(path, rawId)
        val tier = requiredInt(s"$path.tier")
        tier.foreach { value =>
          if (value < 1 || value > 3) profile(s"$path.tier", "must be tier 1, 2, or 3")
        }
        val keyboard = optionalBoolean(s"$path.keyboard", default = false)
        val aspect = optionalIntList(s"$path.aspectRatio", Vector(1, 1))
        if (aspect.size != 2 || aspect.exists(_ <= 0)) {
          invalid(s"$path.aspectRatio", "must contain two positive integers")
        }
        for {
          screenId <- id
          screenTier <- tier
          if aspect.size == 2 && aspect.forall(_ > 0)
        } yield ScreenDefinition(screenId, screenTier, keyboard, aspect.head -> aspect(1))
      }
    }

    private def readConnections(
        computers: Vector[ComputerDefinition],
        screens: Vector[ScreenDefinition]
    ): Vector[ConnectionDefinition] = {
      val computerIds = computers.map(_.id).toSet
      val screenIds = screens.map(_.id).toSet
      val entries = configList("connections", required = true)
      if (entries.isEmpty && config.hasPath("connections")) {
        profile("connections", "at least one computer-to-screen connection is required")
      }
      if (entries.size > policy.maxConnections) {
        profile("connections", s"must not exceed service limit ${policy.maxConnections}")
      }
      val parsed = entries.zipWithIndex.flatMap { case (entry, index) =>
        val path = s"connections[$index]"
        rejectUnknown(path, Set("from", "to"), Some(entry))
        val from = configString(entry, "from", s"$path.from").flatMap(
          parseEndpoint(s"$path.from", _, computerIds, screenIds)
        )
        val to = configString(entry, "to", s"$path.to").flatMap(
          parseEndpoint(s"$path.to", _, computerIds, screenIds)
        )
        (from, to) match {
          case (Some(computer: Computer), Some(screen: Screen)) =>
            Some(ConnectionDefinition(computer, screen))
          case (Some(_), Some(_)) =>
            profile(path, "connections must run from a computer to a screen")
            None
          case _ => None
        }
      }
      parsed
        .groupBy(connection => connection.from.value -> connection.to.value)
        .collect { case (key, values) if values.size > 1 => key }
        .toVector
        .sorted
        .foreach { case (from, to) =>
          invalid("connections", s"duplicate connection from $from to $to", "duplicate_id")
        }
      parsed
    }

    private def parseEndpoint(
        path: String,
        value: String,
        computers: Set[ComputerId],
        screens: Set[ScreenId]
    ): Option[ConnectionEndpoint] = {
      value.split(":", 2).toVector match {
        case Vector("computer", rawId) =>
          parseComputerId(path, rawId).flatMap { id =>
            if (computers.contains(id)) Some(Computer(id))
            else {
              invalid(path, s"unknown computer endpoint: $value", "invalid_connection")
              None
            }
          }
        case Vector("screen", rawId) =>
          parseScreenId(path, rawId).flatMap { id =>
            if (screens.contains(id)) Some(Screen(id))
            else {
              invalid(path, s"unknown screen endpoint: $value", "invalid_connection")
              None
            }
          }
        case _ =>
          invalid(path, s"invalid connection endpoint: $value", "invalid_connection")
          None
      }
    }

    private def readManifestTopology(
        runtime: ProjectRuntime
    ): Option[ManifestTopologyDefinition] = {
      val ids = objectKeys("devices", required = true)
      if (ids.isEmpty) profile("devices", "at least one device is required")
      if (ids.size > policy.maxDevices)
        profile("devices", s"must not exceed service limit ${policy.maxDevices}")

      val devices = ids.flatMap(readManifestDevice)
      val computerCount = devices.count(value =>
        Set(
          ManifestDeviceKind.Computer,
          ManifestDeviceKind.Server,
          ManifestDeviceKind.Microcontroller
        ).contains(value.kind)
      )
      val screenCount = devices.count(_.kind == ManifestDeviceKind.Screen)
      val inventoryCount = devices.map(_.inventory.size).sum
      if (computerCount == 0)
        profile("devices", "at least one computer, server, or microcontroller is required")
      if (computerCount > policy.maxComputers)
        profile(
          "devices",
          s"computer-like devices must not exceed service limit ${policy.maxComputers}"
        )
      if (screenCount > policy.maxScreens)
        profile("devices", s"screens must not exceed service limit ${policy.maxScreens}")
      if (inventoryCount > policy.maxInventoryItems)
        profile(
          "devices",
          s"inventory items must not exceed service limit ${policy.maxInventoryItems}"
        )
      val managedCount = devices.count(_.kind == ManifestDeviceKind.Raid) +
        devices
          .flatMap(_.inventory)
          .count(item =>
            Set(InventoryKind.ManagedHdd, InventoryKind.ManagedFloppy).contains(item.kind)
          )
      if (managedCount > policy.maxManagedDisks)
        profile("devices", s"managed disks must not exceed service limit ${policy.maxManagedDisks}")
      if (
        devices.flatMap(_.inventory).exists(_.kind == InventoryKind.Internet) &&
        !(runtime.internet.httpEnabled || runtime.internet.tcpEnabled)
      ) profile("devices", "Internet cards require manifest request and service policy opt-in")

      val byId = devices.map(value => value.id -> value).toMap
      val entries = configList("connections", required = true)
      if (entries.size > policy.maxConnections)
        profile("connections", s"must not exceed service limit ${policy.maxConnections}")
      val connections = entries.zipWithIndex.flatMap { case (entry, index) =>
        val path = s"connections[$index]"
        rejectUnknown(path, Set("from", "to"), Some(entry))
        val from = configString(entry, "from", s"$path.from").flatMap(
          parseDevicePort(s"$path.from", _, byId)
        )
        val to =
          configString(entry, "to", s"$path.to").flatMap(parseDevicePort(s"$path.to", _, byId))
        for {
          left <- from
          right <- to
          if validateDeviceConnection(path, left, right, byId)
        } yield DeviceConnectionDefinition(left, right)
      }
      connections
        .groupBy { connection =>
          val endpoints = Vector(connection.from.value, connection.to.value).sorted
          endpoints.head -> endpoints.last
        }
        .collect { case (key, values) if values.size > 1 => key }
        .toVector
        .sorted
        .foreach { case (from, to) =>
          invalid("connections", s"duplicate connection between $from and $to", "duplicate_id")
        }
      val endpointUses = connections.flatMap(connection => Vector(connection.from, connection.to))
      endpointUses
        .filter(reference =>
          reference.port.startsWith("mount-") || reference.port == "mount" || Set(
            "north",
            "east",
            "south",
            "west",
            "up",
            "down"
          ).contains(reference.port)
        )
        .groupBy(_.value)
        .collect { case (value, uses) if uses.size > 1 => value }
        .toVector
        .sorted
        .foreach(value =>
          profile("connections", s"single-use port has multiple connections: $value")
        )
      val mountedServers = connections
        .flatMap { connection =>
          Vector(connection.from, connection.to).filter(reference => reference.port == "mount")
        }
        .groupBy(_.device)
      devices.filter(_.kind == ManifestDeviceKind.Server).foreach { server =>
        if (mountedServers.getOrElse(server.id, Vector.empty).size != 1)
          profile(
            s"devices.${server.id.value}",
            "server must have exactly one rack mount connection"
          )
      }

      Some(ManifestTopologyDefinition(devices, connections))
    }

    private def readManifestDevice(rawId: String): Option[ManifestDeviceDefinition] = {
      val path = s"devices.$rawId"
      rejectUnknown(
        path,
        Set("kind", "tier", "keyboard", "aspectRatio", "inventory", "label", "source", "access")
      )
      val id = parseId(path, rawId, DeviceId.parse)
      val kind = requiredString(s"$path.kind").flatMap { value =>
        ManifestDeviceKind.fromName(value) match {
          case valid @ Some(_) => valid
          case None =>
            profile(s"$path.kind", s"unsupported manifest device kind: $value")
            None
        }
      }
      val tier = if (config.hasPath(s"$path.tier")) requiredInt(s"$path.tier") else None
      val keyboard = optionalBoolean(s"$path.keyboard", default = false)
      val aspect = optionalIntList(s"$path.aspectRatio", Vector(1, 1))
      if (aspect.size != 2 || aspect.exists(_ <= 0))
        invalid(s"$path.aspectRatio", "must contain two positive integers")
      val label = if (config.hasPath(s"$path.label")) requiredString(s"$path.label") else None
      label.foreach(value =>
        if (value.isEmpty || value.length > 64)
          invalid(s"$path.label", "label must contain 1 to 64 characters")
      )
      val source =
        if (config.hasPath(s"$path.source"))
          requiredString(s"$path.source").flatMap(canonicalDiskSource(s"$path.source", _))
        else None
      val access =
        if (config.hasPath(s"$path.access"))
          requiredString(s"$path.access").flatMap(readDiskAccess(s"$path.access", _))
        else None
      val inventory = kind.map(value => readV2Inventory(path, value, tier)).getOrElse(Vector.empty)

      kind.foreach { value =>
        val tiered = Set(
          ManifestDeviceKind.Computer,
          ManifestDeviceKind.Server,
          ManifestDeviceKind.Screen,
          ManifestDeviceKind.Hologram,
          ManifestDeviceKind.Microcontroller
        ).contains(value)
        if (tiered && tier.isEmpty) missing(s"$path.tier")
        if (!tiered && tier.nonEmpty) profile(s"$path.tier", s"${value.name} does not use a tier")
        tier.foreach { number =>
          val maximum = if (value == ManifestDeviceKind.Hologram) 2 else 3
          if (number < 1 || number > maximum)
            profile(s"$path.tier", s"unsupported ${value.name} tier: $number")
        }
        if (keyboard && value != ManifestDeviceKind.Screen)
          profile(s"$path.keyboard", "only screens may attach a keyboard")
        if (config.hasPath(s"$path.aspectRatio") && value != ManifestDeviceKind.Screen)
          profile(s"$path.aspectRatio", "only screens use an aspect ratio")
        if (
          (source.nonEmpty || access.nonEmpty || label.nonEmpty) && value != ManifestDeviceKind.Raid
        )
          profile(path, "device-level label/source/access options are reserved for RAID")
        if (value == ManifestDeviceKind.Raid && (source.isEmpty || access.isEmpty || label.isEmpty))
          profile(path, "RAID requires label, source, and access")
        if (value == ManifestDeviceKind.Raid && access.contains(ReadOnly))
          profile(s"$path.access", "RAID construction requires read-write access")
      }

      for {
        deviceId <- id
        deviceKind <- kind
        if aspect.size == 2 && aspect.forall(_ > 0)
      } yield ManifestDeviceDefinition(
        deviceId,
        deviceKind,
        tier,
        keyboard,
        aspect.head -> aspect(1),
        inventory,
        label,
        source,
        access
      )
    }

    private def readV2Inventory(
        devicePath: String,
        deviceKind: ManifestDeviceKind,
        deviceTier: Option[Int]
    ): Vector[InventoryItemDefinition] = {
      val path = s"$devicePath.inventory"
      val entries = configList(path, required = requiresInventory(deviceKind))
      val items = entries.zipWithIndex.flatMap { case (entry, index) =>
        val itemPath = s"$path[$index]"
        rejectUnknown(
          itemPath,
          Set("slot", "kind", "tier", "id", "label", "source", "access", "builtin", "tunnel"),
          Some(entry)
        )
        val slot = configString(entry, "slot", s"$itemPath.slot")
        val kind = configString(entry, "kind", s"$itemPath.kind").flatMap { value =>
          InventoryKind.fromName(value) match {
            case valid @ Some(_) => valid
            case None =>
              profile(s"$itemPath.kind", s"unsupported inventory kind: $value")
              None
          }
        }
        val tier =
          if (entry.hasPath("tier"))
            configNumber(entry, "tier", s"$itemPath.tier").map(value => BigDecimal(value.toString))
          else None
        val id =
          if (entry.hasPath("id"))
            configString(entry, "id", s"$itemPath.id").flatMap(parseDiskId(s"$itemPath.id", _))
          else None
        val label =
          if (entry.hasPath("label")) configString(entry, "label", s"$itemPath.label") else None
        val source =
          if (entry.hasPath("source"))
            configString(entry, "source", s"$itemPath.source").flatMap(
              canonicalDiskSource(s"$itemPath.source", _)
            )
          else None
        val access =
          if (entry.hasPath("access"))
            configString(entry, "access", s"$itemPath.access").flatMap(
              readDiskAccess(s"$itemPath.access", _)
            )
          else None
        val builtin =
          if (entry.hasPath("builtin")) configString(entry, "builtin", s"$itemPath.builtin")
          else None
        val tunnel =
          if (entry.hasPath("tunnel")) configString(entry, "tunnel", s"$itemPath.tunnel") else None
        for {
          itemSlot <- slot
          itemKind <- kind
          if validateInventoryItem(
            itemPath,
            deviceKind,
            deviceTier,
            itemSlot,
            itemKind,
            tier,
            id,
            label,
            source,
            access,
            builtin,
            tunnel
          )
        } yield InventoryItemDefinition(
          itemSlot,
          itemKind,
          tier,
          id,
          label,
          source,
          access,
          builtin,
          tunnel
        )
      }
      items
        .groupBy(_.slot)
        .collect { case (slot, values) if values.size > 1 => slot }
        .toVector
        .sorted
        .foreach { slot =>
          invalid(path, s"duplicate inventory slot: $slot", "duplicate_id")
        }
      if (
        Set(
          ManifestDeviceKind.Computer,
          ManifestDeviceKind.Server,
          ManifestDeviceKind.Microcontroller
        ).contains(deviceKind)
      ) {
        val kinds = items.map(_.kind).toSet
        if (!kinds.exists(value => value == InventoryKind.Cpu || value == InventoryKind.Apu))
          profile(path, "a CPU or APU is required")
        if (!kinds.contains(InventoryKind.Memory))
          profile(path, "at least one memory module is required")
        if (!kinds.contains(InventoryKind.Eeprom)) profile(path, "an EEPROM is required")
        if (
          deviceKind != ManifestDeviceKind.Microcontroller && !kinds
            .exists(value => value == InventoryKind.Gpu || value == InventoryKind.Apu)
        ) profile(path, "a GPU or APU is required")
      }
      if (
        deviceKind == ManifestDeviceKind.Raid && items.count(item =>
          Set(InventoryKind.ManagedHdd, InventoryKind.UnmanagedHdd).contains(item.kind)
        ) != 3
      )
        profile(path, "RAID requires exactly three HDDs")
      items
    }

    private def requiresInventory(kind: ManifestDeviceKind): Boolean = Set(
      ManifestDeviceKind.Computer,
      ManifestDeviceKind.Server,
      ManifestDeviceKind.Microcontroller,
      ManifestDeviceKind.Relay,
      ManifestDeviceKind.Raid,
      ManifestDeviceKind.DiskDrive
    ).contains(kind)

    private def validateInventoryItem(
        path: String,
        owner: ManifestDeviceKind,
        ownerTier: Option[Int],
        slot: String,
        kind: InventoryKind,
        tier: Option[BigDecimal],
        id: Option[DiskId],
        label: Option[String],
        source: Option[Path],
        access: Option[DiskAccess],
        builtin: Option[String],
        tunnel: Option[String]
    ): Boolean = {
      val legalSlot = owner match {
        case ManifestDeviceKind.Computer =>
          ownerTier match {
            case Some(1) => slot.matches("cpu|gpu|eeprom|memory-[1-2]|card-1|disk-1")
            case Some(2) => slot.matches("cpu|gpu|eeprom|memory-[1-2]|card-1|disk-[1-2]")
            case _       => slot.matches("cpu|gpu|eeprom|floppy|memory-[1-2]|card-[1-2]|disk-[1-2]")
          }
        case ManifestDeviceKind.Server =>
          ownerTier match {
            case Some(1) =>
              slot.matches("cpu|eeprom|memory-[1-2]|card-[1-2]|disk-1|component-bus-1")
            case Some(2) =>
              slot.matches("cpu|eeprom|memory-[1-3]|card-[1-3]|disk-[1-2]|component-bus-[1-2]")
            case _ =>
              slot.matches("cpu|eeprom|memory-[1-4]|card-[1-4]|disk-[1-4]|component-bus-[1-3]")
          }
        case ManifestDeviceKind.Microcontroller =>
          slot.matches("cpu|eeprom|memory-[1-2]|card-[1-2]")
        case ManifestDeviceKind.Relay     => Set("cpu", "memory", "disk", "card").contains(slot)
        case ManifestDeviceKind.Raid      => slot.matches("disk-[1-3]")
        case ManifestDeviceKind.DiskDrive => slot == "floppy"
        case _                            => false
      }
      if (!legalSlot) profile(s"$path.slot", s"slot $slot is not legal for ${owner.name}")
      val slotMatchesKind =
        (slot == "cpu" && Set(InventoryKind.Cpu, InventoryKind.Apu).contains(kind)) ||
          (slot == "gpu" && kind == InventoryKind.Gpu) ||
          (slot == "eeprom" && kind == InventoryKind.Eeprom) ||
          (slot.startsWith("memory-") || slot == "memory") && kind == InventoryKind.Memory ||
          (slot.startsWith("disk-") || slot == "disk") && Set(
            InventoryKind.ManagedHdd,
            InventoryKind.UnmanagedHdd
          ).contains(kind) ||
          slot == "floppy" && Set(InventoryKind.ManagedFloppy, InventoryKind.UnmanagedFloppy)
            .contains(kind) ||
          slot.startsWith("component-bus-") && kind == InventoryKind.ComponentBus ||
          (slot.startsWith("card-") || slot == "card") && Set(
            InventoryKind.Gpu,
            InventoryKind.Network,
            InventoryKind.Wireless,
            InventoryKind.Linked,
            InventoryKind.Data,
            InventoryKind.Redstone,
            InventoryKind.Internet
          ).contains(kind)
      if (!slotMatchesKind) profile(s"$path.kind", s"${kind.name} does not fit slot $slot")
      val tiered = !Set(
        InventoryKind.Eeprom,
        InventoryKind.Linked,
        InventoryKind.Internet,
        InventoryKind.ManagedFloppy,
        InventoryKind.UnmanagedFloppy
      ).contains(kind)
      if (tiered && tier.isEmpty) missing(s"$path.tier")
      if (!tiered && tier.nonEmpty) profile(s"$path.tier", s"${kind.name} does not use a tier")
      tier.foreach { value =>
        val ownerMaximum = owner match {
          case ManifestDeviceKind.Server => math.min(3, ownerTier.getOrElse(3) + 1)
          case _                         => ownerTier.getOrElse(3)
        }
        val kindMax = kind match {
          case InventoryKind.Network  => BigDecimal(1)
          case InventoryKind.Wireless => BigDecimal(2)
          case InventoryKind.Redstone => BigDecimal(2)
          case _ =>
            BigDecimal(ownerMaximum) +
              (if (kind == InventoryKind.Memory) BigDecimal("0.5") else BigDecimal(0))
        }
        if (value < 1 || value > kindMax || (kind != InventoryKind.Memory && !value.isWhole))
          profile(s"$path.tier", s"tier $value exceeds the ${kind.name} profile")
      }
      val managed = Set(InventoryKind.ManagedHdd, InventoryKind.ManagedFloppy).contains(kind)
      if (managed && (id.isEmpty || label.isEmpty || source.isEmpty || access.isEmpty))
        profile(path, s"${kind.name} requires id, label, source, and access")
      label.foreach { value =>
        if (value.isEmpty || value.length > 64)
          invalid(s"$path.label", "label must contain 1 to 64 characters")
      }
      if (!managed && (id.nonEmpty || label.nonEmpty || source.nonEmpty || access.nonEmpty))
        profile(path, s"${kind.name} does not use managed-disk fields")
      if (kind == InventoryKind.Eeprom && builtin != Some("lua-bios"))
        profile(s"$path.builtin", "EEPROM builtin must be lua-bios")
      if (kind != InventoryKind.Eeprom && builtin.nonEmpty)
        profile(s"$path.builtin", s"${kind.name} does not use builtin")
      if (
        kind == InventoryKind.Linked && tunnel.forall(value => value.isEmpty || value.length > 64)
      )
        profile(s"$path.tunnel", "linked card tunnel must contain 1 to 64 characters")
      if (kind != InventoryKind.Linked && tunnel.nonEmpty)
        profile(s"$path.tunnel", s"${kind.name} does not use tunnel")
      legalSlot && slotMatchesKind
    }

    private def readDiskAccess(path: String, value: String): Option[DiskAccess] = value match {
      case "read-write" => Some(ReadWrite)
      case "read-only"  => Some(ReadOnly)
      case other =>
        invalid(path, s"unsupported access mode: $other")
        None
    }

    private def parseDevicePort(
        path: String,
        value: String,
        devices: Map[DeviceId, ManifestDeviceDefinition]
    ): Option[DevicePortReference] = value.split(":", 2).toVector match {
      case Vector(rawId, port) if port.nonEmpty =>
        parseId(path, rawId, DeviceId.parse).flatMap { id =>
          devices.get(id) match {
            case None =>
              invalid(path, s"unknown device endpoint: $rawId", "invalid_connection")
              None
            case Some(device) if legalPorts(device).contains(port) =>
              Some(DevicePortReference(id, port))
            case Some(device) =>
              invalid(
                path,
                s"port $port is not legal for ${device.kind.name}",
                "invalid_connection"
              )
              None
          }
        }
      case _ =>
        invalid(path, s"invalid device:port reference: $value", "invalid_connection")
        None
    }

    private def legalPorts(device: ManifestDeviceDefinition): Set[String] = device.kind match {
      case ManifestDeviceKind.Rack =>
        Set("north", "east", "south", "west", "up", "down") ++ (1 to 4).map(index =>
          s"mount-$index"
        )
      case ManifestDeviceKind.Server =>
        val secondaryCount = device.inventory.count(item =>
          Set(InventoryKind.Network, InventoryKind.Wireless).contains(item.kind)
        )
        Set("mount", "network", "primary") ++
          (1 to secondaryCount).map(index => s"secondary-$index")
      case ManifestDeviceKind.Microcontroller | ManifestDeviceKind.Relay =>
        Set("north", "east", "south", "west", "up", "down")
      case _ => Set("network")
    }

    private def validateDeviceConnection(
        path: String,
        from: DevicePortReference,
        to: DevicePortReference,
        devices: Map[DeviceId, ManifestDeviceDefinition]
    ): Boolean = {
      if (from.device == to.device) {
        profile(path, "a connection cannot connect a device to itself")
        false
      } else {
        val left = devices(from.device)
        val right = devices(to.device)
        val mount =
          (left.kind == ManifestDeviceKind.Rack && from.port.startsWith(
            "mount-"
          ) && right.kind == ManifestDeviceKind.Server && to.port == "mount") ||
            (right.kind == ManifestDeviceKind.Rack && to.port.startsWith(
              "mount-"
            ) && left.kind == ManifestDeviceKind.Server && from.port == "mount")
        val mismatchedMount = Set(from.port, to.port).exists(port =>
          port.startsWith("mount-") || port == "mount"
        ) && !mount
        if (mismatchedMount) {
          profile(path, "rack mount ports connect only to a server mount port")
          false
        } else true
      }
    }

    private def outputPath(path: String, value: String): Option[Path] = {
      canonicalCandidate(resolveManifestRelative(value)) match {
        case Right(canonical) if canonical.startsWith(projectRoot) => Some(canonical)
        case Right(canonical) =>
          invalid(path, s"path escapes the project root: $canonical", "path_not_allowed")
          None
        case Left(message) =>
          invalid(path, message, "invalid_path")
          None
      }
    }

    private def canonicalDesktopSource(path: String, value: String): Option[Path] = {
      val candidate = resolveManifestRelative(value)
      try {
        val canonical = candidate.toRealPath()
        if (!canonical.startsWith(projectRoot)) {
          invalid(path, s"path escapes the project root: $canonical", "path_not_allowed")
          None
        } else if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
          invalid(path, "Desktop source must be an existing directory", "invalid_path")
          None
        } else if (
          !Files.isRegularFile(canonical.resolve("workspace.nbt"), LinkOption.NOFOLLOW_LINKS)
        ) {
          invalid(path, "Desktop source must contain workspace.nbt", "invalid_path")
          None
        } else Some(canonical)
      } catch {
        case NonFatal(error) =>
          invalid(path, errorMessage(error), "invalid_path")
          None
      }
    }

    private def canonicalDiskSource(path: String, value: String): Option[Path] = {
      val candidate = resolveManifestRelative(value)
      try {
        val canonical = candidate.toRealPath()
        if (!Files.isDirectory(canonical)) {
          invalid(path, "disk source must be an existing directory", "invalid_path")
          None
        } else {
          if (canonicalAllowedRoots.exists(canonical.startsWith)) Some(canonical)
          else {
            invalid(path, s"path is outside service-approved roots: $canonical", "path_not_allowed")
            None
          }
        }
      } catch {
        case NonFatal(error) =>
          invalid(path, errorMessage(error), "invalid_path")
          None
      }
    }

    private def resolveManifestRelative(value: String): Path = {
      val parsed = new File(value).toPath
      if (parsed.isAbsolute) parsed.toAbsolutePath.normalize()
      else projectRoot.resolve(parsed).toAbsolutePath.normalize()
    }

    private def canonicalCandidate(candidate: Path): Either[String, Path] = {
      try {
        var ancestor = candidate
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
          ancestor = ancestor.getParent
        }
        if (ancestor == null) Left(s"path has no existing ancestor: $candidate")
        else {
          val canonicalAncestor = ancestor.toRealPath()
          Right(canonicalAncestor.resolve(ancestor.relativize(candidate)).normalize())
        }
      } catch {
        case NonFatal(error) => Left(errorMessage(error))
      }
    }

    private def parseProjectId(path: String, value: String): Option[ProjectId] =
      parseId(path, value, ProjectId.parse)

    private def parseComputerId(path: String, value: String): Option[ComputerId] =
      parseId(path, value, ComputerId.parse)

    private def parseScreenId(path: String, value: String): Option[ScreenId] =
      parseId(path, value, ScreenId.parse)

    private def parseDiskId(path: String, value: String): Option[DiskId] =
      parseId(path, value, DiskId.parse)

    private def parseId[A](
        path: String,
        value: String,
        parse: String => Either[String, A]
    ): Option[A] =
      parse(value) match {
        case Right(id) => Some(id)
        case Left(message) =>
          invalid(path, message, "invalid_logical_id")
          None
      }

    private def requireComponentTier(path: String, value: Int, caseTier: Int): Unit = {
      if (value < 1 || value > caseTier)
        profile(path, s"must be between tier 1 and the tier-$caseTier case limit")
    }

    private def validateServiceLimits(): Unit = {
      Vector(
        "$service.maxComputers" -> policy.maxComputers,
        "$service.maxScreens" -> policy.maxScreens,
        "$service.maxConnections" -> policy.maxConnections,
        "$service.maxManagedDisks" -> policy.maxManagedDisks,
        "$service.maxDevices" -> policy.maxDevices,
        "$service.maxInventoryItems" -> policy.maxInventoryItems
      ).foreach { case (path, value) =>
        if (value <= 0) invalid(path, "service limit must be positive", "invalid_service_policy")
      }
    }

    private def rejectUnknown(
        path: String,
        allowed: Set[String],
        selectedConfig: Option[Config] = None
    ): Unit = {
      val target = selectedConfig.orElse(configAt(path))
      target.foreach { value =>
        value
          .root()
          .keySet()
          .asScala
          .toVector
          .sorted
          .filterNot(allowed)
          .foreach { key =>
            val errorPath = if (path.isEmpty) key else s"$path.$key"
            invalid(errorPath, "unknown key", "unknown_key")
          }
      }
    }

    private def objectKeys(path: String, required: Boolean): Vector[String] = {
      if (!config.hasPath(path)) {
        if (required) missing(path)
        Vector.empty
      } else {
        try config.getObject(path).keySet().asScala.toVector.sorted
        catch {
          case NonFatal(error) =>
            invalid(path, errorMessage(error))
            Vector.empty
        }
      }
    }

    private def hasObject(path: String): Boolean =
      configAt(path).nonEmpty

    private def configAt(path: String): Option[Config] = {
      val selected =
        if (path.isEmpty) config
        else {
          if (!config.hasPath(path)) return None
          try config.getConfig(path)
          catch {
            case NonFatal(error) =>
              invalid(path, errorMessage(error))
              return None
          }
        }
      Some(selected)
    }

    private def configList(path: String, required: Boolean): Vector[Config] = {
      if (!config.hasPath(path)) {
        if (required) missing(path)
        Vector.empty
      } else {
        try config.getConfigList(path).asScala.toVector
        catch {
          case NonFatal(error) =>
            invalid(path, errorMessage(error))
            Vector.empty
        }
      }
    }

    private def requiredString(path: String): Option[String] =
      required(path)(config.getString(path))

    private def requiredInt(path: String): Option[Int] =
      required(path)(config.getInt(path))

    private def requiredProfileString(path: String): Option[String] =
      requiredProfile(path)(config.getString(path))

    private def requiredProfileInt(path: String): Option[Int] =
      requiredProfile(path)(config.getInt(path))

    private def requiredProfile[A](path: String)(read: => A): Option[A] = {
      if (!config.hasPath(path)) {
        profile(path, "required by the initial hardware profile")
        None
      } else {
        try Some(read)
        catch {
          case NonFatal(error) =>
            invalid(path, errorMessage(error))
            None
        }
      }
    }

    private def required[A](path: String)(read: => A): Option[A] = {
      if (!config.hasPath(path)) {
        missing(path)
        None
      } else {
        try Some(read)
        catch {
          case NonFatal(error) =>
            invalid(path, errorMessage(error))
            None
        }
      }
    }

    private def optionalString(path: String, default: String): String =
      optional(path, default)(config.getString(path))

    private def optionalInt(path: String, default: Int): Int =
      optional(path, default)(config.getInt(path))

    private def optionalBoolean(path: String, default: Boolean): Boolean =
      optional(path, default)(config.getBoolean(path))

    private def optionalDurationMillis(path: String, default: Long): Long =
      optional(path, default)(config.getDuration(path, TimeUnit.MILLISECONDS))

    private def optionalIntList(path: String, default: Vector[Int]): Vector[Int] =
      optional(path, default)(config.getIntList(path).asScala.map(_.intValue()).toVector)

    private def optional[A](path: String, default: A)(read: => A): A = {
      if (!config.hasPath(path)) default
      else {
        try read
        catch {
          case NonFatal(error) =>
            invalid(path, errorMessage(error))
            default
        }
      }
    }

    private def configString(entry: Config, key: String, path: String): Option[String] =
      entryValue(entry, key, path)(entry.getString(key))

    private def configInt(entry: Config, key: String, path: String): Option[Int] =
      entryValue(entry, key, path)(entry.getInt(key))

    private def configNumber(entry: Config, key: String, path: String): Option[Number] =
      entryValue(entry, key, path)(entry.getNumber(key))

    private def entryValue[A](entry: Config, key: String, path: String)(read: => A): Option[A] = {
      if (!entry.hasPath(key)) {
        missing(path)
        None
      } else {
        try Some(read)
        catch {
          case NonFatal(error) =>
            invalid(path, errorMessage(error))
            None
        }
      }
    }

    private def profile(path: String, message: String): Unit =
      invalid(path, message, "profile_violation")

    private def missing(path: String): Unit =
      invalid(path, "required key is missing", "missing_key")

    private def invalid(
        path: String,
        message: String,
        code: String = "invalid_value"
    ): Unit = errors += ProjectError(path, code, message)
  }
}
