package ocelot.harness.app.protocol

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ProjectOwnerLeaseSpec extends AnyFunSuite with Matchers {
  test("matching stale metadata is removed only after the prior owner identity is verified") {
    val project = Files.createTempDirectory("ocelot-harness-stale-owner-")
    try {
      writePriorOwner(project, "same-instance", "same-instance")
      val lease = ProjectOwnerLease.acquire(project).fold(fail(_), identity)
      try {
        Files.exists(
          ConnectionMetadata.runRoot(project).resolve(ConnectionMetadata.FileName)
        ) shouldBe false
      } finally lease.close()
    } finally deleteRecursively(project)
  }

  test("mismatched stale metadata is preserved and rejected") {
    val project = Files.createTempDirectory("ocelot-harness-mismatched-owner-")
    try {
      writePriorOwner(project, "lock-instance", "metadata-instance")
      val result = ProjectOwnerLease.acquire(project)
      result.left.toOption.get should include("does not match")
      Files.exists(
        ConnectionMetadata.runRoot(project).resolve(ConnectionMetadata.FileName)
      ) shouldBe true
    } finally deleteRecursively(project)
  }

  test("invalid stale metadata is preserved instead of overwritten") {
    val project = Files.createTempDirectory("ocelot-harness-invalid-owner-")
    try {
      val runRoot = ConnectionMetadata.runRoot(project)
      Files.createDirectories(runRoot)
      Files.write(
        runRoot.resolve("owner.lock"),
        " {}\n".getBytes(StandardCharsets.UTF_8)
      )
      val metadata = runRoot.resolve(ConnectionMetadata.FileName)
      Files.write(metadata, "not-json\n".getBytes(StandardCharsets.UTF_8))

      ProjectOwnerLease.acquire(project).left.toOption.get should include(
        "invalid service connection metadata"
      )
      new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8) shouldBe "not-json\n"
    } finally deleteRecursively(project)
  }

  test("a live owner must match metadata before force-stop is allowed") {
    val project = Files.createTempDirectory("ocelot-harness-live-owner-")
    try {
      val lease = ProjectOwnerLease.acquire(project).fold(fail(_), identity)
      try {
        val metadata = lease.publish(12345, "test-project")
        ProjectOwnerLease.verifyLive(project, metadata) shouldBe Right(())
        ProjectOwnerLease
          .verifyLive(project, metadata.copy(instanceId = "other"))
          .left
          .toOption
          .get should include("does not match")
      } finally lease.close()
    } finally deleteRecursively(project)
  }

  private def writePriorOwner(
      project: Path,
      lockInstance: String,
      metadataInstance: String
  ): Unit = {
    val runRoot = ConnectionMetadata.runRoot(project)
    Files.createDirectories(runRoot)
    val identity = ujson.Obj(
      "pid" -> 123,
      "processStartMillis" -> ujson.Num(456d),
      "instanceId" -> lockInstance
    )
    Files.write(
      runRoot.resolve("owner.lock"),
      (" " + ujson.write(identity) + "\n").getBytes(StandardCharsets.UTF_8)
    )
    val metadata = ujson.Obj(
      "host" -> "127.0.0.1",
      "port" -> 12345,
      "protocolMajor" -> 1,
      "projectId" -> "test-project",
      "pid" -> 123,
      "processStartMillis" -> ujson.Num(456d),
      "instanceId" -> metadataInstance,
      "token" -> ("t" * 43)
    )
    Files.write(
      runRoot.resolve(ConnectionMetadata.FileName),
      (ujson.write(metadata) + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }

  private def deleteRecursively(root: Path): Unit = {
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
