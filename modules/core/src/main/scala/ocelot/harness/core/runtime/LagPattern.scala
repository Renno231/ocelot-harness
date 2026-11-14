package ocelot.harness.core.runtime

import scala.util.Random

/** A server's TPS over time for a lag sweep, from a seed: it drifts a step every second and now and
  * then drops to near the lowest rate for one to five seconds, as a busy server does.
  */
private[runtime] final class LagPattern(seed: Long, min: Int, max: Int) {
  private val random = new Random(seed)
  private var current = min + random.nextInt(max - min + 1)
  private var spikeTicks = 0
  private var resumeTps = current
  private var tick = 0L

  def tps: Int = current

  def next(): Int = {
    tick += 1L
    if (spikeTicks > 0) {
      spikeTicks -= 1
      if (spikeTicks == 0) current = resumeTps
    } else if (random.nextInt(LagPattern.SpikeOdds) == 0) {
      resumeTps = current
      spikeTicks = 20 + random.nextInt(81)
      current = min + random.nextInt(math.max(1, (max - min) / 4 + 1))
    } else if (tick % 20L == 0L) {
      current = math.max(min, math.min(max, current + random.nextInt(3) - 1))
    }
    current
  }
}

private[runtime] object LagPattern {
  // About one spike every 20 seconds at 20 TPS.
  val SpikeOdds = 400
}
