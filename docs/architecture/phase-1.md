# Ocelot Harness Phase 1 architecture

- **Status:** Accepted
- **Runtime:** Scala 2.13.10, SBT 1.8.3, Java 8 baseline
- **Emulator dependency:** ocelot-brain 0.24.2 at commit `bec1cc6b1e9e588692f753e9c617063c74967fed`

## Purpose

Ocelot Harness provides an external, headless control plane for OpenComputers environments running in ocelot-brain. It lets an agent or test client configure emulated hardware, run computers, inject user input, inspect screens, capture artifacts, and control simulation progress without Minecraft or Ocelot Desktop.

Phase 1 owns the complete loop:

```text
project manifest + host files
→ validated emulated hardware topology
→ running ocelot-brain workspace
→ key/paste/touch/drag/drop/scroll input
→ text/cell/image observations
→ condition-driven execution and test artifacts
```

## Scope

Phase 1 includes:

- one long-lived Ocelot process with one active project session
- a versioned, human-editable project manifest
- legal computer hardware profiles and validation
- multiple computers, screens, keyboards, filesystems, and network connections
- host-directory-backed managed disks
- machine lifecycle control
- serialized tick advancement with tick and wall-clock budgets
- screen text, cell/color, and PNG observations
- keyboard, clipboard, touch, drag, drop, and scroll input
- event collection and diagnostic bundles
- snapshot save/load
- a versioned JSON-RPC API
- an agent-owned stdio transport and a loopback transport for standalone CLI commands
- a general CLI

The implementation boundary ends at ocelot-brain. Ocelot Desktop canvas nodes, windows, menus, OpenGL rendering, and Desktop screenshots belong to a future Desktop integration.

## Constraints inherited from ocelot-brain

1. `Ocelot.initialize()` mutates process-global registries and starts global worker pools. It is called exactly once at process startup. `Ocelot.shutdown()` is called exactly once during orderly process shutdown.
2. Machine execution uses worker threads. Tick order is controlled, but execution is not bit-for-bit deterministic. Harness waits are condition-driven and bounded by both simulated ticks and wall time.
3. `Workspace` owns entities, connections, in-game time, and persistence. Each project session owns one `Workspace`.
4. Brain inventories accept arbitrary non-negative slot indexes and do not enforce Minecraft/Desktop slot legality. Ocelot Harness owns hardware profile validation before creating entities.
5. Brain component addresses are generated runtime identifiers. Public commands target stable logical IDs from the manifest.
6. `EventBus` is process-global. Every session subscription is retained and cancelled during session close.
7. Screen data can be changed by machine worker threads. A screen observation is copied while synchronized on its `Screen` instance.
8. Managed disks can use host directories directly when `filesystem.bufferChanges = false`. Harness supplies an explicit brain configuration and resolves every host path through its path policy.
9. Default ocelot-brain configuration enables Internet Card HTTP and TCP access. Harness disables both by default.

## System shape

```text
┌──────────────────────── clients ────────────────────────┐
│  ocelotctl CLI     Pi/agent client     test client      │
└───────────────┬─────────────┬──────────────┬────────────┘
                │ JSON-RPC 2.0 messages
┌───────────────▼─────────────────────────────────────────┐
│ harness-app                                              │
│  protocol codec → request validation → command dispatch │
│       ▲                                │                 │
│  stdio transport                 loopback transport     │
└─────────────────────────────────────────┬───────────────┘
                                          │ typed calls
┌─────────────────────────────────────────▼───────────────┐
│ harness-core                                             │
│  RuntimeOwner → ProjectLoader → BrainSession            │
│                                  ├─ hardware catalog     │
│                                  ├─ simulation control   │
│                                  ├─ input                │
│                                  ├─ observations         │
│                                  ├─ events               │
│                                  └─ snapshots            │
└─────────────────────────────────────────┬───────────────┘
                                          │ concrete calls
┌─────────────────────────────────────────▼───────────────┐
│ pinned ocelot-brain submodule                            │
└──────────────────────────────────────────────────────────┘
```

## Build modules and source layout

```text
ocelot-harness/
├─ build.sbt
├─ project/
│  ├─ build.properties
│  └─ plugins.sbt
├─ lib/
│  └─ ocelot-brain/                 pinned Git submodule
├─ modules/
│  ├─ core/
│  │  └─ src/{main,test}/scala/ocelot/harness/core/
│  │     ├─ runtime/                process/session lifecycle and hardware catalog
│  │     ├─ project/                manifest, IDs, and path policy
│  │     ├─ simulation/             ticking and bounded conditions
│  │     ├─ input/                  user-action translation
│  │     ├─ screen/                 snapshots and headless rendering
│  │     ├─ events/                 bounded event collection
│  │     └─ snapshot/               save/load and metadata
│  └─ app/
│     └─ src/{main,test}/scala/ocelot/harness/app/
│        ├─ protocol/               JSON-RPC wire contracts and codec
│        ├─ transport/              stdio and loopback adapters
│        ├─ cli/                    command parsing and presentation
│        └─ Main.scala              executable entry point
├─ fixtures/                        deterministic integration projects
├─ docs/
└─ scripts/                         reproducible bootstrap/verification
```

Only two first-party SBT modules are created initially:

| Module | Public purpose | Dependencies |
|---|---|---|
| `harness-core` | Typed headless automation API; hides all brain lifecycle and concurrency rules | ocelot-brain |
| `harness-app` | External protocol, transports, CLI, and process entrypoint | `harness-core` |

Packages organize private implementation responsibilities without creating additional public module interfaces. There is one concrete ocelot-brain implementation in Phase 1. A generic emulator-backend interface is not introduced until a second real backend exists.

## Deep module boundaries

### RuntimeOwner

**Purpose:** Own process-global Ocelot initialization and shutdown.

**Interface:**

```scala
RuntimeOwner.start(config: RuntimeConfig): Either[HarnessError, RuntimeOwner]
RuntimeOwner.openProject(root: Path, policy: ServicePolicy): Either[HarnessError, HarnessSession]
RuntimeOwner.close(): Unit
```

**Owned invariants:**

- initialize and shutdown occur at most once
- one active session per service process
- startup failure releases acquired resources
- session closes before runtime shutdown
- configured native-library and brain-config paths are absolute

Callers never invoke Ocelot global lifecycle methods directly.

### ProjectLoader

**Purpose:** Turn a project directory into a fully validated desired topology.

```scala
ProjectLoader.load(root: Path, policy: ServicePolicy): Either[ProjectErrors, ValidatedProject]
```

It owns:

- HOCON parsing and `schemaVersion` dispatch
- logical ID syntax and uniqueness
- path canonicalization and allowed-root enforcement
- hardware profile legality
- connection endpoint validation
- defaults expansion
- deterministic ordering of construction

`ValidatedProject` contains no unvalidated strings that downstream code must reinterpret.

### BrainSession

**Purpose:** Own one live workspace and expose intention-level operations.

Representative interface:

```scala
trait HarnessSession extends AutoCloseable {
  def describe(): WorkspaceDescription
  def startMachine(id: MachineId): Either[HarnessError, MachineState]
  def stopMachine(id: MachineId): Either[HarnessError, MachineState]
  def resetMachine(id: MachineId): Either[HarnessError, MachineState]
  def run(request: RunRequest): Either[HarnessError, RunResult]
  def readScreen(id: ScreenId): Either[HarnessError, ScreenSnapshot]
  def send(id: ScreenId, input: UserInput): Either[HarnessError, InputResult]
  def saveSnapshot(name: SnapshotName): Either[HarnessError, SnapshotDescription]
  def loadSnapshot(name: SnapshotName): Either[HarnessError, WorkspaceDescription]
  def diagnostics(request: DiagnosticRequest): Either[HarnessError, DiagnosticBundle]
}
```

The actual class is concrete and package-private where possible. Public models do not expose `totoro.ocelot` types.

**Owned invariants:**

- all mutations and tick calls are linearized through one session executor
- logical IDs resolve to the expected entity kind
- brain entities cannot escape after session close
- screen reads are immutable synchronized copies
- subscriptions, machines, workspace entities, and filesystem resources are released on close
- every wait has a tick budget, a wall-clock deadline, and cancellation

### HardwareCatalog

**Purpose:** Convert legal logical hardware into brain entities and private numeric slots.

The catalog owns case-tier slot counts, accepted component kinds, component-tier limits, required hardware, connection rules, and the mapping to brain inventory indexes. Manifest callers select semantic roles such as `cpu`, `memory`, `gpu`, `disk`, and `card`; they never select raw brain slot integers.

The first accepted hardware profile is a tier-3 computer sufficient for the vertical spike. Tier 1, tier 2, creative, servers, racks, and additional devices are added through explicit profile tests in the hardening milestone.

### SimulationController

**Purpose:** Advance the workspace until a bounded condition is satisfied.

```scala
RunRequest(
  condition: StopCondition,
  maxTicks: Int,
  maxWallTime: FiniteDuration,
  pace: TickPace,
  captureOnFailure: Boolean
)
```

Stop conditions include:

- screen contains text
- screen matches a regular expression
- screen region equals expected cells/colors
- machine reaches running, stopped, or crashed state
- filesystem path exists or has expected content
- event predicate occurs
- screen remains unchanged for a tick count

Each run returns the stop reason, elapsed ticks, elapsed wall time, final machine states, and observation revisions. A timeout is a typed result with diagnostics, not an unbounded sleep or generic exception.

### ScreenSnapshot and ScreenRenderer

`ScreenSnapshot` is an immutable copy containing:

```text
logical screen ID
runtime OC address
revision
power state
precision mode
width and height
color depth and palette
Unicode code point per cell
packed foreground/background per cell
capture tick
```

`ScreenRenderer` is a pure headless module:

```scala
ScreenRenderer.render(snapshot, RenderOptions): BufferedImage
```

It parses the OpenComputers `font.hex` resource, renders foreground/background pixels, and requires no AWT window or OpenGL context. Pixel tests assert image dimensions and raster values. PNG encoding is an output adapter around the rendered image.

### ProtocolEndpoint

**Purpose:** Present the core API as versioned JSON-RPC without leaking transport or brain details.

- JSON-RPC version: `2.0`
- protocol major version: `1`
- messages are UTF-8 JSON, one message per line for stream transports
- IDs are strings or integers as permitted by JSON-RPC
- protocol errors and harness domain errors have separate stable codes
- mutating methods return resulting state and observation revision
- large binary artifacts are written beneath the project artifact root and returned by path plus checksum

The dispatcher receives parsed requests and calls `HarnessSession`; transports only move framed messages.

## Project manifest

The canonical project file is `ocelot-harness.conf`, using HOCON. HOCON is selected because ocelot-brain already uses Typesafe Config, it supports comments and includes, and it avoids a second configuration stack. Harness declares Typesafe Config as a direct dependency rather than relying on the brain's transitive declaration.

```hocon
schemaVersion = 1

project {
  id = "gui-demo"
  artifactDirectory = ".ocelot-harness/artifacts"
  snapshotDirectory = ".ocelot-harness/snapshots"
}

runtime {
  tickRate = 20
  internet {
    http = false
    tcp = false
  }
  limits {
    defaultMaxTicks = 1000
    defaultMaxWallTime = 30s
    eventBufferSize = 10000
  }
}

computers {
  main {
    caseTier = 3

    hardware {
      cpu = { tier = 3 }
      memory = [{ tier = 3.5 }, { tier = 3.5 }]
      gpu = { tier = 3 }
      eeprom = { builtin = "lua-bios" }

      disks {
        project {
          kind = "hdd"
          tier = 3
          label = "project"
          source = "./computer"
          access = "read-write"
        }
      }

      cards = [{ kind = "network", tier = 2 }]
    }
  }
}

screens {
  main {
    tier = 3
    keyboard = true
    aspectRatio = [1, 1]
  }
}

connections = [
  { from = "computer:main", to = "screen:main" }
]
```

### Manifest rules

- `schemaVersion` is required and must be supported exactly.
- Logical IDs use `[a-z][a-z0-9-]{0,62}` and are unique within their kind.
- All relative paths resolve from the manifest's canonical parent directory.
- Read-write disk sources remain inside the project root by default.
- Paths outside the project root require an explicit service-level allow-root option; a manifest cannot grant itself broader access.
- Symlinks are resolved before the allowed-root check.
- Internet access is disabled unless both service policy and manifest request permit it.
- Unknown keys are errors for hardware and security-sensitive sections. An `extensions` object is reserved for future forward-compatible metadata.
- Manifest validation reports all independent errors in one response.
- Runtime-generated addresses and state are written under `.ocelot-harness/`, never back into the desired manifest.

## Logical identity

Public IDs are typed and namespaced:

```text
computer:main
screen:main
filesystem:main/project
```

The session maintains a private mapping from logical IDs to brain entities and runtime component addresses. Responses may include the runtime address for debugging, but requests use logical IDs. Snapshots persist the mapping metadata needed to explain restored state.

## User input contract

Public screen coordinates are OpenComputers-style, one-based coordinates. The adapter converts to the zero-based coordinates accepted by `TextBuffer.mouseDown` and related methods.

```text
KeyDown(key, character?)
KeyUp(key, character?)
TypeText(text, interKeyTicks)
Paste(text)
Touch(x, y, button)
Drag(fromX, fromY, toX, toY, button, steps)
Drop(x, y, button)
Scroll(x, y, delta)
```

Rules enforced by the input module:

- keyboard input requires an attached keyboard
- touch/drag/drop/scroll require a touch-capable screen tier
- precision coordinates require supported precision mode
- key names map through one documented OpenComputers scan-code table
- `TypeText` emits paired key-down/key-up events
- `Paste` uses Ocelot's `ClipboardSplitter` and preserves clipboard limits
- the default user is `agent`; callers may select another configured user
- input is linearized with tick advancement

## Screen observation contract

Text observations offer three formats:

| Format | Content | Intended use |
|---|---|---|
| `text` | newline-joined Unicode cells | agent reading and simple assertions |
| `cells` | resolution, palette, code points, foreground/background | exact GUI assertions and rendering |
| `png` | headless raster artifact | visual inspection and image comparison |

Every observation includes a monotonically increasing session-local revision. The revision changes when relevant screen state changes. Clients can request `waitForRevision` rather than poll at an arbitrary frequency.

## Service lifecycle and transports

### Agent-owned stdio mode

```text
ocelot-harnessd serve --stdio --project <path>
```

The owning client starts the process, writes JSON-RPC messages to stdin, reads responses/events from stdout, and terminates the process when finished. Logs go to stderr. This is the simplest and safest integration for a Pi tool or test runner.

### Standalone loopback mode

```text
ocelot-harnessd up --project <path>
ocelotctl ...
ocelot-harnessd down
```

Separate CLI invocations require a persistent attachable transport; stdio cannot provide that. Loopback mode binds an operating-system-assigned port on `127.0.0.1` only and writes connection metadata beneath `.ocelot-harness/run/`. The metadata contains process ID, port, protocol version, project identity, and a randomly generated session token. Every connection authenticates before commands are accepted.

The runtime refuses a second live owner for the same project. Stale metadata is verified against process identity before cleanup.

## Error model

All expected failures are typed:

```text
ManifestInvalid
UnsupportedSchemaVersion
InvalidLogicalId
HardwareProfileViolation
PathOutsideAllowedRoot
EntityNotFound
EntityKindMismatch
MachineStateConflict
InputNotSupported
ConditionTimedOut
OperationCancelled
SnapshotIncompatible
ProtocolVersionMismatch
RuntimeInitializationFailed
InternalBrainFailure
```

Each error contains a stable code, concise message, relevant logical IDs/paths, and optional structured details. Stack traces are logged and included in diagnostic bundles, not placed in normal protocol messages.

## Security and resource policy

- bind network transport to loopback only
- authenticate loopback clients with a random per-run token
- disable Internet Card HTTP/TCP by default
- canonicalize and constrain host filesystem access
- bound event buffers, command queues, ticks, wall time, capture rate, and artifact size
- reject manifest-selected arbitrary JVM classes, reflection targets, or brain entity class names
- never deserialize Java objects from clients
- treat emulated code as untrusted: Ocelot's computer timeout remains enabled
- redact tokens and absolute allow-root paths from ordinary logs where practical
- write snapshots atomically through a temporary file and rename

## Observability and artifacts

Structured logs include:

```text
timestamp
level
project ID
session ID
command/request ID
logical device ID when relevant
simulation tick
message
```

A failure diagnostic bundle contains:

```text
manifest copy with secrets removed
harness and brain versions/commits
runtime configuration summary
machine states and component inventory
event tail
screen text and cell snapshots
screen PNGs
command timeline
error and stack trace
```

Artifact writes return a relative project path, byte size, media type, and SHA-256 checksum.

## Persistence

A harness snapshot contains:

```text
snapshot metadata
├─ harness version and protocol version
├─ manifest schema and digest
├─ ocelot-brain version and commit
├─ logical-to-runtime identity metadata
└─ compressed brain workspace NBT

disk data
└─ remains in configured host-backed directories or is copied by explicit snapshot policy
```

Snapshot loading validates version metadata and reports compatibility failures before replacing the active workspace. Replacement is transactional: construct and validate the restored session first, then swap and dispose the prior session.

## Testing architecture

### Test classes

| Class | Runs against | Purpose |
|---|---|---|
| Unit | pure first-party code | IDs, manifest validation, path policy, hardware profiles, conditions, codec, rasterization |
| Core integration | real ocelot-brain | lifecycle, machine boot, input delivery, screen state, filesystems, snapshots, multi-device behavior |
| Process contract | launched service process | stdio framing, protocol errors, shutdown, logging separation |
| CLI end-to-end | launched service + CLI | user-visible commands and artifact generation |

### Global-state discipline

Core integration tests run in a forked JVM with parallel execution disabled. One suite fixture initializes Ocelot once, creates an isolated temporary workspace per test, cancels subscriptions, closes sessions, waits for background work, and shuts Ocelot down once. Tests use logical IDs and conditions instead of random runtime addresses or fixed sleeps.

### Vertical spike fixture

The first real integration fixture uses:

- one tier-3 computer
- tier-3 CPU, GPU, two memory modules, managed host-backed HDD, and EEPROM
- one tier-3 screen with keyboard
- a small EEPROM bootloader that loads `/main.lua` from the project disk
- a Lua GUI that writes `READY`, reacts to touch, and displays clipboard input

Acceptance sequence:

```text
load manifest
→ start computer
→ run until screen contains READY
→ inject touch and observe TOUCHED
→ paste text and observe the pasted value
→ capture cells and PNG
→ edit host main.lua
→ reset computer and observe the changed marker
→ save, close, restore, and inspect snapshot
```

Every step has tick and wall-clock limits. A failure produces the diagnostic bundle.

## Build and dependency policy

- pin Java baseline, Scala, SBT, plugins, direct libraries, and the brain submodule commit
- use a checked download of the SBT launcher through repository wrapper scripts; do not commit the launcher binary
- declare each used library directly when its first caller is added, even when it is available transitively
- keep ocelot-brain unmodified in Phase 1; carry any necessary patch as a documented commit in a dedicated fork only after approval
- enable Scalafmt and strict first-party compiler warnings without imposing them on the upstream submodule
- create one fat executable JAR from `harness-app`
- expose one canonical verification command used locally and by future CI

Feature library candidates, added only with their first caller:

```text
Typesafe Config 1.4.4
uPickle/uJson 3.3.1
scopt 4.1.0
ScalaTest 3.2.19 (test)
```

The foundation milestone confirmed the toolchain, brain runtime graph, and ScalaTest version, and declares Typesafe Config 1.4.4 directly for restrictive generated brain configuration. Remaining candidate feature versions are rechecked when their corresponding behavior is implemented.

## Module quality assessment

| Criterion | Decision |
|---|---|
| Depth | Core callers issue intention-level operations; lifecycle, threading, brain APIs, slot indexes, and cleanup remain hidden |
| Leverage | One core API serves CLI, Pi tooling, tests, and both transports |
| Locality | Runtime, project validation, simulation, input, and screen behavior each have one invariant owner behind the core boundary |
| Seams | ocelot-brain is a true external dependency; stdio and loopback are two real transport adapters |
| Deletion test | No generic backend interface or one-method wrapper layers are introduced in Phase 1 |
| Test surface | Public core behavior and protocol contracts are primary; pure render/validation algorithms receive focused direct tests |

## Approved baseline

Phase 1 implementation approval confirms:

1. Phase 1 scope and exclusions
2. HOCON manifest shape and path policy
3. Java 8 / Scala 2.13.10 / SBT 1.8.3 baseline
4. two-module source layout
5. ocelot-brain pinned submodule strategy
6. concrete brain implementation without a speculative backend interface
7. stdio plus authenticated loopback transport plan
8. condition-driven simulation semantics
9. vertical spike acceptance sequence
