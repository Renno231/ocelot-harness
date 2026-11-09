package ocelot.harness.core.runtime

import totoro.ocelot.brain.entity.machine.{Arguments, Callback, Context}
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.nbt.NBTTagCompound
import totoro.ocelot.brain.network.{Network, Node, Visibility}
import totoro.ocelot.brain.util.ResultWrapper.result
import totoro.ocelot.brain.workspace.Workspace

/** The tablet component of a computer the manifest makes a tablet (brain has no tablets), as OC's
  * own: getPitch reads the holder's pitch, here level. owner is the computer's device ID, kept in
  * snapshots so a loaded one goes back to its computer.
  */
final class TabletComponent(var owner: String) extends Entity with Environment {
  def this() = this("")

  override val node: Node =
    Network.newNode(this, Visibility.Network).withComponent("tablet").create()

  @Callback(
    direct = true,
    doc = "function():number -- Gets the pitch of the player holding the tablet."
  )
  def getPitch(context: Context, args: Arguments): Array[AnyRef] = result(0.0)

  override def load(nbt: NBTTagCompound, workspace: Workspace): Unit = {
    super.load(nbt, workspace)
    owner = nbt.getString(TabletComponent.OwnerTag)
  }

  override def save(nbt: NBTTagCompound): Unit = {
    super.save(nbt)
    nbt.setString(TabletComponent.OwnerTag, owner)
  }
}

private object TabletComponent {
  private val OwnerTag = "HarnessTabletOwner"
}
