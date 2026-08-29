# Workspace and runtime expansion plan

- **Status:** Approved
- **Baseline:** Ocelot Harness `bf4ec34`, ocelot-brain `bec1cc6`
- **Compatibility target:** Ocelot Desktop `586d6ef` workspace format using the same brain commit
- **Execution:** three cohesive stages, each reconciled before one canonical verification and one commit

## Objective

Expand Ocelot Harness from its Phase 1 computer/screen subset into a practical workspace control plane:

```text
existing Ocelot Desktop workspace
or deterministic Harness manifest
→ preserved brain graph with stable logical IDs
→ controllable computers and observable screens
→ daemon-owned continuous simulation clock
→ concurrent agent control and live human viewing
```

The required user outcome is two or more computers and screens remaining alive and continuously updating while an agent edits, resets, observes, and interacts and a user watches one or more viewer windows.

## Operating contract

1. Keep `RuntimeOwner` as the sole owner of brain initialization and shutdown.
2. Keep one active project and one serialized mutation/tick lane per service process.
3. Preserve logical IDs at every public boundary; source addresses, entity UUIDs, inventory slots, and brain classes remain private adapter data.
4. Preserve imported brain entities and network edges rather than translating a Desktop workspace through the manifest hardware subset.
5. Validate compressed input, classes, graph size, files, symlinks, disk bindings, and paths before replacing active state.
6. Keep network listeners authenticated and loopback-only. Internet access retains service-and-project double opt-in.
7. Keep the pinned submodule unchanged.
8. Write contract-level tests first, use targeted affected suites during implementation, reconcile the complete stage, then run canonical verification once. Rerun only when later source or test changes invalidate that evidence.
9. Commit each verified stage independently.
10. Stop only when the pinned formats contradict this plan, safe import would require executing unavailable Desktop-only classes, a security bound must be removed, or a public choice is not covered here.

## Module design

### Workspace source boundary

`harness-core` gains one private workspace-source boundary with two concrete adapters:

```text
validated project
→ WorkspaceSourceLoader
   ├─ ManifestWorkspaceSource
   └─ DesktopWorkspaceSource
→ LoadedWorkspace
   ├─ complete brain Workspace
   ├─ logical computer/screen/keyboard bindings
   ├─ source identity and digest
   └─ cleanup ownership
→ existing brain-free HarnessSession
```

This is a source-format seam, not an emulator-backend interface. Both adapters produce the same pinned brain `Workspace`; callers do not coordinate load order, path rebinding, identity discovery, or cleanup.

`HardwareCatalog` remains the private manifest-construction implementation. Desktop import loads the saved `back` graph directly. Source-specific snapshot validation remains behind the same private boundary.

### Public project contract

Schema v2 introduces an explicit workspace source:

```hocon
schemaVersion = 2
project { id = "example" }

workspace {
  kind = "manifest"       # deterministic devices declared below
  # kind = "desktop"      # imported source under the project root
  # directory = "desktop"
}
```

Schema v1 remains readable with its current behavior. Schema v2 is used for new/imported projects. Unknown and security-sensitive fields remain errors.

Local project-authoring commands execute without requiring a running daemon:

```text
ocelotctl project inspect-desktop <desktop-directory> [--json]
ocelotctl project import-desktop <desktop-directory> <project-directory> [--json]
ocelotctl --project <project-directory> project validate [--json]
ocelotctl project init <project-directory> [--template <name>] [--json]
```

Import copies a bounded source into the destination project, emits a v2 manifest and private source-identity metadata, and leaves the original Desktop directory unchanged.

### Stable identity

Desktop import derives initial human-readable IDs from normalized unique frontend labels when available and deterministic kind ordinals otherwise. It stores the binding from the brain entity UUID to logical ID in private import metadata. Reloads and Harness snapshots reuse that mapping. Runtime component addresses are never required by callers.

The importer discovers every brain `Case` as a controllable computer and every brain `Screen` as an observable screen. Associated keyboards are resolved from the graph and Desktop frontend metadata. Other entities and edges remain in the workspace even when they have no specialized Harness command.

### Import compatibility and security

A Desktop workspace directory contains `workspace.nbt`; its root compound contains:

```text
back  → complete brain Workspace NBT
front → Desktop nodes, labels, layout, cables, and window metadata
```

Import consumes `back` for runtime state and reads only identity hints from `front`. Desktop presentation state is retained in the copied source but is not interpreted as Harness UI state.

Before mutation, import enforces:

- compressed and decompressed byte ceilings using `NBTReadLimiter`
- bounded recursive source copy with file-count and total-byte limits
- no followed symlinks, junction escapes, or special files
- required `back`/`front` compounds and bounded entity/edge counts
- preflight of serialized entity class names against constructors available from the pinned runtime
- deterministic duplicate-label handling
- managed-disk rebinding to copied project-contained directories
- rejection of unresolved or policy-external paths
- transactional candidate load and cleanup on every failure

Desktop-only addon classes absent from the pinned brain classpath are reported during preflight without partially loading the workspace. Brain-backed entities and graph edges are preserved regardless of whether manifest v2 can construct them.

### Continuous clock ownership

`BrainSession` owns one private simulation clock coordinated with the existing command lane:

```text
monotonic target deadline
→ submit one serialized workspace tick
→ sample elapsed time and overruns
→ schedule from the next valid deadline
→ skip missed deadlines rather than enqueue catch-up ticks
```

Public clock state is brain-free:

```text
state: paused | running
configured TPS
measured TPS over a bounded rolling window
total ticks
overrun count
last tick duration
```

The session clock is the only continuous tick producer. Snapshot, restore, close, and workspace replacement use a clock barrier. Bounded runs and tick-advancing input coordinate with the clock so two paths never advance the workspace concurrently or silently double the requested rate.

Schema v2 runtime configuration is explicit:

```hocon
runtime {
  tickRate = 20
  clock.autoStart = true
}
```

Schema v1 keeps its existing non-continuous behavior. Imported and newly initialized v2 projects default to `20 TPS` with auto-start enabled.

Control commands:

```text
ocelotctl simulation start [--tps <1..1000>]
ocelotctl simulation pause
ocelotctl simulation resume
ocelotctl simulation step [count]
ocelotctl simulation rate <1..1000>
ocelotctl simulation status [--json]
```

The viewer displays clock state, target TPS, measured TPS, and overruns. Viewer polling observes state and never owns simulation time.

## Stage 1 — Desktop workspace import

### Tests first

1. Parse a bounded Desktop-shaped `workspace.nbt` and reject missing/wrong root tags.
2. Reject oversized compressed/decompressed input, entity/edge counts, unavailable classes, path escapes, symlinks, and duplicate/invalid identity metadata before active-state mutation.
3. Import a real-brain fixture containing multiple computers, screens, keyboards, managed disks, and an additional supported brain entity; prove all entities and edges survive load and snapshot round-trip.
4. Generate deterministic logical IDs from labels/ordinals and preserve them across reopen and snapshot restore.
5. Copy/rebind managed disk data beneath the destination project without touching the source workspace.
6. Prove a failed candidate load leaves an active session unchanged and releases all candidate resources.
7. Prove local inspect/import/validate commands and stable JSON/error exits.
8. Fork a process that imports, starts computers, reads screens, sends targeted input, saves/restores state, and shuts down with zero live Harness threads.

### Implementation

- Add schema-v2 workspace-source envelope while preserving schema v1.
- Add private `WorkspaceSourceLoader`, manifest adapter, Desktop adapter, import metadata, and bounded copier.
- Generalize constructed topology binding and snapshot validation to source-owned identity rules.
- Add local project inspect/import/validate commands and documentation.
- Add a checked brain-only Desktop-format fixture generated by tests; do not add Ocelot Desktop as a runtime dependency.

### Acceptance

```text
copy an existing compatible Desktop workspace
→ inspect compatibility without mutation
→ import into a new Harness project
→ launch daemon
→ control every discovered computer
→ view every discovered screen
→ preserve additional brain entities and graph edges
→ snapshot/restore
→ original Desktop directory unchanged
```

## Stage 2 — Manifest v2 and project authoring

### Supported deterministic topology

Manifest v2 covers the pinned Ocelot Desktop original-node set that can be constructed from ocelot-brain without Desktop classes:

- computer cases and legal inventories
- screens and keyboards
- racks and servers
- disk drives and floppies
- RAID arrays
- hologram projectors
- note blocks
- microcontrollers
- relays and cables

Inventory coverage includes legal pinned-brain CPU/APU, memory, GPU, EEPROM, component bus, managed/unmanaged disk, network/wireless, linked, data, redstone, and Internet-card profiles where supported. Internet hardware remains disabled unless both policies opt in.

Connections use typed device/port references and validate direction, tier, slot, cardinal-side, multiplicity, and service caps before constructing any entity. Addon entities implemented only by Ocelot Desktop remain import-compatibility findings rather than manifest constructors.

### Tests first

1. Table-drive every supported kind, tier, option, slot, and invalid combination.
2. Validate mixed topologies and arbitrary legal graph edges without exposing raw brain slots/classes.
3. Construct and operate representative case, rack/server, microcontroller, RAID, relay, and peripheral networks against real brain.
4. Prove deterministic validation order, schema-v1 compatibility, strict unknown-key rejection, and topology/resource caps.
5. Prove `project init` templates validate and run without manual repair.
6. Prove manifest and Desktop source adapters return the same public session behavior for common computers/screens.

### Implementation

- Replace v1-specific hardware records with private v2 device definitions behind a compact public validated-project model.
- Split the private construction catalog by cohesive device/inventory responsibilities while retaining one topology invariant owner.
- Add project templates for single computer, two computers, rack/server, and mixed network.
- Generate schema and CLI reference tables from the owning catalog where practical.

### Acceptance

```text
ocelotctl project init demo --template mixed-network
→ edit reviewable HOCON
→ validate complete topology
→ daemon constructs it transactionally
→ computers/screens and preserved auxiliary devices operate through one session
```

## Stage 3 — Continuous TPS clock and viewer integration

### Tests first

1. Use a fake monotonic clock around pure deadline calculation to prove 20 TPS scheduling, rate changes, pause/resume, bounded step, and no catch-up storm.
2. Prove exactly one tick producer under concurrent CLI, viewer, input, bounded run, snapshot, and shutdown operations.
3. Prove measured TPS/overruns are bounded and monotonic counters remain correct under slow ticks.
4. Prove schema-v1 compatibility and schema-v2 auto-start behavior.
5. Prove JSON-RPC/CLI clock commands, bounds, stable errors, and lossless counters.
6. Run a real-daemon headed acceptance with two continuously changing screens, two viewer processes, concurrent agent edits/resets/input, a live rate change, pause/step/resume, snapshot, and clean shutdown.

### Implementation

- Add the private clock owner and deadline scheduler around the existing serialized lane.
- Wire v2 `runtime.tickRate` and `clock.autoStart` into session startup.
- Coordinate bounded runs, input tick requirements, snapshot/restore, workspace replacement, and close through the clock owner.
- Add protocol, CLI, viewer status/control, diagnostics, and reference documentation.
- Report target and measured TPS without promising real-time guarantees when host load prevents them.

### Acceptance

```text
daemon opens v2 project at 20 TPS
→ two machines continue running without simulation-run commands
→ two viewer windows update concurrently
→ agent edits/resets/interacts through CLI
→ rate changes to 100 TPS
→ pause freezes ingame time
→ step advances exact requested ticks
→ resume continues without burst catch-up
→ shutdown leaves no Harness threads or state files
```

## Verification and commits

For each stage:

```text
targeted red/green tests
→ affected module suite
→ complete diff and contract reconciliation
→ documentation/reference regeneration
→ one canonical scripts/verify run
→ lightweight diff/submodule/temp-resource checks
→ one stage commit
```

After Stage 3, run the canonical Windows Java 8 verification and a clean ephemeral Linux Java 8 verification. Headed viewer acceptance is required on Windows; daemon and non-display viewer tests remain headless on Linux.

## Completion evidence

The final report includes:

- imported Desktop fixture entity/edge preservation counts
- manifest-v2 supported device/inventory table
- exact project-authoring and clock commands
- target/measured TPS and overrun evidence
- two-computer/two-viewer concurrent acceptance evidence
- test counts and Windows/Linux commands
- public schema/protocol compatibility impact
- dependencies and abstractions added
- cleanup, Git, and submodule status
- unsupported Desktop-only addon classes or platform profiles
