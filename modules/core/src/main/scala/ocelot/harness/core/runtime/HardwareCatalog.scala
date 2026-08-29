package ocelot.harness.core.runtime

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import totoro.ocelot.brain.entity.{
  CPU,
  Case => BrainCase,
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
    keyboards: Map[ScreenId, Keyboard]
)

private[runtime] object HardwareCatalog {
  private object TierThreeSlots {
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
        val computer = add(new BrainCase(Tier.Three))
        val hardware = definition.hardware

        val cpu = new CPU(Tier.Three)
        val gpu = new GraphicsCard(Tier.Three)
        val memories = hardware.memory.map(tier => new Memory(memoryTier(tier)))
        val eeprom = Loot.LuaBiosEEPROM.create()

        computer.inventory(TierThreeSlots.Cpu) = cpu
        computer.inventory(TierThreeSlots.Gpu) = gpu
        memories.zipWithIndex.foreach { case (memory, index) =>
          computer.inventory(TierThreeSlots.FirstMemory + index) = memory
        }
        computer.inventory(TierThreeSlots.Eeprom) = eeprom

        val disks = hardware.disks.zipWithIndex.map { case (diskDefinition, index) =>
          val disk = new HDDManaged(brainTier(diskDefinition.tier))
          disk.workspace = workspace
          disk.customRealPath = Some(diskDefinition.source)
          disk.fileSystem.label.setLabel(diskDefinition.label)
          if (diskDefinition.access == DiskAccess.ReadOnly) {
            disk.setLocked("ocelot-harness-read-only")
          }
          computer.inventory(TierThreeSlots.FirstDisk + index) = disk
          diskDefinition -> disk
        }

        val cards = hardware.cards.zipWithIndex.map { case (cardDefinition, index) =>
          val card = networkCard(cardDefinition.tier)
          computer.inventory(TierThreeSlots.FirstCard + index) = card
          cardDefinition -> card
        }

        val componentDescriptions =
          Vector(
            ComponentDescription(ComponentRole.Cpu, "3", address(cpu))
          ) ++
            memories.zip(hardware.memory).map { case (memory, tier) =>
              ComponentDescription(ComponentRole.Memory, tier.value.toString, address(memory))
            } ++
            Vector(
              ComponentDescription(ComponentRole.Gpu, "3", address(gpu)),
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
        val screen = add(new Screen(Tier.Three))
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
        screensById.collect { case (id, (_, Some(keyboard))) => id -> keyboard }
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

  private def brainTier(tier: Int): Tier.Tier = tier match {
    case 2     => Tier.Two
    case 3     => Tier.Three
    case other => throw new IllegalArgumentException(s"unsupported hardware tier: $other")
  }

  private def memoryTier(tier: MemoryTier): ExtendedTier.ExtendedTier = tier match {
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
