package ocelot.harness.core.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ArrayDeque}
import scala.util.control.NonFatal

import li.cil.repack.com.naef.jnlua.{JavaFunction, LuaState, LuaType}

import totoro.ocelot.brain.Settings
import totoro.ocelot.brain.entity.machine.{Machine, MachineInternals}

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.InvalidInput
import ocelot.harness.core.workspace._

/** Watches every computer of a session from inside: what it calls and receives (the flight
  * recorder), how close it runs to OpenComputers' limits (meters), where its Lua spends
  * instructions (profiler), and breakpoints and stepping (debugger). It also supplies the clock
  * computers read, so a debugger pause or deterministic execution never trips "too long without
  * yielding".
  *
  * Computers report through a probe the instrumentation chunk calls from inside their Lua state;
  * see `instrumentation.lua` for what it wraps and why saves never see it.
  */
private[runtime] final class SessionInspector(
    scheduler: MachineScheduler,
    traceCapacity: Int,
    clockControl: SessionInspector.ClockControl
) extends SliceObserver
    with AutoCloseable {
  import SessionInspector._

  val recorder = new FlightRecorder(traceCapacity)

  private val closed = new AtomicBoolean(false)
  private val probes = new java.util.IdentityHashMap[Machine, MachineProbe]()
  private var diskRoots = Map.empty[String, Vector[Path]]
  @volatile private var tick = 0L
  @volatile private var flagsVersion = 0L

  // Profiler.
  @volatile private var profileTarget: Option[ProfileTarget] = None
  private val profileStacks = mutable.HashMap.empty[(String, Vector[String]), Long]
  private val profileLines = mutable.HashMap.empty[String, Long]
  private var profileSamples = 0L

  // Debugger.
  private val breakpoints = mutable.LinkedHashMap.empty[String, Vector[Int]]
  @volatile private var breakSuffixes = Map.empty[Int, Vector[String]]
  @volatile private var pauseRequested = false
  @volatile private var stepping = false
  private val debugLock = new AnyRef
  @volatile private var paused: Option[PausedComputer] = None
  private var pauseSequence = 0L
  private var pausedWallNanos = 0L
  private val commands = new LinkedBlockingQueue[DebugCommand]()

  scheduler.observe(Some(this))

  // ---------------------------------------------------------------------------------------- //
  // Session wiring

  /** Names the session's computers and where their disks live. Called when the world changes. */
  def bind(computers: Map[String, Machine], disks: Map[String, Vector[Path]]): Unit =
    synchronized {
      probes.clear()
      computers.foreach { case (id, machine) =>
        val probe = new MachineProbe(machine, id, named = true)
        probes.put(machine, probe)
        MachineInternals.tapSignals(machine, probe)
      }
      diskRoots = disks
      bumpFlags()
    }

  def currentTick: Long = tick

  /** Deterministic slice order: by logical computer ID, unnamed ones after by address. */
  def sliceOrder(task: Runnable): String = task match {
    case machine: Machine =>
      val probe = probeFor(machine)
      (if (probe.named) "0:" else "1:") + probe.label
    case other => "2:" + other.getClass.getName
  }

  /** Before a tick: the call budget the brain is about to reset shows the last tick's use. */
  def beforeTick(): Unit = forEachProbe { probe =>
    val max = probe.machine.getMaxCallBudget
    if (max > 0.0) {
      val used = (max - probe.machine.getRemainingCallBudget) / max
      probe.synchronized(probe.peakBudgetUsed = math.max(probe.peakBudgetUsed, used))
    }
  }

  def afterTick(worldTick: Long): Unit = {
    tick = worldTick
    forEachProbe { probe =>
      val machine = probe.machine
      val (free, total) = machine.latestMemoryUsage
      val depth = MachineInternals.queuedSignals(machine)
      val state =
        if (machine.isRunning) "running"
        else if (Option(machine.lastError).nonEmpty) "crashed"
        else "stopped"
      val previous = probe.synchronized {
        if (total > 0) probe.memoryPeak = math.max(probe.memoryPeak, (total - free).toLong)
        probe.history.append(
          MetricsSample(
            worldTick,
            probe.label,
            if (total > 0) (total - free).toLong else 0L,
            probe.sliceNanosThisTick,
            depth,
            probe.callsThisTick
          )
        )
        while (probe.history.size > HistoryTicks) probe.history.removeHead()
        probe.sliceNanosThisTick = 0L
        probe.callsThisTick = 0L
        val before = probe.state
        probe.state = state
        before
      }
      if (previous != state) {
        state match {
          case "running" => recorder.append(worldTick, Some(probe.label), "started", "start")
          case "crashed" =>
            recorder.append(
              worldTick,
              Some(probe.label),
              "crash",
              "crash",
              note = Option(machine.lastError)
            )
          case _ =>
            if (previous != null) recorder.append(worldTick, Some(probe.label), "stopped", "stop")
        }
      }
    }
  }

  /** What a recording compares each tick: the trace since the last tick plus each computer's state
    * and memory.
    */
  def tickFingerprint(): String = {
    val trace = recorder.takeTickChecksum()
    val machines = synchronizedProbes().sortBy(_.label).map { probe =>
      val (free, total) = probe.machine.latestMemoryUsage
      s"${probe.label}=${probe.state}/${total - free}"
    }
    (java.lang.Long.toHexString(trace) +: machines).mkString(" ")
  }

  def recordInput(computerId: Option[String], name: String, arguments: Vector[String]): Unit =
    recorder.append(tick, computerId, "input", name, arguments)

  // ---------------------------------------------------------------------------------------- //
  // Slices

  override def beforeSlice(machine: Machine): Unit = {
    val probe = probeFor(machine)
    MachineInternals.nativeLua(machine).foreach { lua =>
      try instrument(probe, lua)
      catch {
        case NonFatal(error) =>
          if (!probe.instrumentationFailed) {
            probe.instrumentationFailed = true
            recorder.append(
              tick,
              Some(probe.label),
              "note",
              "instrumentation",
              note = Some(s"unavailable: ${Option(error.getMessage).getOrElse(error.toString)}")
            )
          }
      }
    }
  }

  override def afterSlice(machine: Machine, durationNanos: Long): Unit = {
    val probe = probeFor(machine)
    probe.synchronized {
      probe.slices += 1
      probe.cpuNanos += durationNanos
      probe.sliceNanosThisTick += durationNanos
      probe.longestSlice = math.max(probe.longestSlice, durationNanos)
    }
  }

  private def probeFor(machine: Machine): MachineProbe = synchronized {
    Option(probes.get(machine)).getOrElse {
      val label =
        Option(machine.node).flatMap(node => Option(node.address)).map(_.take(8)).getOrElse("?")
      val probe = new MachineProbe(machine, label, named = false)
      probes.put(machine, probe)
      MachineInternals.tapSignals(machine, probe)
      probe
    }
  }

  private def instrument(probe: MachineProbe, lua: LuaState): Unit = lua.synchronized {
    val top = lua.getTop
    try {
      val registry = lua.getRegistryIndex
      lua.getField(registry, InstalledKey)
      val installed = !lua.isNil(-1)
      lua.pop(1)
      if (!installed) {
        lua.pushJavaFunction(probe)
        lua.setField(registry, ProbeKey)
        lua.newTable()
        lua.setField(registry, FlagsKey)
        lua.load(InstrumentationSource, "=harness")
        lua.call(0, 0)
        probe.flagsVersion = -1L
      }
      if (probe.flagsVersion != flagsVersion) {
        val version = flagsVersion
        lua.getField(registry, FlagsKey)
        val flags = lua.getTop
        def set(name: String, value: Boolean): Unit = {
          if (value) lua.pushBoolean(true) else lua.pushNil()
          lua.setField(flags, name)
        }
        set("instructions", scheduler.isDeterministic)
        set("profile", profileTarget.exists(_.covers(probe.label)))
        set("lines", breakSuffixes.nonEmpty || pauseRequested || stepping)
        set("pause", pauseRequested)
        val profiled = profileTarget.exists(_.covers(probe.label))
        if (profiled || scheduler.isDeterministic)
          lua.pushInteger(if (profiled) ProfileEveryInstructions else TimeEveryInstructions)
        else lua.pushNil()
        lua.setField(flags, "every")
        lua.pop(1)
        lua.newTable()
        val breaks = lua.getTop
        breakSuffixes.foreach { case (line, suffixes) =>
          lua.newTable()
          suffixes.zipWithIndex.foreach { case (suffix, index) =>
            lua.pushString(suffix)
            lua.rawSet(-2, index + 1)
          }
          lua.rawSet(breaks, line)
        }
        lua.setField(registry, BreaksKey)
        probe.flagsVersion = version
      }
    } finally lua.setTop(top)
  }

  private def bumpFlags(): Unit = flagsVersion += 1L

  /** Called after the scheduler changes mode so computers pick up instruction time. */
  def executionModeChanged(): Unit = synchronized(bumpFlags())

  // ---------------------------------------------------------------------------------------- //
  // Probe calls from inside a computer

  private def probe(probe: MachineProbe, lua: LuaState): Int = {
    text(lua, 1) match {
      case "call"   => onCall(probe, lua); 0
      case "return" => onReturn(probe, lua); 0
      case "time"   => lua.pushNumber(realTime(probe)); 1
      case "instructions" =>
        if (lua.isNumber(2)) probe.synchronized(probe.instructions += lua.toInteger(2))
        0
      case "frame" =>
        if (probe.inspecting.nonEmpty) collectDebugFrame(probe, lua)
        else probe.frames += frameLabel(text(lua, 2), lua.toInteger(4), text(lua, 5))
        if (probe.inspecting.isEmpty) {
          // A sample counts against the innermost line of the computer's own files, so time
          // spent inside the OpenComputers kernel shows at the line that called into it.
          val source = sourceName(text(lua, 2))
          val own = source.startsWith("/")
          if (probe.frames.size == 1 || (own && !probe.leafOwn)) {
            probe.leafLine = s"$source:${lua.toInteger(3)}"
            probe.leafOwn = own
          }
        }
        0
      case "sample"   => onSample(probe); 0
      case "paused"   => onPaused(probe, lua)
      case "variable" => collectVariable(probe, lua); 0
      case "path"     => pushPath(probe, lua)
      case "value"    => collectValue(probe, lua); 0
      case _          => 0
    }
  }

  private def onCall(probe: MachineProbe, lua: LuaState): Unit = {
    val address = text(lua, 2)
    val method = text(lua, 3)
    val kind = Option(probe.machine.components.get(address)).getOrElse("?")
    val count = lua.getTop
    val arguments = (4 to math.min(count, 3 + TraceText.MaxValues)).map(luaValue(lua, _)).toVector
    probe.synchronized {
      probe.callName = s"$kind.$method"
      probe.callArguments =
        if (count > 3 + TraceText.MaxValues)
          arguments :+ s"... ${count - 3 - TraceText.MaxValues} more"
        else arguments
      probe.callSynchronized = MachineInternals.inSynchronizedCall(probe.machine)
      probe.callStarted = System.nanoTime()
    }
  }

  private def onReturn(probe: MachineProbe, lua: LuaState): Unit = {
    val count = lua.getTop - 1
    val (name, arguments, started, onTickThread) = probe.synchronized {
      (probe.callName, probe.callArguments, probe.callStarted, probe.callSynchronized)
    }
    if (name != null) {
      val duration = System.nanoTime() - started
      val deferred = count == 0
      val results =
        if (deferred) Vector.empty
        else {
          // The brain returns true plus results, or false plus an error.
          val ok = lua.`type`(2) == LuaType.BOOLEAN && lua.toBoolean(2)
          val values =
            (3 to math.min(count + 1, 2 + TraceText.MaxValues)).map(luaValue(lua, _)).toVector
          if (ok) values else values.headOption.map(error => s"error: $error").toVector
        }
      probe.synchronized {
        val counts = probe.calls.getOrElseUpdate(name, Array(0L, 0L, 0L))
        counts(0) += 1
        if (deferred) counts(1) += 1
        counts(2) += duration
        probe.callsThisTick += 1
        if (deferred) probe.budgetExhausted += 1
        probe.callName = null
      }
      recorder.append(
        tick,
        Some(probe.label),
        "call",
        name,
        arguments,
        results,
        duration,
        if (deferred) Some("deferred: the tick's direct-call budget ran out")
        else if (onTickThread) Some("tick thread")
        else None
      )
    }
  }

  // Values read under a lock go to locals first: scalac emits unverifiable bytecode for a
  // synchronized block inside arithmetic.
  private def realTime(probe: MachineProbe): Double =
    if (scheduler.isDeterministic) {
      val instructions: Long = probe.synchronized(probe.instructions)
      tick * 0.05 + instructions * SecondsPerInstruction
    } else {
      val pausedNanos: Long = debugLock.synchronized(pausedWallNanos)
      (System.nanoTime() - pausedNanos) / 1e9 + WallEpochSeconds
    }

  /** A string argument, or null when absent or nil (JNLua's toString throws on nil). */
  private def text(lua: LuaState, index: Int): String =
    if (index <= lua.getTop && lua.isString(index)) lua.toString(index) else null

  private def luaValue(lua: LuaState, index: Int): String = lua.`type`(index) match {
    case LuaType.NIL     => "nil"
    case LuaType.BOOLEAN => lua.toBoolean(index).toString
    case LuaType.NUMBER =>
      if (lua.isInteger(index)) lua.toInteger(index).toString
      else TraceText.number(lua.toNumber(index))
    case LuaType.STRING => TraceText.bytes(lua.toByteArray(index))
    case LuaType.TABLE  => "table"
    case other          => other.displayText()
  }

  // ---------------------------------------------------------------------------------------- //
  // Profiler

  def startProfile(computerId: Option[String]): Unit = synchronized {
    profileStacks.clear()
    profileLines.clear()
    profileSamples = 0L
    profileTarget = Some(ProfileTarget(computerId, tick))
    bumpFlags()
  }

  def stopProfile(): Option[ProfileReport] = synchronized {
    profileTarget.map { target =>
      profileTarget = None
      bumpFlags()
      val folded = profileStacks.toVector
        .sortBy { case (_, count) => -count }
        .map { case ((label, stack), count) => ProfileEntry(label +: stack, count) }
      ProfileReport(
        target.computerId,
        profileSamples,
        tick - target.startTick,
        profileLines.toVector.sortBy(-_._2).take(30),
        folded
      )
    }
  }

  def profiling: Boolean = profileTarget.nonEmpty

  private def onSample(probe: MachineProbe): Unit = {
    val stack = probe.frames.reverseIterator.toVector
    val leaf = probe.leafLine
    probe.frames.clear()
    if (stack.nonEmpty) synchronized {
      if (profileTarget.exists(_.covers(probe.label))) {
        val key = (probe.label, stack)
        profileStacks.update(key, profileStacks.getOrElse(key, 0L) + 1L)
        profileLines.update(leaf, profileLines.getOrElse(leaf, 0L) + 1L)
        profileSamples += 1L
      }
    }
  }

  private def frameLabel(source: String, defined: Long, name: String): String = {
    val file = sourceName(source)
    val function = Option(name).getOrElse(if (defined == 0L) "main chunk" else "function")
    if (defined > 0L) s"$function ($file:$defined)" else s"$function ($file)"
  }

  // ---------------------------------------------------------------------------------------- //
  // Debugger

  def setBreakpoints(file: Path, lines: Vector[Int]): Vector[DebugBreakpoint] = synchronized {
    val key = file.toAbsolutePath.normalize().toString
    if (lines.isEmpty) breakpoints.remove(key) else breakpoints.update(key, lines.distinct.sorted)
    breakSuffixes = breakpoints.toVector
      .flatMap { case (path, values) =>
        val suffix = chunkSuffix(java.nio.file.Paths.get(path))
        values.map(_ -> suffix)
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2).distinct)
      .toMap
    bumpFlags()
    breakpointList
  }

  def requestPause(): Unit = synchronized {
    pauseRequested = true
    bumpFlags()
  }

  def debugState(): DebugState = {
    val current = paused
    val sequence = debugLock.synchronized(pauseSequence)
    DebugState(
      current.map(_.probe.label),
      current.nonEmpty,
      current.map(_.reason),
      sequence,
      synchronized(breakpointList)
    )
  }

  /** Waits until a computer pauses after `afterSequence`, or the time runs out. */
  def awaitPause(afterSequence: Long, timeoutMillis: Long): DebugState = {
    val deadline = System.nanoTime() + timeoutMillis * 1000000L
    debugLock.synchronized {
      while (pauseSequence <= afterSequence && !closed.get() && System.nanoTime() < deadline) {
        val remaining = (deadline - System.nanoTime()) / 1000000L
        if (remaining > 0L) debugLock.wait(remaining)
      }
    }
    debugState()
  }

  def pausedOn(thread: Thread): Boolean = paused.exists(_.thread eq thread)
  def isPaused: Boolean = paused.nonEmpty

  def frames(): Either[HarnessError, Vector[DebugFrame]] =
    inspect(DebugCommand.Frames()).map(_.asInstanceOf[Vector[DebugFrame]])

  def variables(reference: String): Either[HarnessError, Vector[DebugVariable]] = {
    val parsed = reference.split(':').toVector
    val request = parsed match {
      case Vector(frame, kind) if frame.forall(_.isDigit) && (kind == "L" || kind == "U") =>
        Right(DebugCommand.Variables(frame.toInt, kind))
      case Vector(id) if id.nonEmpty && id.forall(_.isDigit) =>
        paused
          .flatMap(value => value.probe.synchronized(value.probe.references.lift(id.toInt)))
          .map(path => DebugCommand.Value(path))
          .toRight(InvalidInput(s"unknown variable reference: $reference"))
      case _ => Left(InvalidInput(s"unknown variable reference: $reference"))
    }
    request.flatMap(inspect).map(_.asInstanceOf[Vector[DebugVariable]])
  }

  /** Lets the paused computer go on: `continue`, or step `in`, `over` or `out`. */
  def resume(mode: String): Either[HarnessError, DebugState] =
    if (!Set("continue", "in", "over", "out").contains(mode))
      Left(InvalidInput(s"unknown resume mode: $mode"))
    else
      paused match {
        case None => Left(InvalidInput("no computer is paused"))
        case Some(_) =>
          synchronized {
            stepping = mode != "continue"
            bumpFlags()
          }
          commands.put(DebugCommand.Resume(mode))
          val deadline = System.nanoTime() + 5000000000L
          while (paused.nonEmpty && System.nanoTime() < deadline) Thread.sleep(1L)
          Right(debugState())
      }

  private def inspect(command: DebugCommand): Either[HarnessError, Any] =
    if (paused.isEmpty) Left(InvalidInput("no computer is paused"))
    else {
      commands.put(command)
      try Right(command.result.get(5L, TimeUnit.SECONDS))
      catch {
        case NonFatal(_) => Left(InvalidInput("the paused computer did not answer"))
      }
    }

  private def onPaused(probe: MachineProbe, lua: LuaState): Int = {
    // Answer the inspection the Lua side just finished.
    probe.inspecting.foreach(_.result.complete(probe.inspectionResult()))
    probe.inspecting = None
    if (paused.isEmpty) {
      val reason = text(lua, 2)
      val wasRunning = clockControl.pause()
      synchronized {
        pauseRequested = false
        stepping = false
        bumpFlags()
      }
      debugLock.synchronized {
        paused = Some(
          new PausedComputer(probe, reason, Thread.currentThread(), wasRunning, System.nanoTime())
        )
        pauseSequence += 1L
        debugLock.notifyAll()
      }
      recorder.append(tick, Some(probe.label), "note", "paused", note = Some(reason))
    }
    var answer: Option[Int] = None
    while (answer.isEmpty) {
      val command =
        if (closed.get()) DebugCommand.Resume("continue")
        else Option(commands.poll(100L, TimeUnit.MILLISECONDS)).orNull
      command match {
        case null => ()
        case DebugCommand.Resume(mode) =>
          debugLock.synchronized {
            paused.foreach { value =>
              pausedWallNanos += System.nanoTime() - value.since
              if (value.clockWasRunning) clockControl.resume()
            }
            paused = None
            debugLock.notifyAll()
          }
          probe.synchronized(probe.references.clear())
          lua.pushString(if (mode == "continue") "continue" else mode)
          answer = Some(1)
        case frames: DebugCommand.Frames =>
          probe.beginInspection(frames)
          lua.pushString("frames")
          answer = Some(1)
        case variables @ DebugCommand.Variables(frame, kind) =>
          probe.beginInspection(variables)
          probe.inspectedPath = Vector(VariablePath.Frame(frame), VariablePath.Kind(kind))
          lua.pushString("variables")
          lua.pushInteger(frame.toLong)
          lua.pushString(kind)
          answer = Some(3)
        case value @ DebugCommand.Value(path) =>
          probe.beginInspection(value)
          probe.inspectedPath = path
          lua.pushString("value")
          answer = Some(1)
      }
    }
    answer.get
  }

  private def collectDebugFrame(probe: MachineProbe, lua: LuaState): Unit = {
    val source = text(lua, 2)
    val line = lua.toInteger(3).toInt
    val name = Option(text(lua, 5)).getOrElse(Option(text(lua, 6)).getOrElse("?"))
    val index = probe.collected.size
    probe.collected += DebugFrame(
      index,
      sourceName(source),
      line,
      name,
      hostPath(probe.label, source)
    )
  }

  private def collectVariable(probe: MachineProbe, lua: LuaState): Unit = {
    val name = text(lua, 2)
    val slot = lua.toInteger(4).toInt
    val reference =
      if (lua.isTable(3)) Some(probe.reference(probe.inspectedPath :+ VariablePath.Slot(slot)))
      else None
    probe.collected += DebugVariable(name, describe(lua, 3), typeName(lua, 3), reference)
  }

  private def pushPath(probe: MachineProbe, lua: LuaState): Int = {
    probe.inspectedPath.foreach {
      case VariablePath.Frame(frame) => lua.pushInteger(frame.toLong)
      case VariablePath.Kind(kind)   => lua.pushString(kind)
      case VariablePath.Slot(slot)   => lua.pushInteger(slot.toLong)
      case VariablePath.Key(key)     => lua.pushString(key)
      case VariablePath.Index(index) => lua.pushInteger(index)
    }
    probe.inspectedPath.size
  }

  private def collectValue(probe: MachineProbe, lua: LuaState): Unit =
    if (lua.isTable(2)) {
      lua.pushNil()
      var count = 0
      while (count < MaxTableEntries && lua.next(2)) {
        // key at -2, value at -1
        val keyType = lua.`type`(-2)
        val key =
          if (keyType == LuaType.STRING) Some(VariablePath.Key(lua.toString(-2)))
          else if (keyType == LuaType.NUMBER && lua.isInteger(-2))
            Some(VariablePath.Index(lua.toInteger(-2)))
          else None
        val name = key match {
          case Some(VariablePath.Key(value))   => value
          case Some(VariablePath.Index(value)) => s"[$value]"
          case _                               => s"[${luaValue(lua, -2)}]"
        }
        val valueIndex = lua.getTop
        val reference =
          if (lua.isTable(valueIndex)) key.map(k => probe.reference(probe.inspectedPath :+ k))
          else None
        probe.collected += DebugVariable(
          name,
          describe(lua, valueIndex),
          typeName(lua, valueIndex),
          reference
        )
        lua.pop(1)
        count += 1
      }
      if (count == MaxTableEntries) lua.pop(1)
    }

  private def describe(lua: LuaState, index: Int): String =
    if (lua.isTable(index)) {
      val absolute = lua.absIndex(index)
      lua.pushNil()
      var count = 0
      while (count < 1000 && lua.next(absolute)) {
        lua.pop(1)
        count += 1
      }
      if (count == 1000) {
        lua.pop(1)
        "table (1000+ entries)"
      } else if (count == 1) "table (1 entry)"
      else s"table ($count entries)"
    } else luaValue(lua, index)

  private def typeName(lua: LuaState, index: Int): String = lua.`type`(index).displayText()

  private def breakpointList: Vector[DebugBreakpoint] =
    breakpoints.toVector.flatMap { case (file, lines) => lines.map(DebugBreakpoint(file, _)) }

  /** The end of an OpenComputers path a host file is loaded as: its path below the disk folder it
    * sits on, or just its name when it sits on none.
    */
  private def chunkSuffix(file: Path): String = {
    val normalized = file.toAbsolutePath.normalize()
    val roots = diskRoots.values.flatten.map(_.toAbsolutePath.normalize()).toVector
    roots
      .filter(root => normalized.startsWith(root) && normalized != root)
      .sortBy(-_.getNameCount)
      .headOption
      .map(root => "/" + root.relativize(normalized).toString.replace('\\', '/'))
      .getOrElse("/" + normalized.getFileName.toString)
  }

  /** The host file a chunk came from, by finding its OpenComputers path on one of the computer's
    * disk folders, mount prefix or not.
    */
  private def hostPath(computerId: String, source: String): Option[String] = {
    val path = sourceName(source).stripPrefix("/")
    if (path.isEmpty) None
    else {
      val segments = path.split('/').toVector
      val roots = diskRoots.getOrElse(computerId, Vector.empty)
      (0 until segments.size).iterator
        .map(skip => segments.drop(skip).mkString("/"))
        .flatMap(relative => roots.iterator.map(_.resolve(relative)))
        .find(candidate => Files.isRegularFile(candidate))
        .map(_.toAbsolutePath.normalize().toString)
    }
  }

  // ---------------------------------------------------------------------------------------- //
  // Meters

  def metrics(): Vector[MachineMetrics] =
    synchronizedProbes().filter(_.named).sortBy(_.label).map { probe =>
      val (free, total) = probe.machine.latestMemoryUsage
      val depth = MachineInternals.queuedSignals(probe.machine)
      probe.synchronized {
        MachineMetrics(
          probe.label,
          Option.when(total > 0)((total - free).toLong),
          Option.when(total > 0)(total.toLong),
          probe.memoryPeak,
          probe.slices,
          probe.cpuNanos,
          probe.longestSlice,
          (Settings.get.timeout * 1e9).toLong,
          probe.budgetExhausted,
          probe.peakBudgetUsed,
          probe.queued,
          probe.taken,
          depth,
          probe.depthPeak,
          Settings.get.maxSignalQueueSize,
          probe.longestWait,
          probe.calls.toVector
            .map { case (name, counts) => CallCount(name, counts(0), counts(1), counts(2)) }
            .sortBy(call => (-call.count, call.name))
        )
      }
    }

  def history(computerId: Option[String], afterTick: Long): Vector[MetricsSample] =
    synchronizedProbes()
      .filter(probe => probe.named && computerId.forall(_ == probe.label))
      .flatMap(probe => probe.synchronized(probe.history.filter(_.tick > afterTick).toVector))
      .sortBy(sample => (sample.tick, sample.computerId))

  def resetMeters(): Unit = forEachProbe(_.resetMeters())

  // ---------------------------------------------------------------------------------------- //

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      scheduler.observe(None)
      commands.put(DebugCommand.Resume("continue"))
      val deadline = System.nanoTime() + 2000000000L
      while (paused.nonEmpty && System.nanoTime() < deadline) Thread.sleep(1L)
      debugLock.synchronized(debugLock.notifyAll())
    }

  private def synchronizedProbes(): Vector[MachineProbe] = synchronized {
    val values = Vector.newBuilder[MachineProbe]
    probes.values().forEach(value => values += value)
    values.result()
  }

  private def forEachProbe(action: MachineProbe => Unit): Unit =
    synchronizedProbes().foreach(action)

  private final class PausedComputer(
      val probe: MachineProbe,
      val reason: String,
      val thread: Thread,
      val clockWasRunning: Boolean,
      val since: Long
  )

  private final class MachineProbe(val machine: Machine, val label: String, val named: Boolean)
      extends JavaFunction
      with MachineInternals.SignalTap {
    @volatile var flagsVersion = -1L
    @volatile var instrumentationFailed = false
    var state: String = _

    // Meters, guarded by this.
    var slices = 0L
    var cpuNanos = 0L
    var longestSlice = 0L
    var sliceNanosThisTick = 0L
    var callsThisTick = 0L
    var memoryPeak = 0L
    var budgetExhausted = 0L
    var peakBudgetUsed = 0.0
    var queued = 0L
    var taken = 0L
    var depthPeak = 0
    var longestWait = 0L
    val queuedAt = ArrayDeque.empty[Long]
    val calls = mutable.HashMap.empty[String, Array[Long]]
    val history = ArrayDeque.empty[MetricsSample]
    var instructions = 0L

    // The call in flight; a computer makes one at a time.
    var callName: String = _
    var callArguments: Vector[String] = Vector.empty
    var callStarted = 0L
    var callSynchronized = false

    // Profiler sample being collected; only this computer's slice thread touches it.
    val frames = ArrayBuffer.empty[String]
    var leafLine = ""
    var leafOwn = false

    // Debugger inspection being collected on the paused thread.
    @volatile var inspecting: Option[DebugCommand] = None
    var inspectedPath: Vector[VariablePath] = Vector.empty
    val collected = ArrayBuffer.empty[Any]
    val references = ArrayBuffer.empty[Vector[VariablePath]]

    def beginInspection(command: DebugCommand): Unit = {
      collected.clear()
      inspecting = Some(command)
    }

    def inspectionResult(): Vector[Any] = collected.toVector

    def reference(path: Vector[VariablePath]): String = synchronized {
      references += path
      (references.size - 1).toString
    }

    def resetMeters(): Unit = synchronized {
      slices = 0L
      cpuNanos = 0L
      longestSlice = 0L
      memoryPeak = 0L
      budgetExhausted = 0L
      peakBudgetUsed = 0.0
      queued = 0L
      taken = 0L
      depthPeak = 0
      longestWait = 0L
      calls.clear()
      history.clear()
    }

    // A fault in the harness must never crash the computer: note it once and carry on.
    override def invoke(lua: LuaState): Int =
      try SessionInspector.this.probe(this, lua)
      catch {
        case NonFatal(error) =>
          if (!instrumentationFailed) {
            instrumentationFailed = true
            recorder.append(
              tick,
              Some(label),
              "note",
              "instrumentation",
              note = Some(
                s"probe failed: $error at " +
                  error.getStackTrace.take(4).mkString(" < ")
              )
            )
          }
          0
      }

    override def queued(name: String, args: Array[AnyRef], depth: Int): Unit = {
      synchronized {
        queued += 1
        depthPeak = math.max(depthPeak, depth)
        queuedAt.append(tick)
      }
      recorder.append(tick, Some(label), "signal_queued", name, TraceText.javaValues(args))
    }

    override def taken(name: String, args: Array[AnyRef], depth: Int): Unit = {
      synchronized {
        taken += 1
        if (queuedAt.nonEmpty) longestWait = math.max(longestWait, tick - queuedAt.removeHead())
      }
      recorder.append(tick, Some(label), "signal_taken", name, TraceText.javaValues(args))
    }
  }
}

private[runtime] object SessionInspector {
  trait ClockControl {

    /** Pauses the simulation clock; returns whether it was running. */
    def pause(): Boolean
    def resume(): Unit
  }

  val HistoryTicks = 1200
  // Instruction intervals between profiler samples and between deterministic time updates.
  // An event loop often runs a few hundred instructions per wake-up and the kernel restarts
  // the count at every resume, so a coarser interval never samples such programs.
  val ProfileEveryInstructions = 100L
  val TimeEveryInstructions = 10000L
  val MaxTableEntries = 200
  // Deterministic real time: 100 million Lua instructions a second, a mid-range server.
  val SecondsPerInstruction = 1e-8
  // Wall real time keeps the brain's epoch-seconds scale but follows the monotonic clock.
  private val WallEpochSeconds = System.currentTimeMillis() / 1000.0 - System.nanoTime() / 1e9

  private val ProbeKey = "ocelot.harness.probe"
  private val FlagsKey = "ocelot.harness.flags"
  private val BreaksKey = "ocelot.harness.breaks"
  private val InstalledKey = "ocelot.harness.installed"

  private lazy val InstrumentationSource: String = {
    val input = classOf[SessionInspector].getResourceAsStream("/ocelot-harness/instrumentation.lua")
    if (input == null) throw new IllegalStateException("instrumentation.lua is missing")
    try new String(input.readAllBytesCompat(), StandardCharsets.UTF_8)
    finally input.close()
  }

  private implicit final class InputBytes(private val input: java.io.InputStream) extends AnyVal {
    def readAllBytesCompat(): Array[Byte] = {
      val output = new java.io.ByteArrayOutputStream()
      val buffer = new Array[Byte](8192)
      var read = input.read(buffer)
      while (read >= 0) {
        output.write(buffer, 0, read)
        read = input.read(buffer)
      }
      output.toByteArray
    }
  }

  def sourceName(source: String): String =
    Option(source).map(value => value.stripPrefix("=").stripPrefix("@")).getOrElse("?")

  private final case class ProfileTarget(computerId: Option[String], startTick: Long) {
    def covers(label: String): Boolean = computerId.forall(_ == label)
  }

  sealed trait VariablePath
  object VariablePath {
    final case class Frame(index: Int) extends VariablePath
    final case class Kind(value: String) extends VariablePath
    final case class Slot(index: Int) extends VariablePath
    final case class Key(name: String) extends VariablePath
    final case class Index(value: Long) extends VariablePath
  }

  sealed trait DebugCommand {
    val result: CompletableFuture[Any] = new CompletableFuture[Any]()
  }
  object DebugCommand {
    final case class Frames() extends DebugCommand
    final case class Variables(frame: Int, kind: String) extends DebugCommand
    final case class Value(path: Vector[VariablePath]) extends DebugCommand
    final case class Resume(mode: String) extends DebugCommand
  }
}
