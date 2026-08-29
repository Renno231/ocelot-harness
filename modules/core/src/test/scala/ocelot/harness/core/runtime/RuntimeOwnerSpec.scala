package ocelot.harness.core.runtime

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import com.typesafe.config.ConfigFactory
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class RuntimeOwnerSpec extends AnyFunSuite with Matchers with EitherValues {
  test("starts the brain once and rejects every later owner in the same process") {
    withRuntimeConfig { config =>
      val lifecycle = new RecordingBrainLifecycle
      val hooks = new RecordingShutdownHooks
      val coordinator = RuntimeOwner.isolatedForTesting(lifecycle, hooks)

      val owner = coordinator.start(config).value

      lifecycle.initializeCount shouldBe 1
      coordinator.start(config).left.value.code shouldBe "runtime_already_owned"

      owner.close()

      lifecycle.shutdownCount shouldBe 1
      hooks.registrationCount shouldBe 1
      hooks.removalCount shouldBe 1
      coordinator.start(config).left.value.code shouldBe "runtime_already_owned"
    }
  }

  test("the registered JVM shutdown action delegates idempotently to the owner") {
    withRuntimeConfig { config =>
      val lifecycle = new RecordingBrainLifecycle
      val hooks = new RecordingShutdownHooks
      val owner = RuntimeOwner.isolatedForTesting(lifecycle, hooks).start(config).value
      val projectRoot = Files.createDirectory(config.runtimeDirectory.resolve("hook-project"))
      val session = owner.openEmptySessionForTesting(projectRoot).value

      hooks.runRegisteredHook()
      hooks.runRegisteredHook()
      owner.close()

      session.isClosed shouldBe true
      lifecycle.shutdownCount shouldBe 1
      hooks.removalCount shouldBe 1
    }
  }

  test("closes the active empty session before global shutdown") {
    withRuntimeConfig { config =>
      val lifecycle = new RecordingBrainLifecycle
      val coordinator = RuntimeOwner.isolatedForTesting(lifecycle, new RecordingShutdownHooks)
      val owner = coordinator.start(config).value
      val projectRoot = Files.createDirectory(config.runtimeDirectory.resolve("project"))
      val session = owner.openEmptySessionForTesting(projectRoot).value
      lifecycle.beforeShutdown = () => session.isClosed shouldBe true

      owner.close()

      session.isClosed shouldBe true
      lifecycle.shutdownCount shouldBe 1
    }
  }

  test("allows only one active session and permits another after explicit session close") {
    withRuntimeConfig { config =>
      val coordinator = RuntimeOwner.isolatedForTesting(
        new RecordingBrainLifecycle,
        new RecordingShutdownHooks
      )
      val owner = coordinator.start(config).value
      val firstRoot = Files.createDirectory(config.runtimeDirectory.resolve("first-project"))
      val secondRoot = Files.createDirectory(config.runtimeDirectory.resolve("second-project"))

      val first = owner.openEmptySessionForTesting(firstRoot).value
      owner.openEmptySessionForTesting(secondRoot).left.value.code shouldBe "project_already_open"

      first.close()
      val second = owner.openEmptySessionForTesting(secondRoot).value
      second.projectRoot shouldBe secondRoot.toAbsolutePath.normalize()

      owner.close()
      second.isClosed shouldBe true
    }
  }

  test("releases lifecycle and shutdown-hook resources after startup failure") {
    withRuntimeConfig { config =>
      val lifecycle = new RecordingBrainLifecycle
      lifecycle.initializeFailure = Some(new IllegalStateException("deliberate startup failure"))
      val hooks = new RecordingShutdownHooks
      val coordinator = RuntimeOwner.isolatedForTesting(lifecycle, hooks)

      val error = coordinator.start(config).left.value

      error.code shouldBe "runtime_initialization_failed"
      error.message should include("deliberate startup failure")
      lifecycle.initializeCount shouldBe 1
      lifecycle.shutdownCount shouldBe 1
      hooks.registrationCount shouldBe 1
      hooks.removalCount shouldBe 1
      Files.exists(config.runtimeDirectory.resolve("brain.conf")) shouldBe false
    }
  }

  test("generates an absolute restrictive brain configuration") {
    withRuntimeConfig { config =>
      val lifecycle = new RecordingBrainLifecycle
      val owner = RuntimeOwner
        .isolatedForTesting(lifecycle, new RecordingShutdownHooks)
        .start(config)
        .value

      lifecycle.configPath.isAbsolute shouldBe true
      lifecycle.nativeLibraryPath.isAbsolute shouldBe true
      val brainConfig = ConfigFactory.parseFile(lifecycle.configPath.toFile)
      brainConfig.getBoolean("opencomputers.internet.enableHttp") shouldBe false
      brainConfig.getBoolean("opencomputers.internet.enableTcp") shouldBe false
      brainConfig.getBoolean("opencomputers.filesystem.bufferChanges") shouldBe false

      owner.close()
    }
  }

  private def withRuntimeConfig(testBody: RuntimeConfig => Unit): Unit = {
    val root = Files.createTempDirectory("ocelot-harness-runtime-owner-")
    try {
      testBody(
        RuntimeConfig(
          runtimeDirectory = root.resolve("runtime"),
          nativeLibraryDirectory = root.resolve("native-libraries")
        )
      )
    } finally {
      deleteRecursively(root)
    }
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }

  private final class RecordingBrainLifecycle extends BrainLifecycle {
    var initializeCount = 0
    var shutdownCount = 0
    var initializeFailure: Option[RuntimeException] = None
    var beforeShutdown: () => Unit = () => ()
    var configPath: Path = _
    var nativeLibraryPath: Path = _

    override def initialize(brainConfigPath: Path, librariesPath: Path): Unit = {
      initializeCount += 1
      configPath = brainConfigPath
      nativeLibraryPath = librariesPath
      initializeFailure.foreach(throw _)
    }

    override def shutdown(): Unit = {
      beforeShutdown()
      shutdownCount += 1
    }

    override def nativeLuaAvailable: Boolean = true
  }

  private final class RecordingShutdownHooks extends ShutdownHooks {
    var registrationCount = 0
    var removalCount = 0
    private var registeredHook: Option[() => Unit] = None

    override def register(closeOwner: () => Unit): AutoCloseable = {
      registrationCount += 1
      registeredHook = Some(closeOwner)
      new AutoCloseable {
        override def close(): Unit = removalCount += 1
      }
    }

    def runRegisteredHook(): Unit = registeredHook.getOrElse(fail("no shutdown hook registered"))()
  }
}
