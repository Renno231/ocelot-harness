package ocelot.harness.core

import java.util.Properties

private[harness] object BuildIdentity {
  val HarnessVersion = "0.1.1"
  val ProtocolVersion = 1
  val BrainCommit = "bec1cc6b1e9e588692f753e9c617063c74967fed"

  private val generated = {
    val properties = new Properties()
    val input = getClass.getResourceAsStream("/ocelot-harness-build.properties")
    if (input != null) {
      try properties.load(input)
      finally input.close()
    }
    properties
  }

  val HarnessCommit: String = generated.getProperty("harness.commit", "unknown")
  val SourceDirty: Boolean = generated.getProperty("harness.dirty", "true").toBoolean
}
