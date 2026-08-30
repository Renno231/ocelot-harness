package ocelot.harness.core.runtime

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import totoro.ocelot.brain.entity.{
  APU,
  CPU,
  Cable,
  Case => BrainCase,
  ComponentBus,
  DataCard,
  FloppyDiskDrive,
  FloppyManaged,
  FloppyUnmanaged,
  GraphicsCard,
  HDDManaged,
  HDDUnmanaged,
  HologramProjector,
  InternetCard,
  IronNoteBlock,
  Keyboard,
  LinkedCard,
  Memory,
  Microcontroller,
  NetworkCard,
  NoteBlock,
  Rack,
  Raid,
  Redstone,
  Relay,
  Screen,
  Server,
  WirelessNetworkCard
}
import totoro.ocelot.brain.entity.traits.{
  Computer,
  Entity,
  Environment,
  Inventory,
  SidedEnvironment
}
import totoro.ocelot.brain.loot.Loot
import totoro.ocelot.brain.network.{Network, Node}
import totoro.ocelot.brain.util.{Direction, ExtendedTier, Tier}
import totoro.ocelot.brain.workspace.Workspace

import ocelot.harness.core.project._
import ocelot.harness.core.workspace._

private[runtime] object ManifestHardwareCatalog {
  def construct(
      project: ValidatedProject,
      topology: ManifestTopologyDefinition,
      workspace: Workspace
  ): ConstructedWorkspace = {
    val added = ArrayBuffer.empty[Entity]
    val entities = scala.collection.mutable.Map.empty[DeviceId, Entity]
    val keyboards = scala.collection.mutable.Map.empty[ScreenId, Keyboard]

    def add[T <: Entity](entity: T): T = {
      added += entity
      workspace.add(entity)
    }

    try {
      topology.devices.foreach { definition =>
        val entity = createDevice(definition)
        entities += definition.id -> entity
        if (definition.kind != ManifestDeviceKind.Server) add(entity)
        else
          entity match {
            case aware: totoro.ocelot.brain.entity.traits.WorkspaceAware =>
              aware.workspace = workspace
            case _ =>
          }
        fillInventory(definition, entity, workspace)
      }

      topology.devices.foreach { definition =>
        if (definition.kind == ManifestDeviceKind.Screen && definition.keyboard) {
          val id = ScreenId
            .parse(definition.id.value)
            .fold(message => throw new IllegalArgumentException(message), identity)
          val keyboard = add(new Keyboard())
          entities(definition.id).asInstanceOf[Screen].connect(keyboard)
          keyboards += id -> keyboard
        }
      }

      topology.connections.foreach { connection =>
        if (isRackMount(connection, topology)) mountServer(connection, entities)
      }
      topology.connections.foreach { connection =>
        if (!isRackMount(connection, topology)) connect(connection, entities)
      }
      entities.values.foreach {
        case computer: Computer =>
          val node = computer.machine.node
          if (node.network == null) Network.joinNewNetwork(node)
        case _ =>
      }

      describe(project, topology, entities.toMap, keyboards.toMap)
    } catch {
      case error if NonFatal(error) =>
        entities.values.collect { case computer: Computer => computer }.foreach { computer =>
          try computer.machine.stop()
          catch { case NonFatal(_) => }
        }
        added.reverseIterator.foreach { entity =>
          if (workspace.getEntitiesIter.exists(_ eq entity)) {
            try workspace.remove(entity)
            catch { case NonFatal(_) => }
          }
        }
        entities.values
          .filterNot(entity => added.exists(_ eq entity))
          .foreach(entity =>
            try entity.dispose()
            catch { case NonFatal(_) => }
          )
        throw error
    }
  }

  def restore(
      project: ValidatedProject,
      topology: ManifestTopologyDefinition,
      workspace: Workspace,
      identity: SnapshotIdentity
  ): ConstructedWorkspace = {
    val all = allEntities(workspace.getEntitiesIter.toVector)
    if (identity.devices.keySet != topology.devices.map(_.id.value).toSet) {
      throw new IllegalArgumentException(
        "snapshot manifest device identity set does not match the project"
      )
    }
    val expectedEntityCount = topology.devices.size +
      topology.devices.map(_.inventory.size).sum +
      topology.devices.count(device => device.kind == ManifestDeviceKind.Screen && device.keyboard)
    if (all.size != expectedEntityCount) {
      throw new IllegalArgumentException(
        s"snapshot entity count mismatch: expected $expectedEntityCount, found ${all.size}"
      )
    }
    val byUuid = all.map(entity => entity.entityId.toString -> entity).toMap
    if (byUuid.size != all.size)
      throw new IllegalArgumentException("snapshot contains duplicate entity identities")
    val expectedComputerIds = topology.devices.collect {
      case device
          if Set(
            ManifestDeviceKind.Computer,
            ManifestDeviceKind.Server,
            ManifestDeviceKind.Microcontroller
          ).contains(device.kind) =>
        device.id.value
    }.toSet
    val expectedScreenIds = topology.devices.collect {
      case device if device.kind == ManifestDeviceKind.Screen => device.id.value
    }.toSet
    val expectedKeyboardIds = topology.devices.collect {
      case device if device.kind == ManifestDeviceKind.Screen && device.keyboard => device.id.value
    }.toSet
    if (
      identity.computers.keySet != expectedComputerIds ||
      identity.screens.keySet != expectedScreenIds ||
      identity.keyboards.keySet != expectedKeyboardIds
    ) throw new IllegalArgumentException("snapshot logical identity set does not match the project")
    val entities = topology.devices.map { definition =>
      val uuid = identity.devices.getOrElse(
        definition.id.value,
        throw new IllegalArgumentException(
          s"snapshot is missing device identity: ${definition.id.value}"
        )
      )
      val entity = byUuid.getOrElse(
        uuid,
        throw new IllegalArgumentException(
          s"snapshot device entity is missing: ${definition.id.value}"
        )
      )
      if (!matchesKind(entity, definition.kind)) {
        throw new IllegalArgumentException(s"snapshot device kind mismatch: ${definition.id.value}")
      }
      validateAndRebindInventory(definition, entity, workspace)
      definition.id -> entity
    }.toMap
    topology.connections.foreach { connection =>
      if (!isRackMount(connection, topology)) connect(connection, entities)
    }
    validateRestoredConnections(topology, entities)
    val keyboards = topology.devices.collect {
      case definition if definition.kind == ManifestDeviceKind.Screen && definition.keyboard =>
        val id = ScreenId.parse(definition.id.value).toOption.get
        val address = identity.keyboards.getOrElse(
          definition.id.value,
          throw new IllegalArgumentException(
            s"snapshot is missing keyboard identity: ${definition.id.value}"
          )
        )
        val keyboard = workspace
          .entityByAddress(address)
          .collect { case value: Keyboard => value }
          .getOrElse(
            throw new IllegalArgumentException(
              s"snapshot keyboard is missing: ${definition.id.value}"
            )
          )
        val screen = entities(definition.id).asInstanceOf[Screen]
        if (!screen.node.isNeighborOf(keyboard.node))
          throw new IllegalArgumentException(
            s"snapshot keyboard connection mismatch: ${definition.id.value}"
          )
        id -> keyboard
    }.toMap
    describe(project, topology, entities, keyboards)
  }

  private def allEntities(topLevel: Vector[Entity]): Vector[Entity] = {
    def descend(entity: Entity): Vector[Entity] = entity match {
      case inventory: Inventory => entity +: inventory.inventory.entities.toVector.flatMap(descend)
      case _                    => Vector(entity)
    }
    topLevel.flatMap(descend)
  }

  private def matchesKind(entity: Entity, kind: ManifestDeviceKind): Boolean = kind match {
    case ManifestDeviceKind.Computer        => entity.isInstanceOf[BrainCase]
    case ManifestDeviceKind.Screen          => entity.isInstanceOf[Screen]
    case ManifestDeviceKind.Rack            => entity.isInstanceOf[Rack]
    case ManifestDeviceKind.Server          => entity.isInstanceOf[Server]
    case ManifestDeviceKind.DiskDrive       => entity.isInstanceOf[FloppyDiskDrive]
    case ManifestDeviceKind.Raid            => entity.isInstanceOf[Raid]
    case ManifestDeviceKind.Hologram        => entity.isInstanceOf[HologramProjector]
    case ManifestDeviceKind.NoteBlock       => entity.isInstanceOf[NoteBlock]
    case ManifestDeviceKind.IronNoteBlock   => entity.isInstanceOf[IronNoteBlock]
    case ManifestDeviceKind.Microcontroller => entity.isInstanceOf[Microcontroller]
    case ManifestDeviceKind.Relay           => entity.isInstanceOf[Relay]
    case ManifestDeviceKind.Cable           => entity.isInstanceOf[Cable]
  }

  private def validateAndRebindInventory(
      definition: ManifestDeviceDefinition,
      entity: Entity,
      workspace: Workspace
  ): Unit = entity match {
    case inventory: Inventory =>
      val expectedSlots = definition.inventory.map(item => slotIndex(definition, item.slot)).toSet
      val actualSlots = inventory.inventory.iterator.map(_.index).toSet
      if (definition.kind != ManifestDeviceKind.Rack && actualSlots != expectedSlots)
        throw new IllegalArgumentException(s"snapshot inventory mismatch: ${definition.id.value}")
      definition.inventory.foreach { item =>
        val restored = inventory
          .inventory(slotIndex(definition, item.slot))
          .get
          .getOrElse(
            throw new IllegalArgumentException(
              s"snapshot inventory item is missing: ${definition.id.value}/${item.slot}"
            )
          )
        if (!matchesInventoryItem(definition.kind, restored, item))
          throw new IllegalArgumentException(
            s"snapshot inventory item kind or tier mismatch: ${definition.id.value}/${item.slot}"
          )
        item.kind match {
          case InventoryKind.ComponentBus =>
            restored.asInstanceOf[ComponentBus].tier = brainTier(item.tier.get.toInt)
          case InventoryKind.ManagedHdd | InventoryKind.ManagedFloppy =>
            val disk = restored.asInstanceOf[totoro.ocelot.brain.entity.traits.DiskManaged]
            disk.workspace = workspace
            disk.customRealPath = item.source
            disk.fileSystem.label.setLabel(item.label.get)
            if (item.access.contains(DiskAccess.ReadOnly))
              disk.setLocked("ocelot-harness-read-only")
            else if (disk.isLocked) disk.setLocked("")
          case _ =>
        }
      }
      entity match {
        case raid: Raid =>
          raid.customRealPath = definition.source
          raid.label.setLabel(definition.label.orNull)
        case _ =>
      }
    case _ if definition.inventory.nonEmpty =>
      throw new IllegalArgumentException(
        s"snapshot device inventory is missing: ${definition.id.value}"
      )
    case _ =>
  }

  private def matchesInventoryItem(
      owner: ManifestDeviceKind,
      entity: Entity,
      item: InventoryItemDefinition
  ): Boolean = item.kind match {
    case InventoryKind.Cpu =>
      entity match {
        case value: CPU => value.tier == brainTier(item.tier.get.toInt)
        case _          => false
      }
    case InventoryKind.Apu =>
      entity match {
        case value: APU => value.tier == brainTier(item.tier.get.toInt)
        case _          => false
      }
    case InventoryKind.Memory =>
      entity match {
        case value: Memory => value.memoryTier == memoryTier(item.tier.get)
        case _             => false
      }
    case InventoryKind.Gpu =>
      entity match {
        case value: GraphicsCard => value.tier == brainTier(item.tier.get.toInt)
        case _                   => false
      }
    case InventoryKind.Eeprom       => entity.isInstanceOf[totoro.ocelot.brain.entity.EEPROM]
    case InventoryKind.ComponentBus => entity.isInstanceOf[ComponentBus]
    case InventoryKind.ManagedHdd =>
      entity match {
        case value: HDDManaged => value.tier == brainTier(item.tier.get.toInt)
        case _                 => false
      }
    case InventoryKind.UnmanagedHdd =>
      entity match {
        case value: HDDUnmanaged => value.tier == brainTier(item.tier.get.toInt)
        case value: HDDManaged if owner == ManifestDeviceKind.Raid =>
          value.tier == brainTier(item.tier.get.toInt)
        case _ => false
      }
    case InventoryKind.ManagedFloppy   => entity.isInstanceOf[FloppyManaged]
    case InventoryKind.UnmanagedFloppy => entity.isInstanceOf[FloppyUnmanaged]
    case InventoryKind.Network         => entity.getClass == classOf[NetworkCard]
    case InventoryKind.Wireless =>
      item.tier.get.toInt match {
        case 1 => entity.isInstanceOf[WirelessNetworkCard.Tier1]
        case 2 => entity.isInstanceOf[WirelessNetworkCard.Tier2]
        case _ => false
      }
    case InventoryKind.Linked => entity.isInstanceOf[LinkedCard]
    case InventoryKind.Data =>
      item.tier.get.toInt match {
        case 1 => entity.isInstanceOf[DataCard.Tier1]
        case 2 => entity.isInstanceOf[DataCard.Tier2]
        case 3 => entity.isInstanceOf[DataCard.Tier3]
        case _ => false
      }
    case InventoryKind.Redstone =>
      item.tier.get.toInt match {
        case 1 => entity.getClass == classOf[Redstone.Tier1]
        case 2 => entity.isInstanceOf[Redstone.Tier2]
        case _ => false
      }
    case InventoryKind.Internet => entity.isInstanceOf[InternetCard]
  }

  private def validateRestoredConnections(
      topology: ManifestTopologyDefinition,
      entities: Map[DeviceId, Entity]
  ): Unit = topology.connections.foreach { connection =>
    if (isRackMount(connection, topology)) {
      val (rackRef, serverRef) =
        if (connection.from.port.startsWith("mount-"))
          connection.from -> connection.to
        else connection.to -> connection.from
      val slot = rackRef.port.stripPrefix("mount-").toInt - 1
      val mounted = entities(rackRef.device).asInstanceOf[Rack].inventory(slot).get
      if (!mounted.contains(entities(serverRef.device)))
        throw new IllegalArgumentException(
          s"snapshot rack mount mismatch: ${serverRef.device.value}"
        )
    } else {
      val left = endpointNode(connection.from, entities)
      val right = endpointNode(connection.to, entities)
      if (left.network == null || (left.network ne right.network))
        throw new IllegalArgumentException(
          s"snapshot connection mismatch: ${connection.from.value} -> ${connection.to.value}"
        )
    }
  }

  private def describe(
      project: ValidatedProject,
      topology: ManifestTopologyDefinition,
      entities: Map[DeviceId, Entity],
      keyboards: Map[ScreenId, Keyboard]
  ): ConstructedWorkspace = {
    val computers = topology.devices.flatMap { definition =>
      entities(definition.id) match {
        case computer: Computer =>
          val id = ComputerId.parse(definition.id.value).toOption.get
          Some(id -> computer)
        case _ => None
      }
    }.toMap
    val screens = topology.devices.collect {
      case definition if definition.kind == ManifestDeviceKind.Screen =>
        ScreenId.parse(definition.id.value).toOption.get -> entities(definition.id)
          .asInstanceOf[Screen]
    }.toMap
    val computerDescriptions = topology.devices.flatMap { definition =>
      entities(definition.id) match {
        case computer: Computer => Some(describeComputer(definition, computer))
        case _                  => None
      }
    }
    val screenDescriptions = topology.devices.collect {
      case definition if definition.kind == ManifestDeviceKind.Screen =>
        val id = ScreenId.parse(definition.id.value).toOption.get
        val screen = entities(definition.id).asInstanceOf[Screen]
        ScreenDescription(
          id,
          definition.tier.get,
          definition.aspectRatio,
          requiredAddress(screen),
          keyboards.get(id).map(requiredAddress)
        )
    }
    val deviceDescriptions = topology.devices.map { definition =>
      val entity = entities(definition.id)
      val runtimeAddress = entity match {
        case computer: Computer       => address(computer.machine)
        case environment: Environment => address(environment)
        case _                        => None
      }
      DeviceDescription(definition.id.value, definition.kind.name, definition.tier, runtimeAddress)
    }
    val managedDisks = topology.devices.flatMap { device =>
      device.inventory.collect {
        case item
            if Set(InventoryKind.ManagedHdd, InventoryKind.ManagedFloppy).contains(item.kind) =>
          s"${device.id.value}/${item.id.get.value}" -> item.source.get
      }
    } ++ topology.devices.collect {
      case device if device.kind == ManifestDeviceKind.Raid =>
        s"${device.id.value}/raid" -> device.source.get
    }
    ConstructedWorkspace(
      WorkspaceDescription(
        project.id,
        computerDescriptions,
        screenDescriptions,
        Vector.empty,
        deviceDescriptions,
        topology.connections.map(connection => connection.from.value -> connection.to.value)
      ),
      computers,
      screens,
      keyboards,
      topology.devices.collect {
        case definition if definition.kind == ManifestDeviceKind.Screen =>
          ScreenId.parse(definition.id.value).toOption.get -> definition.tier.get
      }.toMap,
      managedDisks,
      entities.map { case (id, entity) => id.value -> entity }
    )
  }

  private def createDevice(definition: ManifestDeviceDefinition): Entity = definition.kind match {
    case ManifestDeviceKind.Computer        => new BrainCase(brainTier(definition.tier.get))
    case ManifestDeviceKind.Screen          => new Screen(brainTier(definition.tier.get))
    case ManifestDeviceKind.Rack            => new Rack()
    case ManifestDeviceKind.Server          => new Server(brainTier(definition.tier.get))
    case ManifestDeviceKind.DiskDrive       => new FloppyDiskDrive()
    case ManifestDeviceKind.Raid            => new Raid()
    case ManifestDeviceKind.Hologram        => new HologramProjector(brainTier(definition.tier.get))
    case ManifestDeviceKind.NoteBlock       => new NoteBlock()
    case ManifestDeviceKind.IronNoteBlock   => new IronNoteBlock()
    case ManifestDeviceKind.Microcontroller => new Microcontroller(brainTier(definition.tier.get))
    case ManifestDeviceKind.Relay           => new Relay()
    case ManifestDeviceKind.Cable           => new Cable()
  }

  private def fillInventory(
      definition: ManifestDeviceDefinition,
      entity: Entity,
      workspace: Workspace
  ): Unit = entity match {
    case inventory: Inventory =>
      entity match {
        case raid: Raid =>
          raid.customRealPath = definition.source
          raid.label.setLabel(definition.label.orNull)
        case _ =>
      }
      definition.inventory.foreach { item =>
        inventory.inventory(slotIndex(definition, item.slot)) = createInventoryItem(item, workspace)
      }
    case _ if definition.inventory.nonEmpty =>
      throw new IllegalArgumentException(s"${definition.kind.name} cannot contain inventory")
    case _ =>
  }

  private def createInventoryItem(item: InventoryItemDefinition, workspace: Workspace): Entity =
    item.kind match {
      case InventoryKind.Cpu          => new CPU(brainTier(item.tier.get.toInt))
      case InventoryKind.Apu          => new APU(brainTier(item.tier.get.toInt))
      case InventoryKind.Memory       => new Memory(memoryTier(item.tier.get))
      case InventoryKind.Gpu          => new GraphicsCard(brainTier(item.tier.get.toInt))
      case InventoryKind.Eeprom       => Loot.LuaBiosEEPROM.create()
      case InventoryKind.ComponentBus => new ComponentBus(brainTier(item.tier.get.toInt))
      case InventoryKind.ManagedHdd =>
        configureManaged(new HDDManaged(brainTier(item.tier.get.toInt)), item, workspace)
      case InventoryKind.UnmanagedHdd    => new HDDUnmanaged(brainTier(item.tier.get.toInt))
      case InventoryKind.ManagedFloppy   => configureManaged(new FloppyManaged(), item, workspace)
      case InventoryKind.UnmanagedFloppy => new FloppyUnmanaged()
      case InventoryKind.Network         => new NetworkCard()
      case InventoryKind.Wireless =>
        if (item.tier.contains(BigDecimal(1))) new WirelessNetworkCard.Tier1()
        else new WirelessNetworkCard.Tier2()
      case InventoryKind.Linked =>
        val card = new LinkedCard()
        card.tunnel = item.tunnel.get
        card
      case InventoryKind.Data =>
        item.tier.get.toInt match {
          case 1 => new DataCard.Tier1()
          case 2 => new DataCard.Tier2()
          case 3 => new DataCard.Tier3()
        }
      case InventoryKind.Redstone =>
        if (item.tier.contains(BigDecimal(1))) new Redstone.Tier1() else new Redstone.Tier2()
      case InventoryKind.Internet => new InternetCard()
    }

  private def configureManaged[T <: Entity with totoro.ocelot.brain.entity.traits.DiskManaged](
      disk: T,
      item: InventoryItemDefinition,
      workspace: Workspace
  ): T = {
    disk.workspace = workspace
    disk.customRealPath = item.source
    disk.fileSystem.label.setLabel(item.label.get)
    if (item.access.contains(DiskAccess.ReadOnly)) disk.setLocked("ocelot-harness-read-only")
    disk
  }

  private def slotIndex(device: ManifestDeviceDefinition, slot: String): Int = device.kind match {
    case ManifestDeviceKind.Computer =>
      slot match {
        case "gpu"                                => 0
        case value if value.startsWith("card-")   => value.stripPrefix("card-").toInt
        case value if value.startsWith("memory-") => 3 + value.stripPrefix("memory-").toInt - 1
        case value if value.startsWith("disk-")   => 5 + value.stripPrefix("disk-").toInt - 1
        case "floppy"                             => 7
        case "cpu"                                => 8
        case "eeprom"                             => 9
        case value if value.startsWith("component-bus-") =>
          10 + value.stripPrefix("component-bus-").toInt - 1
        case other => throw new IllegalArgumentException(s"unsupported computer slot: $other")
      }
    case ManifestDeviceKind.Server =>
      slot match {
        case value if value.startsWith("card-") => value.stripPrefix("card-").toInt - 1
        case "cpu"                              => 4
        case value if value.startsWith("component-bus-") =>
          5 + value.stripPrefix("component-bus-").toInt - 1
        case value if value.startsWith("memory-") => 8 + value.stripPrefix("memory-").toInt - 1
        case value if value.startsWith("disk-")   => 12 + value.stripPrefix("disk-").toInt - 1
        case "eeprom"                             => 16
        case other => throw new IllegalArgumentException(s"unsupported server slot: $other")
      }
    case ManifestDeviceKind.Microcontroller =>
      slot match {
        case value if value.startsWith("card-")   => value.stripPrefix("card-").toInt - 1
        case "cpu"                                => 2
        case value if value.startsWith("memory-") => 3 + value.stripPrefix("memory-").toInt - 1
        case "eeprom"                             => 5
        case other =>
          throw new IllegalArgumentException(s"unsupported microcontroller slot: $other")
      }
    case ManifestDeviceKind.Relay =>
      slot match {
        case "cpu"    => 0
        case "memory" => 1
        case "disk"   => 2
        case "card"   => 3
      }
    case ManifestDeviceKind.Raid      => slot.stripPrefix("disk-").toInt - 1
    case ManifestDeviceKind.DiskDrive => 0
    case other => throw new IllegalArgumentException(s"${other.name} has no inventory slots")
  }

  private def describeComputer(
      definition: ManifestDeviceDefinition,
      computer: Computer
  ): ComputerDescription = {
    val id = ComputerId.parse(definition.id.value).toOption.get
    val components = definition.inventory.collect {
      case item
          if Set(
            InventoryKind.Cpu,
            InventoryKind.Apu,
            InventoryKind.Memory,
            InventoryKind.Gpu,
            InventoryKind.Eeprom,
            InventoryKind.ComponentBus
          ).contains(item.kind) =>
        val entity = computer.inventory(slotIndex(definition, item.slot)).get.get
        val role = item.kind match {
          case InventoryKind.Cpu | InventoryKind.Apu => ComponentRole.Cpu
          case InventoryKind.Memory                  => ComponentRole.Memory
          case InventoryKind.Gpu                     => ComponentRole.Gpu
          case InventoryKind.Eeprom                  => ComponentRole.Eeprom
          case other                                 => ComponentRole.Other(other.name)
        }
        ComponentDescription(
          role,
          item.tier.map(_.toString).getOrElse("builtin"),
          entity match {
            case environment: Environment => address(environment)
            case _                        => None
          }
        )
    }
    val disks = definition.inventory.collect {
      case item if item.kind == InventoryKind.ManagedHdd =>
        val disk =
          computer.inventory(slotIndex(definition, item.slot)).get.get.asInstanceOf[HDDManaged]
        DiskDescription(
          item.id.get,
          item.tier.get.toInt,
          item.label.get,
          item.source.get,
          item.access.get,
          requiredAddress(disk)
        )
    }
    val cards = definition.inventory.collect {
      case item if cardKind(item.kind).nonEmpty =>
        val entity =
          computer.inventory(slotIndex(definition, item.slot)).get.get.asInstanceOf[Environment]
        CardDescription(
          cardKind(item.kind).get,
          item.tier.map(_.toInt).getOrElse(3),
          requiredAddress(entity)
        )
    }
    ComputerDescription(
      id,
      definition.tier.get,
      requiredAddress(computer.machine),
      components,
      disks,
      cards,
      definition.kind.name
    )
  }

  private def cardKind(kind: InventoryKind): Option[CardKind] = kind match {
    case InventoryKind.Network  => Some(CardKind.Network)
    case InventoryKind.Wireless => Some(CardKind.Wireless)
    case InventoryKind.Linked   => Some(CardKind.Linked)
    case InventoryKind.Data     => Some(CardKind.Data)
    case InventoryKind.Redstone => Some(CardKind.Redstone)
    case InventoryKind.Internet => Some(CardKind.Internet)
    case _                      => None
  }

  private def isRackMount(
      connection: DeviceConnectionDefinition,
      topology: ManifestTopologyDefinition
  ): Boolean = {
    val kinds = topology.devices.map(value => value.id -> value.kind).toMap
    (kinds(connection.from.device) == ManifestDeviceKind.Rack && connection.from.port.startsWith(
      "mount-"
    )) ||
    (kinds(connection.to.device) == ManifestDeviceKind.Rack && connection.to.port.startsWith(
      "mount-"
    ))
  }

  private def mountServer(
      connection: DeviceConnectionDefinition,
      entities: scala.collection.Map[DeviceId, Entity]
  ): Unit = {
    val (rackRef, serverRef) =
      if (connection.from.port.startsWith("mount-"))
        connection.from -> connection.to
      else connection.to -> connection.from
    val slot = rackRef.port.stripPrefix("mount-").toInt - 1
    entities(rackRef.device).asInstanceOf[Rack].inventory(slot) =
      entities(serverRef.device).asInstanceOf[Server]
  }

  private def connect(
      connection: DeviceConnectionDefinition,
      entities: scala.collection.Map[DeviceId, Entity]
  ): Unit = {
    val left = endpointNode(connection.from, entities)
    val right = endpointNode(connection.to, entities)
    if (left.network == null) Network.joinNewNetwork(left)
    left.connect(right)
  }

  private def endpointNode(
      reference: DevicePortReference,
      entities: scala.collection.Map[DeviceId, Entity]
  ): Node = {
    val entity = entities(reference.device)
    reference.port match {
      case "network" | "primary" => entity.asInstanceOf[Environment].node
      case value if value.startsWith("secondary-") =>
        entity.asInstanceOf[Server].getConnectableAt(value.stripPrefix("secondary-").toInt - 1).node
      case side => entity.asInstanceOf[SidedEnvironment].sidedNode(direction(side))
    }
  }

  private def direction(value: String): Direction.Value = value match {
    case "down"  => Direction.Down
    case "up"    => Direction.Up
    case "north" => Direction.North
    case "south" => Direction.South
    case "west"  => Direction.West
    case "east"  => Direction.East
  }

  private def brainTier(value: Int): Tier.Tier = value match {
    case 1 => Tier.One
    case 2 => Tier.Two
    case 3 => Tier.Three
  }

  private def memoryTier(value: BigDecimal): ExtendedTier.ExtendedTier = value match {
    case value if value == BigDecimal(1)     => ExtendedTier.One
    case value if value == BigDecimal("1.5") => ExtendedTier.OneHalf
    case value if value == BigDecimal(2)     => ExtendedTier.Two
    case value if value == BigDecimal("2.5") => ExtendedTier.TwoHalf
    case value if value == BigDecimal(3)     => ExtendedTier.Three
    case value if value == BigDecimal("3.5") => ExtendedTier.ThreeHalf
    case other => throw new IllegalArgumentException(s"unsupported memory tier: $other")
  }

  private def address(environment: Environment): Option[String] =
    Option(environment.node).flatMap(node => Option(node.address))

  private def requiredAddress(environment: Environment): String =
    address(environment).getOrElse(
      throw new IllegalStateException(
        s"constructed component has no runtime address: ${environment.getClass.getName}"
      )
    )
}
