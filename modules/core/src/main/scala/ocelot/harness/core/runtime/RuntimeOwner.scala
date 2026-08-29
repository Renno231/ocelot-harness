package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.io.{Codec, Source}
import scala.util.control.NonFatal

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import totoro.ocelot.brain.{Ocelot, Settings}
import totoro.ocelot.brain.entity.machine.luac.LuaStateFactory
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError._

final class RuntimeOwner private[runtime] (
    coordinator: RuntimeCoordinator,
    lifecycle: BrainLifecycle,
    shutdownHooks: ShutdownHooks
) extends AutoCloseable {
  private var activeSession: Option[BrainSession] = None
  private var brainConfigPath: Option[Path] = None
  private var shutdownHook: Option[AutoCloseable] = None
  private var initializationStarted = false
  private var closed = false

  def openProject(root: Path): Either[HarnessError, BrainSession] = synchronized {
    if (closed) {
      Left(RuntimeClosed)
    } else if (activeSession.nonEmpty) {
      Left(ProjectAlreadyOpen)
    } else {
      normalizeProjectRoot(root).flatMap { projectRoot =>
        try {
          val session = new BrainSession(
            projectRoot,
            new Workspace(projectRoot),
            sessionClosed
          )
          activeSession = Some(session)
          Right(session)
        } catch {
          case NonFatal(error) =>
            Left(ProjectOpenFailed(projectRoot.toString, errorMessage(error)))
        }
      }
    }
  }

  private[harness] def brainVersion: String = lifecycle.version

  private[harness] def nativeLuaAvailable: Boolean = lifecycle.nativeLuaAvailable

  override def close(): Unit = synchronized {
    if (!closed) {
      closed = true
      var firstFailure: Option[Throwable] = None

      def complete(action: => Unit): Unit = {
        try action
        catch {
          case NonFatal(error) if firstFailure.isEmpty => firstFailure = Some(error)
          case NonFatal(_)                             =>
        }
      }

      activeSession.foreach(session => complete(session.close()))
      activeSession = None

      if (initializationStarted) {
        complete(lifecycle.shutdown())
      }
      initializationStarted = false

      shutdownHook.foreach(hook => complete(hook.close()))
      shutdownHook = None

      brainConfigPath.foreach(path => complete(Files.deleteIfExists(path)))
      brainConfigPath = None

      coordinator.ownerClosed(this)
      firstFailure.foreach(throw _)
    }
  }

  private[runtime] def initialize(config: RuntimeConfig): Unit = synchronized {
    val normalized = normalizeConfig(config)
    Files.createDirectories(normalized.runtimeDirectory)
    Files.createDirectories(normalized.nativeLibraryDirectory)

    val configPath = BrainConfiguration.write(normalized.runtimeDirectory)
    brainConfigPath = Some(configPath)
    shutdownHook = Some(shutdownHooks.register(() => close()))
    initializationStarted = true
    lifecycle.initialize(configPath, normalized.nativeLibraryDirectory)
  }

  private[runtime] def closeAfterStartupFailure(): Unit = {
    try close()
    catch {
      case NonFatal(_) =>
    }
  }

  private def normalizeConfig(config: RuntimeConfig): RuntimeConfig = {
    if (
      config == null || config.runtimeDirectory == null || config.nativeLibraryDirectory == null
    ) {
      throw new IllegalArgumentException("runtime and native-library directories are required")
    }

    RuntimeConfig(
      config.runtimeDirectory.toAbsolutePath.normalize(),
      config.nativeLibraryDirectory.toAbsolutePath.normalize()
    )
  }

  private def normalizeProjectRoot(root: Path): Either[HarnessError, Path] = {
    if (root == null) {
      Left(ProjectOpenFailed("<null>", "project root is required"))
    } else {
      val normalized = root.toAbsolutePath.normalize()
      if (Files.isDirectory(normalized)) Right(normalized)
      else
        Left(ProjectOpenFailed(normalized.toString, "project root must be an existing directory"))
    }
  }

  private def sessionClosed(session: BrainSession): Unit = synchronized {
    if (activeSession.exists(_ eq session)) {
      activeSession = None
    }
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

object RuntimeOwner {
  private val processCoordinator =
    new RuntimeCoordinator(OcelotBrainLifecycle, JvmShutdownHooks)

  def start(config: RuntimeConfig): Either[HarnessError, RuntimeOwner] =
    processCoordinator.start(config)

  private[runtime] def isolatedForTesting(
      lifecycle: BrainLifecycle,
      shutdownHooks: ShutdownHooks
  ): RuntimeCoordinator = new RuntimeCoordinator(lifecycle, shutdownHooks)
}

private[runtime] final class RuntimeCoordinator(
    lifecycle: BrainLifecycle,
    shutdownHooks: ShutdownHooks
) {
  private var consumed = false
  private var owner: Option[RuntimeOwner] = None

  def start(config: RuntimeConfig): Either[HarnessError, RuntimeOwner] = synchronized {
    if (consumed) {
      Left(RuntimeAlreadyOwned)
    } else {
      consumed = true
      val candidate = new RuntimeOwner(this, lifecycle, shutdownHooks)
      owner = Some(candidate)
      try {
        candidate.initialize(config)
        Right(candidate)
      } catch {
        case NonFatal(error) =>
          candidate.closeAfterStartupFailure()
          Left(RuntimeInitializationFailed(errorMessage(error)))
      }
    }
  }

  private[runtime] def ownerClosed(closedOwner: RuntimeOwner): Unit = synchronized {
    if (owner.exists(_ eq closedOwner)) {
      owner = None
    }
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

private[runtime] trait BrainLifecycle {
  def initialize(brainConfigPath: Path, librariesPath: Path): Unit
  def shutdown(): Unit
  def nativeLuaAvailable: Boolean
  def version: String = Ocelot.Version
}

private object OcelotBrainLifecycle extends BrainLifecycle {
  override def initialize(brainConfigPath: Path, librariesPath: Path): Unit = {
    Ocelot.configPath = Some(brainConfigPath)
    Ocelot.librariesPath = Some(librariesPath)
    Ocelot.initialize()
  }

  override def shutdown(): Unit = {
    try Ocelot.shutdown()
    finally {
      Ocelot.configPath = None
      Ocelot.librariesPath = None
    }
  }

  override def nativeLuaAvailable: Boolean = LuaStateFactory.isAvailable
}

private[runtime] trait ShutdownHooks {
  def register(closeOwner: () => Unit): AutoCloseable
}

private object JvmShutdownHooks extends ShutdownHooks {
  override def register(closeOwner: () => Unit): AutoCloseable = {
    val hook = new Thread(
      new Runnable {
        override def run(): Unit = closeOwner()
      },
      "ocelot-harness-runtime-shutdown"
    )
    Runtime.getRuntime.addShutdownHook(hook)

    new AutoCloseable {
      override def close(): Unit = {
        try Runtime.getRuntime.removeShutdownHook(hook)
        catch {
          case _: IllegalStateException =>
        }
      }
    }
  }
}

private object BrainConfiguration {
  private val FileName = "brain.conf"

  def write(runtimeDirectory: Path): Path = {
    val input = classOf[Settings].getResourceAsStream("/application.conf")
    if (input == null) {
      throw new IllegalStateException("ocelot-brain application.conf is unavailable")
    }

    val defaults = {
      val source = Source.fromInputStream(input)(Codec.UTF8)
      try ConfigFactory.parseString(source.mkString)
      finally source.close()
    }
    val restrictions = ConfigFactory.parseString(
      """
        |opencomputers {
        |  filesystem.bufferChanges = false
        |  internet.enableHttp = false
        |  internet.enableHttpHeaders = false
        |  internet.enableTcp = false
        |}
        |""".stripMargin
    )
    val rendered = restrictions
      .withFallback(defaults)
      .resolve()
      .root()
      .render(
        ConfigRenderOptions
          .defaults()
          .setComments(false)
          .setOriginComments(false)
          .setJson(false)
      )
    val configPath = runtimeDirectory.resolve(FileName).toAbsolutePath.normalize()
    Files.write(
      configPath,
      rendered.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
    configPath
  }
}
