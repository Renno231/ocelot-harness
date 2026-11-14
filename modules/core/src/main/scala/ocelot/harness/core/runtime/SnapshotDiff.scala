package ocelot.harness.core.runtime

import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import totoro.ocelot.brain.nbt._

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.SnapshotReadFailed
import ocelot.harness.core.workspace._

/** What changed between two save states, named the way the project names things: devices by their
  * manifest IDs, cards by slot and kind, screen text by line, and binary data (a computer's Lua
  * state, EEPROM contents) by size and checksum.
  */
private[runtime] object SnapshotDiff {
  val MaxChanges = 2000
  private val MaxValueChars = 240
  private val MaxDiskFiles = 100000

  def compare(
      fromName: SnapshotName,
      from: LoadedSnapshot,
      fromDirectory: Path,
      toName: SnapshotName,
      to: LoadedSnapshot,
      toDirectory: Path
  ): Either[HarnessError, SnapshotComparison] =
    try {
      val before = flatten(from)
      val after = flatten(to)
      val changes = (before.keySet ++ after.keySet).toVector.sorted.flatMap { path =>
        (before.get(path), after.get(path)) match {
          case (Some(a), Some(b)) if a == b => None
          case (Some(a), Some(b)) => Some(SnapshotChange(path, "changed", Some(a), Some(b)))
          case (Some(a), None)    => Some(SnapshotChange(path, "removed", Some(a), None))
          case (None, Some(b))    => Some(SnapshotChange(path, "added", None, Some(b)))
          case (None, None)       => None
        }
      }
      val (diskChanges, notes) =
        compareDisks(fromName, from, fromDirectory, toName, to, toDirectory)
      val all = changes ++ diskChanges
      Right(
        SnapshotComparison(
          fromName.value,
          toName.value,
          from.description.captureTick,
          to.description.captureTick,
          all.take(MaxChanges),
          all.size,
          notes
        )
      )
    } catch {
      case NonFatal(error) =>
        Left(SnapshotReadFailed(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    }

  private def flatten(snapshot: LoadedSnapshot): Map[String, String] = {
    val names = labels(snapshot.identity)
    val out = mutable.LinkedHashMap.empty[String, String]
    val root = snapshot.nbt
    root.getKeySet.asScala.toVector.sorted.foreach {
      case "entities" =>
        elements(root.getTag("entities")).foreach {
          case entity: NBTTagCompound =>
            val data = entity.getCompoundTag("data")
            val address = data.getCompoundTag("node").getString("address")
            val label = names.getOrElse(
              address,
              s"${simpleType(entity.getString("type"))} ${address.take(8)}"
            )
            walkEntity(
              label,
              data,
              screen = entity.getString("type").endsWith(".Screen"),
              names,
              out
            )
          case _ =>
        }
      case "edges" =>
        // Connections compare as a set; their order in the save is not meaningful.
        elements(root.getTag("edges")).foreach {
          case edge: NBTTagCompound =>
            val ends =
              Vector(edge.getString("left"), edge.getString("right")).map(name(_, names)).sorted
            out.update(s"connections/${ends.mkString(" <-> ")}", "connected")
          case _ =>
        }
      case "time" => out.update("world time (ticks)", render(root.getTag("time"), names))
      case "time_paused" =>
        out.update("world time paused", render(root.getTag("time_paused"), names))
      case key => walk(key, root.getTag(key), names, out)
    }
    out.toMap
  }

  private def walkEntity(
      label: String,
      data: NBTTagCompound,
      screen: Boolean,
      names: Map[String, String],
      out: mutable.Map[String, String]
  ): Unit =
    data.getKeySet.asScala.toVector.sorted.foreach {
      case "inventory" =>
        elements(data.getTag("inventory")).foreach {
          case slot: NBTTagCompound =>
            val contents = slot.getCompoundTag("contents")
            val path =
              s"$label/slot ${slot.getInteger("slot")} ${simpleType(contents.getString("type"))}"
            walk(path, contents.getCompoundTag("data"), names, out)
          case _ =>
        }
      case "data" if screen =>
        val buffer = data.getCompoundTag("data")
        buffer.getKeySet.asScala.toVector.sorted.foreach {
          case "buffer" =>
            elements(buffer.getTag("buffer")).zipWithIndex.foreach { case (line, index) =>
              out.update(
                s"$label/text line ${index + 1}",
                render(line, names).replaceAll("\\s+$", "")
              )
            }
          case key => walk(s"$label/$key", buffer.getTag(key), names, out)
        }
      case key => walk(s"$label/$key", data.getTag(key), names, out)
    }

  private def walk(
      path: String,
      value: NBTBase,
      names: Map[String, String],
      out: mutable.Map[String, String]
  ): Unit =
    value match {
      case compound: NBTTagCompound =>
        compound.getKeySet.asScala.toVector.sorted.foreach(key =>
          walk(s"$path/${segment(key, names)}", compound.getTag(key), names, out)
        )
      case list: NBTTagList =>
        elements(list).zipWithIndex.foreach { case (item, index) =>
          walk(s"$path/$index", item, names, out)
        }
      case other => out.update(path, render(other, names))
    }

  private def segment(key: String, names: Map[String, String]): String =
    if (key.endsWith("_kernel")) "Lua state"
    else names.foldLeft(key) { case (text, (address, label)) => text.replace(address, label) }

  private def render(value: NBTBase, names: Map[String, String]): String = value match {
    case bytes: NBTTagByteArray => blob(bytes.getByteArray)
    case ints: NBTTagIntArray   => s"${ints.toString.count(_ == ',') + 1} numbers"
    case text: NBTTagString =>
      val raw = text.toString.drop(1).dropRight(1)
      val named = name(raw, names)
      if (named.length > MaxValueChars) named.take(MaxValueChars) + "..." else named
    // Numbers print without NBT's type suffixes (12L, 3b, 1.5f).
    case number: NBTNumber => number.toString.replaceAll("[bslfdBSLFD]$", "")
    case other             => other.toString
  }

  private def blob(bytes: Array[Byte]): String =
    s"${bytes.length} bytes sha256:${sha256(bytes).take(12)}"

  private def name(text: String, names: Map[String, String]): String =
    names.get(text).getOrElse(text)

  private def labels(identity: SnapshotIdentity): Map[String, String] =
    identity.computers.map { case (id, address) => address -> s"computer $id" } ++
      identity.screens.map { case (id, address) => address -> s"screen $id" } ++
      identity.keyboards.map { case (id, address) => address -> s"keyboard $id" } ++
      identity.devices.map { case (id, address) => address -> s"device $id" }

  private def simpleType(className: String): String =
    className.split('.').lastOption.getOrElse(className)

  private def elements(value: NBTBase): Vector[NBTBase] = value match {
    case list: NBTTagList =>
      val copy = list.copy()
      Vector.fill(copy.tagCount())(copy.removeTag(0))
    case _ => Vector.empty
  }

  /** Files on host-folder disks, when both save states copied them. */
  private def compareDisks(
      fromName: SnapshotName,
      from: LoadedSnapshot,
      fromDirectory: Path,
      toName: SnapshotName,
      to: LoadedSnapshot,
      toDirectory: Path
  ): (Vector[SnapshotChange], Vector[String]) = {
    val uncopied = Vector(fromName -> from, toName -> to).collect {
      case (name, snapshot)
          if snapshot.description.hostDisks == HostDiskSnapshotPolicy.ReferenceOnly =>
        s"${name.value} did not copy host folder disks, so their files are not compared"
    }
    if (uncopied.nonEmpty) Vector.empty -> uncopied
    else {
      val before = diskFiles(fromDirectory.resolve("disks"))
      val after = diskFiles(toDirectory.resolve("disks"))
      val changes = (before.keySet ++ after.keySet).toVector.sorted.flatMap { path =>
        val label = s"host disk files/$path"
        (before.get(path), after.get(path)) match {
          case (Some(a), Some(b)) if a == b => None
          case (Some(a), Some(b)) => Some(SnapshotChange(label, "changed", Some(a), Some(b)))
          case (Some(a), None)    => Some(SnapshotChange(label, "removed", Some(a), None))
          case (None, Some(b))    => Some(SnapshotChange(label, "added", None, Some(b)))
          case (None, None)       => None
        }
      }
      changes -> Vector.empty
    }
  }

  private def diskFiles(root: Path): Map[String, String] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Map.empty
    else {
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .take(MaxDiskFiles)
          .map(path =>
            root.relativize(path).toString.replace('\\', '/') -> blob(Files.readAllBytes(path))
          )
          .toMap
      finally stream.close()
    }

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(value => f"${value & 0xff}%02x").mkString
}
