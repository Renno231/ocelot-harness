package ocelot.harness.app

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters._

import ocelot.harness.core.runtime.{RuntimeConfig, RuntimeOwner}

object BrainLifecycleProbe {
  def main(arguments: Array[String]): Unit = {
    require(
      arguments.length == 3,
      "expected native-library, runtime, and empty-project directory arguments"
    )

    val nativeLibraryDirectory = Paths.get(arguments(0)).toAbsolutePath.normalize()
    val runtimeDirectory = Paths.get(arguments(1)).toAbsolutePath.normalize()
    val projectDirectory = Paths.get(arguments(2)).toAbsolutePath.normalize()
    Files.createDirectories(projectDirectory)

    val owner = RuntimeOwner
      .start(RuntimeConfig(runtimeDirectory, nativeLibraryDirectory))
      .fold(error => throw new IllegalStateException(s"${error.code}: ${error.message}"), identity)

    try {
      Console.out.println(s"BRAIN_LIFECYCLE_INITIALIZED version=${owner.brainVersion}")
      require(owner.nativeLuaAvailable, "no compatible native Lua library was loaded")
      Console.out.println("BRAIN_NATIVE_LUA_AVAILABLE=true")

      val session = owner
        .openProject(projectDirectory)
        .fold(
          error => throw new IllegalStateException(s"${error.code}: ${error.message}"),
          identity
        )
      Console.out.println("BRAIN_EMPTY_SESSION_OPENED")
      session.close()
      Console.out.println("BRAIN_EMPTY_SESSION_CLOSED")
    } finally {
      owner.close()
      Console.out.println("BRAIN_LIFECYCLE_SHUTDOWN")
    }

    val currentThread = Thread.currentThread()
    val liveHarnessThreads = Thread.getAllStackTraces
      .keySet()
      .asScala
      .count(thread =>
        (thread ne currentThread) && thread.isAlive && !thread.isDaemon && thread.getName
          .startsWith("ocelot-harness-")
      )
    Console.out.println(s"HARNESS_NON_DAEMON_THREADS=$liveHarnessThreads")
  }
}
