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
import ocelot.harness.core.project.MemoryTier.{Three, ThreeAndHalf}

object ProjectLoader {
  val ManifestFileName: String = "ocelot-harness.conf"
  private val SupportedSchemaVersion = 1

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
            case Right(version) if version != SupportedSchemaVersion =>
              Left(
                ProjectErrors.from(
                  Vector(
                    ProjectError(
                      "schemaVersion",
                      "unsupported_schema",
                      s"schema version $version is unsupported; expected $SupportedSchemaVersion"
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
      canonicalAllowedRoots
      rejectUnknown(
        "",
        Set(
          "schemaVersion",
          "project",
          "runtime",
          "computers",
          "screens",
          "connections",
          "extensions"
        )
      )
      rejectUnknown("project", Set("id", "artifactDirectory", "snapshotDirectory"))
      rejectUnknown("runtime", Set("tickRate", "internet", "limits"))
      rejectUnknown("runtime.internet", Set("http", "tcp"))
      rejectUnknown(
        "runtime.limits",
        Set("defaultMaxTicks", "defaultMaxWallTime", "eventBufferSize")
      )

      val id = requiredString("project.id").flatMap(parseProjectId("project.id", _))
      val paths = readPaths()
      val runtime = readRuntime()
      val computers = readComputers()
      val screens = readScreens()
      val connections = readConnections(computers, screens)

      if (errors.nonEmpty) Left(ProjectErrors.from(errors.toVector))
      else {
        Right(
          ValidatedProject(
            version,
            id.get,
            paths.get,
            runtime,
            computers,
            screens,
            connections
          )
        )
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
      if (tickRate <= 0) invalid("runtime.tickRate", "must be positive")

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
        RuntimeLimits(maxTicks, maxWallTime, eventBufferSize)
      )
    }

    private def readComputers(): Vector[ComputerDefinition] = {
      val ids = objectKeys("computers", required = true)
      if (ids.isEmpty && hasObject("computers")) {
        invalid("computers", "at least one computer is required")
      }
      ids.flatMap(readComputer)
    }

    private def readComputer(rawId: String): Option[ComputerDefinition] = {
      val path = s"computers.$rawId"
      rejectUnknown(path, Set("caseTier", "hardware"))
      val id = parseComputerId(path, rawId)
      val caseTier = requiredInt(s"$path.caseTier")
      caseTier.foreach { tier =>
        if (tier != 3) profile(s"$path.caseTier", "the initial profile requires tier 3")
      }
      val hardware = readHardware(s"$path.hardware")
      for {
        computerId <- id
        tier <- caseTier
        validatedHardware <- hardware
      } yield ComputerDefinition(computerId, tier, validatedHardware)
    }

    private def readHardware(path: String): Option[HardwareDefinition] = {
      rejectUnknown(path, Set("cpu", "memory", "gpu", "eeprom", "disks", "cards"))
      rejectUnknown(s"$path.cpu", Set("tier"))
      rejectUnknown(s"$path.gpu", Set("tier"))
      rejectUnknown(s"$path.eeprom", Set("builtin"))

      val cpuTier = requiredProfileInt(s"$path.cpu.tier")
      val gpuTier = requiredProfileInt(s"$path.gpu.tier")
      val eeprom = requiredProfileString(s"$path.eeprom.builtin")
      cpuTier.foreach(tier => requireTier3(s"$path.cpu.tier", tier))
      gpuTier.foreach(tier => requireTier3(s"$path.gpu.tier", tier))
      eeprom.foreach { builtin =>
        if (builtin != "lua-bios") profile(s"$path.eeprom.builtin", "only lua-bios is supported")
      }

      val memory = readMemory(s"$path.memory")
      val disks = readDisks(s"$path.disks")
      val cards = readCards(s"$path.cards")
      for {
        cpu <- cpuTier
        gpu <- gpuTier
        bios <- eeprom
      } yield HardwareDefinition(cpu, memory, gpu, bios, disks, cards)
    }

    private def readMemory(path: String): Vector[MemoryTier] = {
      val configs = configList(path, required = true)
      if (configs.isEmpty) profile(path, "at least one memory module is required")
      if (configs.size > 2) profile(path, "at most two memory modules are supported")
      configs.zipWithIndex.flatMap { case (entry, index) =>
        val entryPath = s"$path[$index]"
        rejectUnknown(entryPath, Set("tier"), Some(entry))
        configNumber(entry, "tier", s"$entryPath.tier").flatMap { value =>
          BigDecimal(value.toString) match {
            case MemoryTier.Three.value        => Some(Three)
            case MemoryTier.ThreeAndHalf.value => Some(ThreeAndHalf)
            case _ =>
              profile(s"$entryPath.tier", "must be 3 or 3.5")
              None
          }
        }
      }
    }

    private def readDisks(path: String): Vector[DiskDefinition] = {
      val ids = objectKeys(path, required = false)
      if (ids.size > 2) profile(path, "at most two managed disks are supported")
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
          if (value != 2 && value != 3) profile(s"$diskPath.tier", "must be tier 2 or 3")
          else if (slotIndex > 0 && value != 2)
            profile(s"$diskPath.tier", "the secondary disk slot supports tier 2")
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

    private def readCards(path: String): Vector[CardDefinition] = {
      val entries = configList(path, required = false)
      if (entries.size > 2) profile(path, "at most two addon cards are supported")
      entries.zipWithIndex.flatMap { case (entry, index) =>
        val cardPath = s"$path[$index]"
        rejectUnknown(cardPath, Set("kind", "tier"), Some(entry))
        val kind = configString(entry, "kind", s"$cardPath.kind")
        val tier = configInt(entry, "tier", s"$cardPath.tier")
        kind.foreach { value =>
          if (value != "network") profile(s"$cardPath.kind", "only network cards are supported")
        }
        tier.foreach { value =>
          if (value < 1 || value > 2)
            profile(s"$cardPath.tier", "network card tier must fit a tier-2 card slot")
        }
        for {
          cardKind <- kind if cardKind == "network"
          cardTier <- tier if cardTier >= 1 && cardTier <= 2
        } yield CardDefinition(Network, cardTier)
      }
    }

    private def readScreens(): Vector[ScreenDefinition] = {
      val ids = objectKeys("screens", required = true)
      if (ids.isEmpty && hasObject("screens")) {
        invalid("screens", "at least one screen is required")
      }
      ids.flatMap { rawId =>
        val path = s"screens.$rawId"
        rejectUnknown(path, Set("tier", "keyboard", "aspectRatio"))
        val id = parseScreenId(path, rawId)
        val tier = requiredInt(s"$path.tier")
        tier.foreach(value => requireTier3(s"$path.tier", value))
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

    private def requireTier3(path: String, value: Int): Unit = {
      if (value != 3) profile(path, "the initial profile requires tier 3")
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
