package ocelot.harness.core.artifact

import java.awt.image.BufferedImage
import java.io.{IOException, StringReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import ocelot.harness.core.project.ScreenId
import ocelot.harness.core.workspace.{ScreenCell, ScreenSnapshot}

final class ScreenArtifactSpec extends AnyFunSuite with Matchers with EitherValues {
  private val screenId = ScreenId.parse("main").value

  test("font parser and renderer preserve exact glyph and cell colors") {
    val font = GlyphFont
      .parse(
        new StringReader(
          "0041:80402010080402010000000000000000\n" +
            "754C:80004000200010000800040002000100" +
            "00010002000400080010002000400080\n"
        )
      )
      .value
    val snapshot = screen(
      width = 3,
      cells = Vector(
        ScreenCell('A', 0x112233, 0x445566, 0.toShort),
        ScreenCell(0x754c, 0xff0000, 0x0000ff, 0.toShort),
        ScreenCell(' ', 0xffffff, 0x010203, 0.toShort)
      )
    )

    val image = ScreenRenderer.render(snapshot, RenderOptions(scale = 1), font).value
    image.getWidth shouldBe 24
    image.getHeight shouldBe 16
    rgb(image, 0, 0) shouldBe 0x112233
    rgb(image, 1, 0) shouldBe 0x445566
    rgb(image, 8, 0) shouldBe 0xff0000
    rgb(image, 9, 0) shouldBe 0x0000ff
    rgb(image, 23, 15) shouldBe 0x0000ff
  }

  test("wide glyph at the final cell is safely clipped to the visible screen") {
    val font = GlyphFont
      .parse(
        new StringReader("754C:8000000000000000000000000000000000000000000000000000000000000000\n")
      )
      .value
    val snapshot = screen(
      width = 1,
      cells = Vector(ScreenCell(0x754c, 0xabcdef, 0x123456, 0.toShort))
    )

    val image = ScreenRenderer.render(snapshot, RenderOptions(), font).value
    image.getWidth shouldBe 8
    rgb(image, 0, 0) shouldBe 0xabcdef
    rgb(image, 7, 0) shouldBe 0x123456
  }

  test("powered-off rendering is black and scaling preserves exact pixels") {
    val font = GlyphFont.parse(new StringReader("0041:80000000000000000000000000000000\n")).value
    val snapshot = screen(
      width = 1,
      cells = Vector(ScreenCell('A', 0xffffff, 0x123456, 0.toShort)),
      powered = false
    )

    val image = ScreenRenderer.render(snapshot, RenderOptions(scale = 2), font).value
    image.getWidth shouldBe 16
    image.getHeight shouldBe 32
    allPixels(image) should contain only 0x000000
  }

  test("renderer rejects invalid dimensions and capture limits") {
    val font = GlyphFont.parse(new StringReader("0041:80000000000000000000000000000000\n")).value
    ScreenRenderer
      .render(screen(1, Vector.empty), RenderOptions(), font)
      .left
      .value
      .code shouldBe "invalid_capture"
    ScreenRenderer
      .render(screen(1, Vector(ScreenCell('A', 1, 0, 0))), RenderOptions(maxPixels = 100), font)
      .left
      .value
      .message should include("pixel limit")
    ScreenRenderer
      .render(
        screen(1, Vector(ScreenCell('A', 1, 0, 0))).copy(
          width = Int.MaxValue,
          height = Int.MaxValue
        ),
        RenderOptions(maxPixels = Long.MaxValue),
        font
      )
      .left
      .value
      .code shouldBe "invalid_capture"
  }

  test("artifact writes are contained, atomic, bounded, and checksummed") {
    withTempDirectory { root =>
      val store = new ArtifactStore(root, maxArtifactBytes = 1024L, allowedRoot = root)
      val image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
      image.setRGB(0, 0, 0xff123456.toInt)

      val artifact = store.writePng("screens/main.png", image).value
      val written = root.resolve(artifact.relativePath)
      artifact.relativePath shouldBe "screens/main.png"
      artifact.mediaType shouldBe "image/png"
      artifact.size shouldBe Files.size(written)
      artifact.sha256 shouldBe sha256(Files.readAllBytes(written))
      artifact.sha256 shouldBe "aa08cd36bb2cf3c9d029f034acc488af7f04807863fb0bc9e6d32b1be5dc4ee9"
      Files
        .list(root.resolve("screens"))
        .toArray
        .map(_.toString)
        .count(_.contains(".tmp")) shouldBe 0

      val snapshot = screen(
        1,
        Vector(ScreenCell('A', 0x112233, 0x445566, 0x7788.toShort))
      ).copy(text = "A", palette = Vector(0xabcdef))
      val text = ScreenArtifactWriter
        .write(
          snapshot,
          ScreenArtifactRequest("screens/main.txt", ScreenArtifactFormat.Text),
          store
        )
        .value
      text.mediaType shouldBe "text/plain; charset=utf-8"
      new String(
        Files.readAllBytes(root.resolve(text.relativePath)),
        StandardCharsets.UTF_8
      ) shouldBe "A"
      val cells = ScreenArtifactWriter
        .write(
          snapshot,
          ScreenArtifactRequest("screens/main.cells.json", ScreenArtifactFormat.CellsJson),
          store
        )
        .value
      cells.mediaType shouldBe "application/json"
      new String(
        Files.readAllBytes(root.resolve(cells.relativePath)),
        StandardCharsets.UTF_8
      ) should include("\"foreground\":1122867")

      store
        .writeBytes("../escape.txt", "bad".getBytes(StandardCharsets.UTF_8), "text/plain")
        .left
        .value
        .code shouldBe
        "invalid_artifact_path"
      new ArtifactStore(
        root.resolve("small"),
        maxArtifactBytes = 2L,
        allowedRoot = root
      )
        .writeBytes("too-big.txt", Array[Byte](1, 2, 3), "application/octet-stream")
        .left
        .value
        .code shouldBe "artifact_limit_exceeded"

      val outside = Files.createTempDirectory("ocelot-artifact-outside-")
      try {
        try {
          Files.createSymbolicLink(root.resolve("linked"), outside)
          store
            .writeBytes("linked/escape.txt", Array[Byte](1), "application/octet-stream")
            .left
            .value
            .code shouldBe "invalid_artifact_path"
          Files.exists(outside.resolve("escape.txt")) shouldBe false
          Files.createSymbolicLink(root.resolve("escaped-root"), outside)
          new ArtifactStore(
            root.resolve("escaped-root"),
            maxArtifactBytes = 1024L,
            allowedRoot = root
          ).writeBytes("escape.txt", Array[Byte](1), "application/octet-stream")
            .left
            .value
            .code shouldBe "invalid_artifact_path"
          Files.exists(outside.resolve("escape.txt")) shouldBe false
        } catch {
          case _: UnsupportedOperationException =>
          case _: IOException                   =>
          case _: SecurityException             =>
        }
      } finally deleteTree(outside)
    }
  }

  private def screen(
      width: Int,
      cells: Vector[ScreenCell],
      powered: Boolean = true
  ): ScreenSnapshot =
    ScreenSnapshot(
      screenId,
      revision = 1L,
      width = width,
      height = 1,
      text = "",
      cells = cells,
      palette = Vector.empty,
      colorDepth = 8,
      powered = powered,
      captureTick = 1L
    )

  private def rgb(image: BufferedImage, x: Int, y: Int): Int = image.getRGB(x, y) & 0xffffff

  private def allPixels(image: BufferedImage): Vector[Int] =
    Vector.tabulate(image.getWidth * image.getHeight) { index =>
      rgb(image, index % image.getWidth, index / image.getWidth)
    }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString

  private def withTempDirectory(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("ocelot-artifact-test-")
    try test(root)
    finally deleteTree(root)
  }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => Files.deleteIfExists(path))
      finally stream.close()
    }
}
