package ocelot.harness.app

import java.nio.file.Paths

import totoro.ocelot.brain.Ocelot
import totoro.ocelot.brain.entity.machine.luac.LuaStateFactory

object BrainLifecycleProbe {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one native-library directory argument")

    Ocelot.librariesPath = Some(Paths.get(arguments(0)))
    var initialized = false

    try {
      Ocelot.initialize()
      initialized = true
      Console.out.println(s"BRAIN_LIFECYCLE_INITIALIZED version=${Ocelot.Version}")
      require(LuaStateFactory.isAvailable, "no compatible native Lua library was loaded")
      Console.out.println("BRAIN_NATIVE_LUA_AVAILABLE=true")
    } finally {
      if (initialized) {
        Ocelot.shutdown()
        Console.out.println("BRAIN_LIFECYCLE_SHUTDOWN")
      }
    }
  }
}
