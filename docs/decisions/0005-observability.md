# ADR 0005: Observability — recorder, meters, profiler, deterministic replay, debugger

- **Status:** Accepted
- **Decision owners:** Ocelot Harness maintainers

## Context

Screens, events and snapshots show what a computer ended up doing, not why. Finding why an
OpenComputers program misbehaves needs what quality-critical projects use: a record of every
call and signal, live resource meters against the game's limits, a profiler, exact
reproduction of a run (including under server lag), and a debugger that stops the world.

The pinned ocelot-brain must stay unmodified, and every wait, buffer and artifact stays bounded.

## Decision

### Instrumentation from inside the computer

A Lua chunk (`resources/ocelot-harness/instrumentation.lua`) is installed into each machine's
Lua state when it starts. It wraps `component.invoke` and the signal queue (flight recorder,
meters) and chains `debug.sethook` with the kernel's own instruction hook (profiler, debugger,
deterministic time). It calls back into one Java function per machine; a failure inside it is
recorded as a `note`/`instrumentation` event and never crashes the computer.

The kernel re-arms its count hook at every resume, so a slice shorter than the interval never
fires it. The composite hook therefore fires on a finer interval (100 instructions while
profiling, 10,000 in deterministic mode) and passes the kernel its event at the kernel's own
cadence.

`MachineInternals` (harness-owned, in the brain package) reaches the brain's static worker pool
and per-machine state; it is the only place that touches brain internals.

### Flight recorder and meters

One bounded ring of 65,536 events per session: component calls (arguments, results, duration,
whether the per-tick direct-call budget ran out), signals queued and taken (modem payloads
included), inputs, starts, stops and crashes. Reads page at most 5,000 events. Meters per
computer: memory (current, peak), longest slice against the 5 s timeout, call-budget
exhaustion, signal queue depth/peak against 256, longest signal wait, per-method call counts and
time. Per-tick samples of the last 1,200 ticks feed `metrics.history` and the viewer's graphs.

### Profiler

Instruction-count sampling of the interrupted Lua stack. Each sample counts against the
innermost line of the computer's own files (sources starting with `/`), so time inside the
OpenComputers kernel shows at the line that called into it. Output: hottest lines, folded
stacks, and an SVG flame graph as artifacts.

### Deterministic mode, recording, replay, sweeps

`MachineScheduler` replaces the brain's slice pool. In deterministic mode slices run on the tick
thread in a fixed computer order (manifest ID; unnamed computers by address), for
`max(1, floor((1000 / serverTps) / executionDelay))` rounds per tick, so a lagging server gives
each tick more slices as it does in game. Lua's real-time clock reads virtual time
`tick × 0.05 s + instructions × 1e-8 s`.

A recording is a save state plus `recording.txt` beside it: every input and speed change with
its tick, and a checksum of each tick's calls, signals and inputs. Replay loads the save state,
feeds the log, and reports the first tick whose checksum differs. `until` stops at the first tick
a condition holds (bisecting "when did X first happen"). Recordings are bounded to 1,000,000
ticks and 100,000 inputs.

A sweep reruns a save state once per seed under a seeded server-lag pattern (TPS drifts a step
every 20 ticks within the given range; about one drop to the minimum per 400 ticks, lasting
20–100 ticks) and lists the seeds whose condition does not hold in time. At most 100 runs.

### Debugger

Breakpoints are host files and lines; they match the chunk whose path inside a host-folder disk
ends with the file's path inside that folder. A stopped computer holds its slice and the
simulation clock pauses, so timers, other computers and messages wait with it. Frames, locals,
upvalues and table contents (200 entries per table) are read while stopped; step in, over and
out, continue, and pause. Debug reads bypass the session lane (the lane may be waiting on the
stopped computer); everything else stays serialized. `ocelot-dap` exposes this to VS Code
through the Debug Adapter Protocol over the existing authenticated loopback connection; the
extension lives in `tools/vscode-ocelot`.

### Snapshot diff, JVM recording, diagnostics

`snapshot.diff` compares two save states by manifest device names, card slots, screen lines,
binary blobs by size and checksum, connections as a set, and the files of host-folder disks
when both save states copied them; at most 2,000 changes are returned with the total.
Copied disks leave out the project's `.ocelot-harness` service folder.

`jvm.recording.start/stop` drive Java Flight Recorder through the platform
`DiagnosticCommand` MBean (no launch flags) and write a `.jfr` artifact within the 16 MiB
artifact limit. Diagnostic bundles add `trace.txt` (the last 2,000 recorder events, paths
redacted) and `metrics.txt`.

### Protocol

`trace.read`, `metrics.read`, `metrics.history`, `metrics.reset`, `profile.start`,
`profile.stop`, `simulation.mode`, `recording.start`, `recording.stop`, `recording.replay`,
`simulation.sweep`, `debug.breakpoints`, `debug.pause`, `debug.state` (long-poll up to 60 s),
`debug.frames`, `debug.variables`, `debug.resume`, `snapshot.diff`, `jvm.recording.start`,
`jvm.recording.stop`. Replay and sweep accept up to 300 s of wall time.

## Known limits

- `os.clock` stays the computer's CPU time, also in deterministic mode.
- Snapshots do not restore host-folder files; a replay after such a file changed reports the
  tick where the run diverges.
- The sweep's lag pattern is a model, not measured server data.
- Breakpoints only match files a computer loads from a host-folder disk under the same path.

## Consequences

Every new buffer, wait and artifact has a stated bound. Deterministic mode changes slice timing
from the game's threaded behavior by design; threaded remains the default.
