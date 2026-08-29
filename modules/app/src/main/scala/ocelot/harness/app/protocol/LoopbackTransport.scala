package ocelot.harness.app.protocol

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.lang.management.ManagementFactory
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, SocketTimeoutException}
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{
  AclEntry,
  AclEntryPermission,
  AclEntryType,
  AclFileAttributeView,
  PosixFileAttributeView,
  PosixFilePermissions
}
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.SecureRandom
import java.util.{Base64, Collections, EnumSet, UUID}
import java.util.concurrent.{ArrayBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.util.control.NonFatal

import ocelot.harness.core.BuildIdentity
import ocelot.harness.core.workspace.HarnessSession

private[app] final case class ConnectionMetadata(
    host: String,
    port: Int,
    protocolMajor: Int,
    projectId: String,
    pid: Long,
    processStartMillis: Long,
    instanceId: String,
    token: String
) {
  def json: ujson.Value = ujson.Obj(
    "host" -> host,
    "port" -> port,
    "protocolMajor" -> protocolMajor,
    "projectId" -> projectId,
    "pid" -> ujson.Num(pid.toDouble),
    "processStartMillis" -> ujson.Num(processStartMillis.toDouble),
    "instanceId" -> instanceId,
    "token" -> token
  )
}

private[app] object ConnectionMetadata {
  val FileName = "connection.json"

  def read(projectRoot: Path): Either[String, ConnectionMetadata] = {
    val path = runRoot(projectRoot).resolve(FileName)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      Left("service connection metadata is unavailable")
    else {
      try {
        val value = ujson.read(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).obj
        validate(
          ConnectionMetadata(
            value("host").str,
            value("port").num.toInt,
            value("protocolMajor").num.toInt,
            value("projectId").str,
            value("pid").num.toLong,
            value("processStartMillis").num.toLong,
            value("instanceId").str,
            value("token").str
          )
        )
      } catch {
        case NonFatal(error) => Left(s"invalid service connection metadata: ${message(error)}")
      }
    }
  }

  def runRoot(projectRoot: Path): Path =
    projectRoot.toAbsolutePath.normalize().resolve(".ocelot-harness").resolve("run")

  private def validate(metadata: ConnectionMetadata): Either[String, ConnectionMetadata] = {
    val valid =
      metadata.host == "127.0.0.1" && metadata.port > 0 && metadata.port <= 65535 &&
        metadata.protocolMajor == BuildIdentity.ProtocolVersion && metadata.projectId.nonEmpty &&
        metadata.pid > 0L && metadata.processStartMillis > 0L && metadata.instanceId.nonEmpty &&
        metadata.token.length >= 40
    if (valid) Right(metadata) else Left("invalid service connection metadata fields")
  }

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

private[app] final class ProjectOwnerLease private (
    val runRoot: Path,
    val pid: Long,
    val processStartMillis: Long,
    val instanceId: String,
    val token: String,
    channel: FileChannel,
    lock: FileLock
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)

  def publish(port: Int, projectId: String): ConnectionMetadata = synchronized {
    require(port > 0, "service port must be positive")
    require(projectId != null && projectId.nonEmpty, "project ID is required")
    val metadata = ConnectionMetadata(
      "127.0.0.1",
      port,
      BuildIdentity.ProtocolVersion,
      projectId,
      pid,
      processStartMillis,
      instanceId,
      token
    )
    val destination = runRoot.resolve(ConnectionMetadata.FileName)
    val temporary = Files.createTempFile(runRoot, ".connection-", ".tmp")
    try {
      Files.write(temporary, (ujson.write(metadata.json) + "\n").getBytes(StandardCharsets.UTF_8))
      restrictToOwner(temporary)
      try
        Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      catch {
        case _: AtomicMoveNotSupportedException =>
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
      }
      metadata
    } finally Files.deleteIfExists(temporary)
  }

  override def close(): Unit = synchronized {
    if (closed.compareAndSet(false, true)) {
      val metadataPath = runRoot.resolve(ConnectionMetadata.FileName)
      ConnectionMetadata.read(runRoot.getParent.getParent) match {
        case Right(metadata) if metadata.instanceId == instanceId =>
          Files.deleteIfExists(metadataPath)
        case _ =>
      }
      try lock.release()
      finally channel.close()
    }
  }

  private def restrictToOwner(path: Path): Unit = {
    val posix = Files.getFileAttributeView(
      path,
      classOf[PosixFileAttributeView],
      LinkOption.NOFOLLOW_LINKS
    )
    if (posix != null) {
      posix.setPermissions(PosixFilePermissions.fromString("rw-------"))
    } else {
      val acl = Files.getFileAttributeView(
        path,
        classOf[AclFileAttributeView],
        LinkOption.NOFOLLOW_LINKS
      )
      if (acl == null)
        throw new IllegalStateException("owner-only metadata permissions unavailable")
      val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
      val entry = AclEntry
        .newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(EnumSet.allOf(classOf[AclEntryPermission]))
        .build()
      acl.setAcl(Collections.singletonList(entry))
    }
  }
}

private[app] object ProjectOwnerLease {
  private val random = new SecureRandom()

  def acquire(projectRoot: Path): Either[String, ProjectOwnerLease] = {
    val runRoot = ConnectionMetadata.runRoot(projectRoot)
    Files.createDirectories(runRoot)
    val lockPath = runRoot.resolve("owner.lock")
    val channel = FileChannel.open(
      lockPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
    val lock =
      try channel.tryLock(0L, 1L, false)
      catch {
        case _: OverlappingFileLockException => null
      }
    if (lock == null) {
      channel.close()
      Left("another live owner already controls this project")
    } else {
      try {
        val priorIdentity = readIdentity(channel)
        val metadataPath = runRoot.resolve(ConnectionMetadata.FileName)
        val staleMetadata =
          if (Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
            ConnectionMetadata.read(projectRoot) match {
              case Right(metadata) => Some(metadata)
              case Left(message)   => throw new IllegalStateException(message)
            }
          } else None
        staleMetadata.foreach { metadata =>
          val matches = priorIdentity.exists(identity =>
            identity.instanceId == metadata.instanceId && identity.pid == metadata.pid &&
              identity.processStartMillis == metadata.processStartMillis
          )
          if (!matches) {
            throw new IllegalStateException(
              "stale connection metadata does not match the verified prior owner identity"
            )
          }
          Files.deleteIfExists(runRoot.resolve(ConnectionMetadata.FileName))
        }

        val identity = OwnerIdentity(
          currentPid,
          ManagementFactory.getRuntimeMXBean.getStartTime,
          UUID.randomUUID().toString
        )
        writeIdentity(channel, identity)
        val tokenBytes = new Array[Byte](32)
        random.nextBytes(tokenBytes)
        Right(
          new ProjectOwnerLease(
            runRoot,
            identity.pid,
            identity.processStartMillis,
            identity.instanceId,
            Base64.getUrlEncoder.withoutPadding().encodeToString(tokenBytes),
            channel,
            lock
          )
        )
      } catch {
        case NonFatal(error) =>
          try lock.release()
          finally channel.close()
          Left(message(error))
      }
    }
  }

  def verifyLive(
      projectRoot: Path,
      metadata: ConnectionMetadata
  ): Either[String, Unit] = {
    val lockPath = ConnectionMetadata.runRoot(projectRoot).resolve("owner.lock")
    if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
      Left("service owner identity is unavailable")
    } else {
      val channel = FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE)
      try {
        val candidate =
          try channel.tryLock(0L, 1L, false)
          catch {
            case _: OverlappingFileLockException => null
          }
        if (candidate != null) {
          candidate.release()
          Left("service owner is no longer live")
        } else {
          readIdentity(channel) match {
            case Some(identity)
                if identity.instanceId == metadata.instanceId && identity.pid == metadata.pid &&
                  identity.processStartMillis == metadata.processStartMillis =>
              Right(())
            case _ => Left("live owner identity does not match connection metadata")
          }
        }
      } catch {
        case NonFatal(error) => Left(message(error))
      } finally channel.close()
    }
  }

  private def readIdentity(channel: FileChannel): Option[OwnerIdentity] = {
    val size = channel.size() - 1L
    if (size <= 0L || size > 4096L) None
    else {
      val bytes = ByteBuffer.allocate(size.toInt)
      channel.position(1L)
      while (bytes.hasRemaining && channel.read(bytes) >= 0) {}
      try {
        val value = ujson.read(new String(bytes.array(), StandardCharsets.UTF_8)).obj
        Some(
          OwnerIdentity(
            value("pid").num.toLong,
            value("processStartMillis").num.toLong,
            value("instanceId").str
          )
        )
      } catch {
        case NonFatal(_) => None
      }
    }
  }

  private def writeIdentity(channel: FileChannel, identity: OwnerIdentity): Unit = {
    val bytes = (
      ujson.write(
        ujson.Obj(
          "pid" -> ujson.Num(identity.pid.toDouble),
          "processStartMillis" -> ujson.Num(identity.processStartMillis.toDouble),
          "instanceId" -> identity.instanceId
        )
      ) + "\n"
    ).getBytes(StandardCharsets.UTF_8)
    channel.truncate(0L)
    channel.position(1L)
    channel.write(ByteBuffer.wrap(bytes))
    channel.force(true)
  }

  private def currentPid: Long = {
    val runtimeName = ManagementFactory.getRuntimeMXBean.getName
    val prefix = runtimeName.takeWhile(_.isDigit)
    if (prefix.isEmpty) throw new IllegalStateException("unable to determine service process ID")
    prefix.toLong
  }

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  private final case class OwnerIdentity(pid: Long, processStartMillis: Long, instanceId: String)
}

private[app] object LoopbackServer {
  def serve(session: HarnessSession, lease: ProjectOwnerLease): Unit = {
    val address = InetAddress.getByName("127.0.0.1")
    val server = new ServerSocket()
    val running = new AtomicBoolean(true)
    val executor = new ThreadPoolExecutor(
      1,
      4,
      30L,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue[Runnable](32),
      daemonThreadFactory,
      new ThreadPoolExecutor.AbortPolicy()
    )
    try {
      server.setReuseAddress(false)
      server.bind(new InetSocketAddress(address, 0), 32)
      server.setSoTimeout(250)
      lease.publish(server.getLocalPort, session.describe().projectId.value)
      while (running.get()) {
        try {
          val socket = server.accept()
          try executor.execute(() => serveConnection(socket, session, lease.token, running))
          catch {
            case NonFatal(_) => socket.close()
          }
        } catch {
          case _: SocketTimeoutException =>
        }
      }
    } finally {
      try server.close()
      finally {
        executor.shutdown()
        if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
          executor.shutdownNow()
          executor.awaitTermination(5L, TimeUnit.SECONDS)
        }
      }
    }
  }

  private def serveConnection(
      socket: Socket,
      session: HarnessSession,
      token: String,
      running: AtomicBoolean
  ): Unit = {
    try {
      socket.setSoTimeout(30000)
      val endpoint = new JsonRpcEndpoint(session, Some(token), () => running.set(false))
      StdioTransport.serve(
        socket.getInputStream,
        socket.getOutputStream,
        endpoint,
        () => running.get()
      )
    } catch {
      case _: SocketTimeoutException =>
      case NonFatal(_)               =>
    } finally {
      try socket.close()
      catch {
        case NonFatal(_) =>
      }
    }
  }

  private def daemonThreadFactory: ThreadFactory = new ThreadFactory {
    private val sequence = new AtomicInteger(0)
    override def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, s"ocelot-harness-loopback-${sequence.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }
}

private[app] final case class RpcClientFailure(
    exitCode: Int,
    message: String,
    harnessCode: Option[String] = None
)

private[app] object LoopbackClient {
  def call(
      projectRoot: Path,
      method: String,
      params: ujson.Obj = ujson.Obj()
  ): Either[RpcClientFailure, ujson.Value] =
    ConnectionMetadata
      .read(projectRoot)
      .left
      .map(message => RpcClientFailure(AppExitCode.Connection, message))
      .flatMap(metadata => call(metadata, method, params))

  def ping(projectRoot: Path): Either[RpcClientFailure, ujson.Value] =
    call(projectRoot, "workspace.describe")

  private def call(
      metadata: ConnectionMetadata,
      method: String,
      params: ujson.Obj
  ): Either[RpcClientFailure, ujson.Value] = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress(metadata.host, metadata.port), 3000)
      socket.setSoTimeout(30000)
      val writer = new BufferedWriter(
        new OutputStreamWriter(socket.getOutputStream, StandardCharsets.UTF_8)
      )
      val reader = new BufferedReader(
        new InputStreamReader(socket.getInputStream, StandardCharsets.UTF_8)
      )
      exchange(
        writer,
        reader,
        ujson.Str("handshake"),
        "harness.version",
        ujson.Obj("protocolMajor" -> BuildIdentity.ProtocolVersion, "token" -> metadata.token)
      ).flatMap { version =>
        if (method == "harness.version") Right(version)
        else exchange(writer, reader, ujson.Str("request"), method, params)
      }
    } catch {
      case NonFatal(error) =>
        Left(
          RpcClientFailure(AppExitCode.Connection, s"service connection failed: ${message(error)}")
        )
    } finally {
      try socket.close()
      catch {
        case NonFatal(_) =>
      }
    }
  }

  private def exchange(
      writer: BufferedWriter,
      reader: BufferedReader,
      id: ujson.Value,
      method: String,
      params: ujson.Obj
  ): Either[RpcClientFailure, ujson.Value] = {
    writer.write(
      ujson.write(
        ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)
      )
    )
    writer.newLine()
    writer.flush()
    val line = reader.readLine()
    if (line == null)
      Left(RpcClientFailure(AppExitCode.Connection, "service closed the connection"))
    else decodeResponse(line, id)
  }

  private def decodeResponse(
      line: String,
      expectedId: ujson.Value
  ): Either[RpcClientFailure, ujson.Value] = {
    try {
      val response = ujson.read(line).obj
      if (
        response
          .get("jsonrpc")
          .forall(_ != ujson.Str("2.0")) || response.get("id").forall(_ != expectedId)
      ) {
        Left(RpcClientFailure(AppExitCode.Protocol, "invalid JSON-RPC response correlation"))
      } else
        response.get("result") match {
          case Some(result) => Right(result)
          case None =>
            response.get("error") match {
              case Some(error: ujson.Obj) =>
                val code = error.value.get("code").collect { case ujson.Num(value) => value.toInt }
                val text = error.value
                  .get("message")
                  .collect { case ujson.Str(value) => value }
                  .getOrElse("protocol request failed")
                val harnessCode = error.value
                  .get("data")
                  .collect { case value: ujson.Obj => value }
                  .flatMap(_.value.get("harnessCode"))
                  .collect { case ujson.Str(value) => value }
                val exit = if (code.contains(-32000)) AppExitCode.Domain else AppExitCode.Protocol
                Left(RpcClientFailure(exit, text, harnessCode))
              case _ =>
                Left(
                  RpcClientFailure(AppExitCode.Protocol, "JSON-RPC response has no result or error")
                )
            }
        }
    } catch {
      case NonFatal(error) =>
        Left(
          RpcClientFailure(AppExitCode.Protocol, s"invalid JSON-RPC response: ${message(error)}")
        )
    }
  }

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}

private[app] object AppExitCode {
  val Success = 0
  val Usage = 2
  val Connection = 3
  val Protocol = 4
  val Domain = 5
  val Runtime = 6
}
