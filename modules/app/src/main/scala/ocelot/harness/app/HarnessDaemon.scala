package ocelot.harness.app

import java.nio.file.{AccessDeniedException, Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import ocelot.harness.app.protocol._
import ocelot.harness.core.HarnessError
import ocelot.harness.core.runtime.{RuntimeConfig, RuntimeOwner}
import ocelot.harness.core.workspace.HarnessSession

object HarnessDaemon {
  def main(arguments: Array[String]): Unit = {
    if (arguments.length == 3 && !arguments.exists(_.startsWith("--"))) {
      BrainLifecycleProbe.main(arguments)
    } else {
      val exitCode = run(arguments)
      if (exitCode != AppExitCode.Success) System.exit(exitCode)
    }
  }

  private[app] def run(arguments: Array[String]): Int =
    parse(arguments.toVector) match {
      case Left(message)                     => fail(AppExitCode.Usage, message)
      case Right(ServeStdio(projectRoot))    => serveStdio(projectRoot)
      case Right(ServeLoopback(projectRoot)) => serveLoopback(projectRoot)
      case Right(Up(projectRoot))            => up(projectRoot)
      case Right(Down(projectRoot, json))    => down(projectRoot, json)
      case Right(Status(projectRoot, json))  => status(projectRoot, json)
      case Right(ForceStop(projectRoot))     => forceStop(projectRoot)
    }

  private def serveStdio(projectRoot: Path): Int = {
    val protocolOutput = System.out
    System.setOut(System.err)
    ProjectOwnerLease.acquire(projectRoot) match {
      case Left(message) => fail(AppExitCode.Connection, message)
      case Right(lease) =>
        try {
          ManagedProject.open(projectRoot) match {
            case Left(error) => fail(AppExitCode.Runtime, s"${error.code}: ${error.message}")
            case Right(project) =>
              val running = new AtomicBoolean(true)
              val endpoint = new JsonRpcEndpoint(project.session, None, () => running.set(false))
              try {
                StdioTransport.serve(System.in, protocolOutput, endpoint, () => running.get())
                AppExitCode.Success
              } catch {
                case NonFatal(error) => fail(AppExitCode.Runtime, errorMessage(error))
              } finally project.close()
          }
        } finally lease.close()
    }
  }

  private def serveLoopback(projectRoot: Path): Int = {
    System.setOut(System.err)
    ProjectOwnerLease.acquire(projectRoot) match {
      case Left(message) => fail(AppExitCode.Connection, message)
      case Right(lease) =>
        try {
          ManagedProject.open(projectRoot) match {
            case Left(error) => fail(AppExitCode.Runtime, s"${error.code}: ${error.message}")
            case Right(project) =>
              try {
                LoopbackServer.serve(project.session, lease)
                AppExitCode.Success
              } catch {
                case NonFatal(error) => fail(AppExitCode.Runtime, errorMessage(error))
              } finally project.close()
          }
        } finally lease.close()
    }
  }

  private def up(projectRoot: Path): Int = {
    LoopbackClient.ping(projectRoot) match {
      case Right(_) =>
        fail(AppExitCode.Connection, "another live owner already controls this project")
      case Left(_) =>
        val runRoot = ConnectionMetadata.runRoot(projectRoot)
        try {
          Files.createDirectories(runRoot)
          val log = runRoot.resolve("service.log")
          val command = Vector(
            javaExecutable.toString,
            "-cp",
            System.getProperty("java.class.path"),
            "ocelot.harness.app.HarnessDaemon",
            "serve",
            "--loopback",
            "--project",
            projectRoot.toString
          )
          val process = new ProcessBuilder(command: _*)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile))
            .start()
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
          var ready = false
          var lastConnectionError: Option[String] = None
          while (!ready && process.isAlive && System.nanoTime() < deadline) {
            LoopbackClient.ping(projectRoot) match {
              case Right(_)    => ready = true
              case Left(error) => lastConnectionError = Some(error.message)
            }
            if (!ready) Thread.sleep(25L)
          }
          if (ready) {
            System.out.println(
              s"started project service for ${projectRoot.toAbsolutePath.normalize()}"
            )
            AppExitCode.Success
          } else {
            if (process.isAlive) process.destroyForcibly()
            val detail = tail(log)
              .orElse(lastConnectionError)
              .getOrElse("service did not become ready within 30 seconds")
            fail(AppExitCode.Runtime, detail)
          }
        } catch {
          case NonFatal(error) => fail(AppExitCode.Runtime, errorMessage(error))
        }
    }
  }

  private def down(projectRoot: Path, json: Boolean): Int = {
    val metadataBefore = ConnectionMetadata.read(projectRoot).toOption
    LoopbackClient.call(projectRoot, "service.shutdown") match {
      case Left(error) => fail(error.exitCode, error.message, error.harnessCode)
      case Right(_) =>
        val metadataPath =
          ConnectionMetadata.runRoot(projectRoot).resolve(ConnectionMetadata.FileName)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (
          (Files.exists(metadataPath) || metadataBefore
            .exists(value => processIsAlive(value.pid))) &&
          System.nanoTime() < deadline
        ) Thread.sleep(25L)
        if (Files.exists(metadataPath) || metadataBefore.exists(value => processIsAlive(value.pid)))
          fail(AppExitCode.Runtime, "service did not stop within 10 seconds")
        else {
          if (json) System.out.println(ujson.write(ujson.Obj("running" -> false)))
          else System.out.println("stopped project service")
          AppExitCode.Success
        }
    }
  }

  private def status(projectRoot: Path, json: Boolean): Int =
    LoopbackClient.ping(projectRoot) match {
      case Right(description) =>
        if (json)
          System.out.println(
            ujson.write(
              ujson.Obj(
                "running" -> true,
                "projectId" -> description("projectId"),
                "protocolMajor" -> ocelot.harness.core.BuildIdentity.ProtocolVersion
              )
            )
          )
        else System.out.println(s"running project ${description("projectId").str}")
        AppExitCode.Success
      case Left(error) =>
        if (json) System.out.println(ujson.write(ujson.Obj("running" -> false)))
        else System.err.println(s"ERROR: ${error.message}")
        AppExitCode.Connection
    }

  private def forceStop(projectRoot: Path): Int =
    ConnectionMetadata.read(projectRoot) match {
      case Left(message) => fail(AppExitCode.Connection, message)
      case Right(metadata) =>
        ProjectOwnerLease.verifyLive(projectRoot, metadata) match {
          case Left(message) => fail(AppExitCode.Connection, message)
          case Right(_) =>
            try {
              val command =
                if (isWindows)
                  Vector("taskkill", "/PID", metadata.pid.toString, "/T", "/F")
                else Vector("kill", "-TERM", metadata.pid.toString)
              val process = new ProcessBuilder(command: _*).redirectErrorStream(true).start()
              if (!process.waitFor(10L, TimeUnit.SECONDS) || process.exitValue() != 0)
                fail(AppExitCode.Runtime, "forced service stop failed")
              else {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
                var cleaned = false
                while (!cleaned && System.nanoTime() < deadline) {
                  ProjectOwnerLease.acquire(projectRoot) match {
                    case Right(lease) => lease.close(); cleaned = true
                    case Left(_)      => Thread.sleep(25L)
                  }
                }
                if (!cleaned)
                  fail(AppExitCode.Runtime, "service stopped but ownership did not clear")
                else {
                  System.out.println("force-stopped project service")
                  AppExitCode.Success
                }
              }
            } catch {
              case NonFatal(error) => fail(AppExitCode.Runtime, errorMessage(error))
            }
        }
    }

  private def parse(arguments: Vector[String]): Either[String, Command] = arguments match {
    case Vector("serve", "--stdio", "--project", project) =>
      Right(ServeStdio(path(project)))
    case Vector("serve", "--loopback", "--project", project) =>
      Right(ServeLoopback(path(project)))
    case Vector("up", "--project", project)             => Right(Up(path(project)))
    case Vector("down", "--project", project)           => Right(Down(path(project), json = false))
    case Vector("down", "--project", project, "--json") => Right(Down(path(project), json = true))
    case Vector("status", "--project", project) => Right(Status(path(project), json = false))
    case Vector("status", "--project", project, "--json") =>
      Right(Status(path(project), json = true))
    case Vector("force-stop", "--project", project) => Right(ForceStop(path(project)))
    case _ =>
      Left(
        "usage: ocelot-harnessd (serve --stdio|--loopback | up | down | status | force-stop) --project <path> [--json]"
      )
  }

  private def path(value: String): Path = Paths.get(value).toAbsolutePath.normalize()

  private def javaExecutable: Path = {
    val executable = if (isWindows) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", executable)
  }

  private def isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  private def processIsAlive(pid: Long): Boolean = {
    try {
      if (isWindows) {
        val process = new ProcessBuilder(
          "tasklist",
          "/FI",
          s"PID eq $pid",
          "/FO",
          "CSV",
          "/NH"
        ).redirectErrorStream(true).start()
        val bytes = new java.io.ByteArrayOutputStream()
        val input = process.getInputStream
        val buffer = new Array[Byte](4096)
        var count = input.read(buffer)
        while (count >= 0) {
          if (count > 0) bytes.write(buffer, 0, count)
          count = input.read(buffer)
        }
        process.waitFor(5L, TimeUnit.SECONDS)
        new String(bytes.toByteArray, java.nio.charset.StandardCharsets.UTF_8)
          .contains(s"\"$pid\"")
      } else {
        val process = new ProcessBuilder("kill", "-0", pid.toString).start()
        process.waitFor(5L, TimeUnit.SECONDS) && process.exitValue() == 0
      }
    } catch {
      case NonFatal(_) => true
    }
  }

  private def tail(path: Path): Option[String] =
    if (!Files.isRegularFile(path)) None
    else {
      val lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8).asScala
      lines.reverse.find(_.trim.nonEmpty)
    }

  private def fail(code: Int, message: String, harnessCode: Option[String] = None): Int = {
    val suffix = harnessCode.map(value => s" [$value]").getOrElse("")
    System.err.println(s"ERROR$suffix: $message")
    code
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private sealed trait Command
  private final case class ServeStdio(projectRoot: Path) extends Command
  private final case class ServeLoopback(projectRoot: Path) extends Command
  private final case class Up(projectRoot: Path) extends Command
  private final case class Down(projectRoot: Path, json: Boolean) extends Command
  private final case class Status(projectRoot: Path, json: Boolean) extends Command
  private final case class ForceStop(projectRoot: Path) extends Command
}

private[app] final class ManagedProject private (
    val session: HarnessSession,
    owner: RuntimeOwner,
    processDirectory: Path
) extends AutoCloseable {
  override def close(): Unit = {
    var failure: Option[Throwable] = None
    try owner.close()
    catch {
      case NonFatal(error) => failure = Some(error)
    }
    try ManagedProject.deleteRecursively(processDirectory)
    catch {
      case _: AccessDeniedException           =>
      case NonFatal(error) if failure.isEmpty => failure = Some(error)
      case NonFatal(_)                        =>
    }
    failure.foreach(throw _)
  }
}

private[app] object ManagedProject {
  def open(projectRoot: Path): Either[HarnessError, ManagedProject] = {
    val normalized = projectRoot.toAbsolutePath.normalize()
    val runRoot = normalized.resolve(".ocelot-harness").resolve("run")
    var processDirectory: Option[Path] = None
    var owner: Option[RuntimeOwner] = None
    try {
      Files.createDirectories(runRoot)
      cleanStaleProcessDirectories(runRoot)
      val directory = Files.createDirectory(runRoot.resolve(s"process-${UUID.randomUUID()}"))
      processDirectory = Some(directory)
      RuntimeOwner.start(
        RuntimeConfig(directory.resolve("runtime"), directory.resolve("native-libraries"))
      ) match {
        case Left(error) => Left(error)
        case Right(runtimeOwner) =>
          owner = Some(runtimeOwner)
          runtimeOwner.openProject(normalized) match {
            case Left(error) => Left(error)
            case Right(session) =>
              owner = None
              processDirectory = None
              Right(new ManagedProject(session, runtimeOwner, directory))
          }
      }
    } finally {
      owner.foreach(value =>
        try value.close()
        catch {
          case NonFatal(_) =>
        }
      )
      processDirectory.foreach(path =>
        try deleteRecursively(path)
        catch {
          case NonFatal(_) =>
        }
      )
    }
  }

  private def cleanStaleProcessDirectories(runRoot: Path): Unit = {
    val entries = Files.list(runRoot)
    try {
      entries
        .iterator()
        .asScala
        .filter(path => Files.isDirectory(path) && path.getFileName.toString.startsWith("process-"))
        .foreach(path =>
          try deleteRecursively(path)
          catch {
            case _: AccessDeniedException =>
          }
        )
    } finally entries.close()
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
