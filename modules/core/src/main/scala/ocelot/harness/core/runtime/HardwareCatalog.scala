package ocelot.harness.core.runtime

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import totoro.ocelot.brain.entity.{
  CPU,
  Case => BrainCase,
  EEPROM,
  GraphicsCard,
  HDDManaged,
  Keyboard,
  Memory,
  NetworkCard,
  Screen,
  WirelessNetworkCard
}
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.loot.Loot
import totoro.ocelot.brain.util.{ExtendedTier, Tier}
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.project._
import ocelot.harness.core.workspace._

private[runtime] final case class ConstructedWorkspace(
    description: WorkspaceDescription,
    computers: Map[ComputerId, BrainCase],
    screens: Map[ScreenId, Screen],
    keyboards: Map[ScreenId, Keyboard],
    screenTiers: Map[ScreenId, Int],
    managedDisks: Vector[(String, java.nio.file.Path)]
)

private[runtime] object HardwareCatalog {
  private object CaseSlots {
    val Gpu = 0
    val FirstCard = 1
    val FirstMemory = 3
    val FirstDisk = 5
    val Cpu = 8
    val Eeprom = 9
  }

  def construct(project: ValidatedProject, workspace: Workspace): ConstructedWorkspace = {
    val added = ArrayBuffer.empty[Entity]

    def add[T <: Entity](entity: T): T = {
      added += entity
      workspace.add(entity)
    }

    try {
      val constructedComputers = project.computers.map { definition =>
        val computer = add(new BrainCase(brainTier(definition.caseTier)))
        val hardware = definition.hardware

        val cpu = new CPU(brainTier(hardware.cpuTier))
        val gpu = new GraphicsCard(brainTier(hardware.gpuTier))
        val memories = hardware.memory.map(tier => new Memory(memoryTier(tier)))
        val eeprom = Loot.LuaBiosEEPROM.create()

        computer.inventory(CaseSlots.Cpu) = cpu
        computer.inventory(CaseSlots.Gpu) = gpu
        memories.zipWithIndex.foreach { case (memory, index) =>
          computer.inventory(CaseSlots.FirstMemory + index) = memory
        }
        computer.inventory(CaseSlots.Eeprom) = eeprom

        val disks = hardware.disks.zipWithIndex.map { case (diskDefinition, index) =>
          val disk = new HDDManaged(brainTier(diskDefinition.tier))
          disk.workspace = workspace
          disk.customRealPath = Some(diskDefinition.source)
          disk.fileSystem.label.setLabel(diskDefinition.label)
          if (diskDefinition.access == DiskAccess.ReadOnly) {
            disk.setLocked("ocelot-harness-read-only")
          }
          computer.inventory(CaseSlots.FirstDisk + index) = disk
          diskDefinition -> disk
        }

        val cards = hardware.cards.zipWithIndex.map { case (cardDefinition, index) =>
          val card = networkCard(cardDefinition.tier)
          computer.inventory(CaseSlots.FirstCard + index) = card
          cardDefinition -> card
        }

        val componentDescriptions =
          Vector(
            ComponentDescription(ComponentRole.Cpu, hardware.cpuTier.toString, address(cpu))
          ) ++
            memories.zip(hardware.memory).map { case (memory, tier) =>
              ComponentDescription(ComponentRole.Memory, tier.value.toString, address(memory))
            } ++
            Vector(
              ComponentDescription(ComponentRole.Gpu, hardware.gpuTier.toString, address(gpu)),
              ComponentDescription(ComponentRole.Eeprom, "builtin", address(eeprom))
            )

        (
          definition.id,
          computer,
          ComputerDescription(
            definition.id,
            definition.caseTier,
            requiredAddress(computer),
            componentDescriptions,
            disks.map { case (diskDefinition, disk) =>
              DiskDescription(
                diskDefinition.id,
                diskDefinition.tier,
                diskDefinition.label,
                diskDefinition.source,
                diskDefinition.access,
                requiredAddress(disk)
              )
            },
            cards.map { case (cardDefinition, card) =>
              CardDescription(cardDefinition.kind, cardDefinition.tier, requiredAddress(card))
            }
          )
        )
      }

      val screensById = project.screens.map { definition =>
        val screen = add(new Screen(brainTier(definition.tier)))
        val keyboard = if (definition.keyboard) Some(add(new Keyboard())) else None
        keyboard.foreach(screen.connect)
        definition.id -> (screen, keyboard)
      }.toMap

      val computersById = constructedComputers.map { case (id, computer, _) =>
        id -> computer
      }.toMap
      project.connections.foreach {
        case ConnectionDefinition(
              ConnectionEndpoint.Computer(computerId),
              ConnectionEndpoint.Screen(screenId)
            ) =>
          computersById(computerId).connect(screensById(screenId)._1)
        case _ =>
          throw new IllegalArgumentException("validated project contains an invalid connection")
      }

      val screens = project.screens.map { definition =>
        val (screen, keyboard) = screensById(definition.id)
        ScreenDescription(
          definition.id,
          definition.tier,
          definition.aspectRatio,
          requiredAddress(screen),
          keyboard.map(requiredAddress)
        )
      }

      val computers = constructedComputers.map { case (_, _, description) => description }
      ConstructedWorkspace(
        WorkspaceDescription(project.id, computers, screens, project.connections),
        computersById,
        screensById.view.mapValues(_._1).toMap,
        screensById.collect { case (id, (_, Some(keyboard))) => id -> keyboard },
        project.screens.map(value => value.id -> value.tier).toMap,
        project.computers.flatMap(computer =>
          computer.hardware.disks.map(disk =>
            s"${computer.id.value}/${disk.id.value}" -> disk.source
          )
        )
      )
    } catch {
      case NonFatal(error) =>
        added.reverseIterator.foreach { entity =>
          if (workspace.getEntitiesIter.exists(_ eq entity)) {
            try workspace.remove(entity)
            catch {
              case NonFatal(_) =>
            }
          }
        }
        throw error
    }
  }

  def restore(
      project: ValidatedProject,
      workspace: Workspace,
      identity: SnapshotIdentity
  ): ConstructedWorkspace = {
    def requiredIdentity(values: Map[String, String], kind: String, id: String): String =
      values.getOrElse(
        id,
        throw new IllegalArgumentException(s"snapshot is missing $kind identity: $id")
      )

    def requiredEntity[T](address: String, kind: String)(select: PartialFunction[Entity, T]): T =
      workspace
        .entityByAddress(address)
        .collect(select)
        .getOrElse(
          throw new IllegalArgumentException(s"snapshot $kind entity is missing: $address")
        )

    def inventoryEntity[T <: Environment](
        computer: BrainCase,
        slot: Int,
        role: String
    )(select: PartialFunction[Entity, T]): T =
      computer.inventory(slot).get.collect(select).getOrElse {
        val available = computer.inventory.iterator.map(_.index).toVector.sorted.mkString(",")
        throw new IllegalArgumentException(
          s"snapshot computer has invalid or missing $role in slot $slot; available slots: $available"
        )
      }

    val expectedComputerIds = project.computers.map(_.id.value).toSet
    val expectedScreenIds = project.screens.map(_.id.value).toSet
    val expectedKeyboardIds = project.screens.filter(_.keyboard).map(_.id.value).toSet
    if (
      identity.computers.keySet != expectedComputerIds ||
      identity.screens.keySet != expectedScreenIds ||
      identity.keyboards.keySet != expectedKeyboardIds
    ) {
      throw new IllegalArgumentException("snapshot logical identity set does not match the project")
    }

    val computers = project.computers.map { definition =>
      val computerAddress = requiredIdentity(identity.computers, "computer", definition.id.value)
      val computer = requiredEntity(computerAddress, "computer") { case value: BrainCase => value }
      val hardware = definition.hardware
      if (computer.tier != brainTier(definition.caseTier)) {
        throw new IllegalArgumentException(
          s"snapshot computer tier mismatch: ${definition.id.value}"
        )
      }
      val expectedSlots =
        Set(CaseSlots.Cpu, CaseSlots.Gpu, CaseSlots.Eeprom) ++
          hardware.memory.indices.map(CaseSlots.FirstMemory + _) ++
          hardware.disks.indices.map(CaseSlots.FirstDisk + _) ++
          hardware.cards.indices.map(CaseSlots.FirstCard + _)
      val actualSlots = computer.inventory.iterator.map(_.index).toSet
      if (actualSlots != expectedSlots) {
        throw new IllegalArgumentException(s"snapshot inventory mismatch: ${definition.id.value}")
      }
      val cpu = inventoryEntity(computer, CaseSlots.Cpu, "CPU") {
        case value: CPU if value.tier == brainTier(hardware.cpuTier) => value
      }
      val gpu = inventoryEntity(computer, CaseSlots.Gpu, "GPU") {
        case value: GraphicsCard if value.tier == brainTier(hardware.gpuTier) => value
      }
      val eeprom = inventoryEntity(computer, CaseSlots.Eeprom, "EEPROM") { case value: EEPROM =>
        value
      }
      val memories = hardware.memory.zipWithIndex.map { case (tier, index) =>
        inventoryEntity(computer, CaseSlots.FirstMemory + index, s"memory ${index + 1}") {
          case value: Memory if value.memoryTier == memoryTier(tier) => value
        }
      }
      val disks = hardware.disks.zipWithIndex.map { case (disk, index) =>
        val value =
          inventoryEntity(computer, CaseSlots.FirstDisk + index, s"disk ${disk.id.value}") {
            case restored: HDDManaged if restored.tier == brainTier(disk.tier) => restored
          }
        value.workspace = workspace
        value.customRealPath = Some(disk.source)
        value.fileSystem.label.setLabel(disk.label)
        if (disk.access == DiskAccess.ReadOnly && !value.isLocked) {
          value.setLocked("ocelot-harness-read-only")
        }
        DiskDescription(
          disk.id,
          disk.tier,
          disk.label,
          disk.source,
          disk.access,
          requiredAddress(value)
        )
      }
      val cards = hardware.cards.zipWithIndex.map { case (card, index) =>
        val value =
          inventoryEntity(computer, CaseSlots.FirstCard + index, s"card ${card.kind}") {
            case restored: NetworkCard
                if card.tier == 1 && restored.getClass == classOf[NetworkCard] =>
              restored
            case restored: WirelessNetworkCard.Tier2 if card.tier == 2 => restored
          }
        CardDescription(card.kind, card.tier, requiredAddress(value))
      }
      val components =
        Vector(ComponentDescription(ComponentRole.Cpu, hardware.cpuTier.toString, address(cpu))) ++
          memories.zip(hardware.memory).map { case (memory, tier) =>
            ComponentDescription(ComponentRole.Memory, tier.value.toString, address(memory))
          } ++
          Vector(
            ComponentDescription(ComponentRole.Gpu, hardware.gpuTier.toString, address(gpu)),
            ComponentDescription(ComponentRole.Eeprom, "builtin", address(eeprom))
          )
      definition.id -> (
        computer,
        ComputerDescription(
          definition.id,
          definition.caseTier,
          computerAddress,
          components,
          disks,
          cards
        )
      )
    }

    val screens = project.screens.map { definition =>
      val screenAddress = requiredIdentity(identity.screens, "screen", definition.id.value)
      val screen = requiredEntity(screenAddress, "screen") { case value: Screen => value }
      if (screen.tier != brainTier(definition.tier)) {
        throw new IllegalArgumentException(s"snapshot screen tier mismatch: ${definition.id.value}")
      }
      val keyboard = identity.keyboards.get(definition.id.value).map { address =>
        requiredEntity(address, "keyboard") { case value: Keyboard => value }
      }
      if (definition.keyboard != keyboard.nonEmpty) {
        throw new IllegalArgumentException(
          s"snapshot keyboard identity mismatch: ${definition.id.value}"
        )
      }
      keyboard.foreach { value =>
        if (!screen.node.isNeighborOf(value.node)) {
          throw new IllegalArgumentException(
            s"snapshot keyboard connection mismatch: ${definition.id.value}"
          )
        }
      }
      definition.id -> (
        screen,
        keyboard,
        ScreenDescription(
          definition.id,
          definition.tier,
          definition.aspectRatio,
          screenAddress,
          keyboard.map(requiredAddress)
        )
      )
    }

    val computersById = computers.map { case (id, (computer, _)) => id -> computer }.toMap
    val screensById = screens.map { case (id, (screen, _, _)) => id -> screen }.toMap
    project.connections.foreach {
      case ConnectionDefinition(
            ConnectionEndpoint.Computer(computerId),
            ConnectionEndpoint.Screen(screenId)
          ) if computersById(computerId).node.isNeighborOf(screensById(screenId).node) =>
      case connection =>
        throw new IllegalArgumentException(s"snapshot connection mismatch: $connection")
    }
    val expectedEntityCount = computers.size + screens.size + identity.keyboards.size
    if (workspace.getEntitiesIter.size != expectedEntityCount) {
      throw new IllegalArgumentException("snapshot contains unexpected workspace entities")
    }

    ConstructedWorkspace(
      WorkspaceDescription(
        project.id,
        computers.map(_._2._2),
        screens.map(_._2._3),
        project.connections
      ),
      computersById,
      screensById,
      screens.collect { case (id, (_, Some(keyboard), _)) => id -> keyboard }.toMap,
      project.screens.map(value => value.id -> value.tier).toMap,
      project.computers.flatMap(computer =>
        computer.hardware.disks.map(disk => s"${computer.id.value}/${disk.id.value}" -> disk.source)
      )
    )
  }

  private def brainTier(tier: Int): Tier.Tier = tier match {
    case 1     => Tier.One
    case 2     => Tier.Two
    case 3     => Tier.Three
    case other => throw new IllegalArgumentException(s"unsupported hardware tier: $other")
  }

  private def memoryTier(tier: MemoryTier): ExtendedTier.ExtendedTier = tier match {
    case MemoryTier.One          => ExtendedTier.One
    case MemoryTier.OneAndHalf   => ExtendedTier.OneHalf
    case MemoryTier.Two          => ExtendedTier.Two
    case MemoryTier.TwoAndHalf   => ExtendedTier.TwoHalf
    case MemoryTier.Three        => ExtendedTier.Three
    case MemoryTier.ThreeAndHalf => ExtendedTier.ThreeHalf
  }

  private def networkCard(tier: Int): NetworkCard = tier match {
    case 1     => new NetworkCard()
    case 2     => new WirelessNetworkCard.Tier2()
    case other => throw new IllegalArgumentException(s"unsupported network-card tier: $other")
  }

  private def address(environment: Environment): Option[String] =
    Option(environment.node).flatMap(node => Option(node.address))

  private def requiredAddress(environment: Environment): String =
    address(environment).getOrElse(
      throw new IllegalStateException("constructed brain component has no runtime address")
    )
}
