# Initial Ocelot Desktop agent-automation feasibility study

> **Historical record:** This study predates the implementation. Its upstream analysis and design rationale remain useful, but its gap list, proposed command surface, and phased roadmap do not describe current Ocelot Harness behavior. Use the [documentation index](../README.md) for current guides and references.

- **Assessment:** viable, with a small Ocelot integration layer
- **Recommended direction:** a long-lived local automation service plus a general CLI; Pi-specific tools should be a thin adapter over the same service
- **Assessed source:** Ocelot Desktop `develop` at [`586d6ef`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/commit/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977), with ocelot-brain at [`bec1cc6`](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/commit/bec1cc6b1e9e588692f753e9c617063c74967fed)

## Executive conclusion

Ocelot is a strong base for agent-driven OpenComputers development because the OpenComputers runtime is already separated from Minecraft in **ocelot-brain**. The library supports constructing machines, advancing simulation ticks, controlling computers, injecting screen/keyboard/mouse events, reading exact screen state, binding virtual disks to host directories, and saving/loading emulation state.

The missing piece is not emulation. It is an **external automation boundary**: Ocelot Desktop currently has no headless mode, control API, or useful command set beyond selecting config/workspace paths. Its workspace is a directory containing a compressed binary `workspace.nbt` and disk data, not a live-editable declarative project file.

```text
agent
→ general CLI or Pi tool
→ local RPC service (long-lived process)
→ Ocelot automation model
   ├─ ocelot-brain: machines, disks, ticks, input, screen state
   └─ Desktop adapter: node layout, windows, exact UI screenshots
→ structured observations + PNG/video artifacts
→ agent revises files and repeats
```

### Viability by capability

| Capability | Existing foundation | Work needed | Verdict |
|---|---|---:|---|
| Run real OC code without Minecraft | ocelot-brain is OpenComputers logic detached from Minecraft | Package a supported runner | **High** |
| Edit project files from the host | Managed disks can point at arbitrary host directories; unbuffered mode reads/writes host files directly | Bind paths declaratively and define safe roots | **High** |
| Start/stop/reset computers | Direct machine methods already exist | Expose commands and stable IDs | **High** |
| Send keys, paste, click, drag, scroll | `TextBuffer` already exposes the same methods Desktop calls | Expose them through RPC | **High** |
| Read screens for assertions | Exact character and packed-color matrices already exist | Serialize as text/JSON; add synchronization | **High** |
| Capture an individual screen as PNG | Desktop renders the same cell/color data; whole-window screenshot code exists | Add a screen renderer/crop endpoint | **High** |
| Record an individual screen | Screen mutations already emit events | Add frame/event recording and video encoding | **Medium–high** |
| Configure machines and connections | Brain entities/inventories/networks and Desktop node classes are programmatic | Add a declarative schema and validation | **High** |
| Position nodes and manage Desktop windows | Positions, open state, focus, pinning, scale, and bounds are internal mutable state | Add Desktop-side commands on its UI thread | **Medium–high** |
| Mutate `workspace.nbt` while Desktop is running | None; Desktop only loads it explicitly | Do not use this as the live-control mechanism | **Poor approach** |
| Deterministic automated tests | Tick advancement is explicit in brain | Add pause/step/run-until and condition waits | **High** |

## What Ocelot already provides

### 1. A real embeddable OpenComputers runtime

The [ocelot-brain primer](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/blob/bec1cc6b1e9e588692f753e9c617063c74967fed/doc/ocelot-brain.md) describes a `Workspace` as the machine/entity container and says its `update()` method advances the simulation. The included [`Demo.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/blob/bec1cc6b1e9e588692f753e9c617063c74967fed/src/main/scala/totoro/ocelot/demo/Demo.scala) constructs a case, CPU, GPU, memory, HDD, OpenOS floppy, EEPROM, and screen; connects them; starts the machine; advances ticks; and saves/restores a snapshot.

This is almost exactly the required testing substrate. A headless runner does **not** need to automate the Desktop GUI to execute OC software accurately.

### 2. Host-directory-backed disks

[`DiskRealPathAware.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/blob/bec1cc6b1e9e588692f753e9c617063c74967fed/src/main/scala/totoro/ocelot/brain/entity/traits/DiskRealPathAware.scala) supports a `customRealPath` pointing at any host directory. Ocelot Desktop already exposes this as **Set directory** in [`DiskItem.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/inventory/traits/DiskItem.scala).

The brain configuration currently defaults `filesystem.bufferChanges` to `false`. With that setting, [`FileSystemAPI.fromDirectory`](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/blob/bec1cc6b1e9e588692f753e9c617063c74967fed/src/main/scala/totoro/ocelot/brain/entity/fs/FileSystemAPI.scala) uses a direct read/write filesystem rather than an in-memory buffered copy. Therefore:

```text
agent writes host file
→ OC filesystem reads that same file
→ no workspace save/reload is required
```

Normal program behavior still applies: a running OC process may retain already-read code or an open file handle, so the harness may need to restart the program/computer or trigger an application-specific reload.

### 3. Exact machine input

[`TextBuffer.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/blob/bec1cc6b1e9e588692f753e9c617063c74967fed/src/main/scala/totoro/ocelot/brain/entity/TextBuffer.scala) provides:

- `keyDown` / `keyUp`
- `clipboard`
- `mouseDown` / `mouseDrag` / `mouseUp`
- `mouseScroll`

Ocelot Desktop's [`ScreenView.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/ui/widget/ScreenView.scala) simply translates physical UI events into these calls. The harness can call the same methods directly, preserving the signals an OpenComputers program receives without unreliable operating-system mouse automation.

### 4. Better-than-screenshot screen inspection

[`GenericTextBuffer.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-brain/-/blob/bec1cc6b1e9e588692f753e9c617063c74967fed/src/main/scala/totoro/ocelot/brain/util/GenericTextBuffer.scala) holds:

- Unicode code points in a 2-D cell array
- packed foreground/background colors per cell
- current width, height, palette, and color depth
- conversion to individual lines or the full screen as text

For agents and tests, structured screen data should be the primary observation:

```json
{
  "screen": "main",
  "resolution": [80, 25],
  "powered": true,
  "cursor": null,
  "lines": ["OpenOS 1.7.5 ...", "..."],
  "cells": "optional color/cell payload",
  "revision": 142
}
```

This avoids OCR, uses fewer tokens, and supports exact assertions. PNG remains important for validating GUI appearance. Both forms should be generated from one synchronized snapshot of the screen buffer.

### 5. Rendering and screenshots

Desktop's [`Graphics.screenshot()`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/graphics/Graphics.scala) reads the OpenGL framebuffer into a `BufferedImage`. [`UiHandler.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/ui/UiHandler.scala) connects that to F12 and a save-directory chooser.

This proves whole-Desktop capture is implemented, but it is not automation-ready: it requires a keybinding and chooser, and there is no individual-screen endpoint. Individual screens are rendered from copied text/color matrices in [`ScreenNode.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/node/nodes/ScreenNode.scala), so either of these is feasible:

1. **Headless screen PNG:** CPU-render the cell/color matrix using Ocelot's font assets. Best for CI.
2. **Exact Desktop crop:** render/capture the existing `ScreenViewport` on the OpenGL/UI thread. Best for pixel fidelity with Desktop.

Recording should initially store timestamped PNG frames or, more efficiently, screen-change events plus periodic keyframes. MP4/WebM can be an export step through FFmpeg rather than part of the emulator core.

### 6. Persisted visual workspace state

Desktop already persists the relevant visual properties:

- node positions in [`Node.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/node/Node.scala)
- node classes and connections in [`WorkspaceView.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/ui/widget/WorkspaceView.scala)
- window position, size, focus, pinning, and state in [`Window.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/ui/widget/window/Window.scala)
- screen window scale in [`ScreenWindow.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/windows/ScreenWindow.scala)

The data model is therefore capable; only an external control surface is missing.

## Current gaps and constraints

### No external control API

The existing [`CommandLine.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/util/CommandLine.scala) supports only:

```text
--help
--config <path>
--workspace <path>
```

There is no server, socket, RPC interface, headless flag, command execution, or programmatic screenshot endpoint. Upstream issue [#86, “Ocelot Desktop interface component”](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/issues/86), explicitly proposes listing computers/components, reading disks, moving windows, and saving/loading workspaces. It remains open and is marked complex.

A smoke test of the v1.14.2 release JAR confirmed the three options above. It also found that `java -jar ... --help` printed help but did not terminate within 10 seconds, apparently because Desktop/AWT state is initialized before command handling. This is minor for human use but reinforces that a purpose-built automation entrypoint is needed.

### The workspace is not a safe live-edit control plane

[`OcelotDesktop.scala`](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/src/main/scala/ocelot/desktop/OcelotDesktop.scala) saves a workspace directory as:

```text
workspace-directory/
├─ workspace.nbt       # compressed binary backend + Desktop frontend state
├─ workspace.nbt.bak
└─ <disk-address>/...  # default managed-disk contents
```

Desktop reads `workspace.nbt` only during an explicit load. Editing or replacing it while the process runs will not live-update the in-memory model and risks races with autosave. A human-editable project manifest should be introduced above NBT; the adapter should apply manifest changes through Ocelot's objects and then let Ocelot own NBT persistence.

### Threading must be respected

Desktop has a simulation update thread and an OpenGL/UI thread, protected in places by a tick lock and task queues. The automation server must not mutate arbitrary objects on network-handler threads.

```text
RPC request
→ validate and resolve IDs
→ enqueue simulation mutation or UI mutation
→ execute under the appropriate lock/thread
→ return result after completion
```

- machine/entity/network changes: simulation queue / tick lock
- windows, widget state, OpenGL captures: UI thread
- screen reads: synchronize on the screen, as `ScreenNode` already does

### Desktop is graphical, brain is headless

Exact Desktop windows and workspace screenshots require an OpenGL-capable graphical session. CI and agent testing should not depend on that. Separate the capabilities:

| Mode | Purpose | Requires display/GPU |
|---|---|---:|
| `headless` | OC execution, files, deterministic input, text/cell captures, CPU-rendered screen PNG | No |
| `desktop` | Visual workspace layout, Desktop windows, whole-UI screenshots, pixel checks | Yes |

### Test coverage around Desktop internals is small

At the assessed commit, Desktop has 321 main Scala source files and only 2 test source files. Any invasive Desktop fork needs focused tests around RPC serialization, ID resolution, task dispatch, persistence, and screen capture. Keep the integration layer narrow to reduce regression risk.

## Interface options compared

Scores: 5 is best.

| Approach | Reliable | Headless | Full Desktop control | Reusable | Build cost | Recommendation |
|---|---:|---:|---:|---:|---:|---|
| OS-level GUI automation | 2 | 1 | 3 | 2 | 4 | Only as a final end-to-end smoke test |
| Edit `workspace.nbt` directly | 1 | 3 | 2 | 2 | 2 | Reject as live interface |
| One-shot CLI directly manipulating saves | 2 | 4 | 1 | 4 | 3 | Useful only for offline conversion/inspection |
| Headless service over ocelot-brain | 5 | 5 | 1 | 5 | 3 | Core automated runtime |
| In-process Desktop RPC adapter | 5 | 1 | 5 | 5 | 2 | Add for exact visual workspace control |
| Pi-only custom tool | 4 | depends | depends | 1 | 4 | Thin wrapper only, never the core |
| General CLI over local RPC | 5 | 5 | 5 | 5 | 4 | Primary user/agent interface |

The best solution is not “CLI versus Pi tool.” It is:

```text
one stable local protocol
├─ general CLI client
├─ Pi tool adapter
├─ test framework client
└─ future editor/GUI client
```

## Recommended architecture

### Components

```text
ocelot-harnessd               long-lived process; owns emulation state
├─ automation-domain          stable IDs, commands, observations, errors
├─ manifest-loader            human-editable machine/workspace topology
├─ brain-adapter              headless execution and condition-driven testing
├─ desktop-adapter            optional visual layout/window control
├─ capture                    text, cells, PNG, recording
└─ local JSON-RPC transport   agent-owned stdio or authenticated loopback

ocelotctl                     general CLI client
pi-ocelot-tools               thin Pi schemas calling the same RPC methods
```

A long-lived service is required because machine state evolves between commands. A CLI process should be a client, not recreate the emulator for every action.

### Protocol choice

Use transport-neutral **JSON-RPC 2.0** dispatch with two local transports:

- agent-owned stdio for simple subprocess lifecycle, no port allocation, and direct Pi/test integration
- authenticated loopback for separate `ocelotctl` invocations that attach to one persistent session

Keep framing, authentication, and connection lifecycle outside the emulation core.

### Human-editable project manifest

Do not expose Ocelot class names or raw NBT as the project format. Use a versioned HOCON manifest with logical IDs:

```hocon
schemaVersion = 1
project.id = "gui-demo"

computers.main {
  caseTier = 3
  hardware {
    cpu = { tier = 3 }
    memory = [{ tier = 3.5 }]
    gpu = { tier = 3 }
    eeprom = { builtin = "lua-bios" }
    disks.project {
      kind = "hdd"
      tier = 3
      source = "./computer"
    }
  }
}

screens.main {
  tier = 3
  keyboard = true
}

connections = [
  { from = "computer:main", to = "screen:main" }
]
```

The manifest is desired configuration. Ocelot's workspace/NBT remains the runtime snapshot. The first implementation can apply a manifest only at creation/load; live reconciliation can come later.

### Minimum command surface

```text
ocelot-harnessd up --project <dir>
ocelot-harnessd status

ocelotctl workspace describe
ocelotctl snapshot save <name>
ocelotctl snapshot load <name>

ocelotctl machine list
ocelotctl machine start main
ocelotctl machine stop main
ocelotctl machine reset main

ocelotctl screen list
ocelotctl screen read main --format text|cells
ocelotctl screen capture main --output screen.png
ocelotctl screen type main "edit /home/app.lua"
ocelotctl screen key main ENTER
ocelotctl screen touch main 12 8 --button left
ocelotctl screen scroll main 12 8 -1
ocelotctl screen wait main --contains "Ready" --max-ticks 400 --timeout 30s

ocelotctl simulation run --max-ticks 200 --timeout 30s
ocelotctl events tail
ocelotctl diagnostics collect

ocelot-harnessd down
```

### Stable object identity

Random OpenComputers component addresses are runtime details. Agent commands should target logical manifest IDs (`machine:main`, `screen:main`) and return both logical and OC addresses when useful. Persist the logical-to-runtime mapping in harness metadata, not by changing upstream Ocelot addresses.

### Deterministic test loop

Wall-clock sleeps make agent tests slow and flaky. The service should support paused/stepped simulation:

```text
write files
→ apply/reload
→ start machine
→ advance one or more OC ticks
→ evaluate structured condition
→ stop on success, failure, crash, or max tick budget
→ capture text + cells + PNG + event log
```

Useful conditions:

- screen contains/matches text
- screen region equals expected cells/colors
- machine stopped/crashed/running
- filesystem path exists or content matches
- event occurred
- no screen change for N ticks

## Recommended phased implementation

### Phase 0 — Technical spike

Build the smallest headless program directly on ocelot-brain:

1. create one computer, managed host-backed HDD, OpenOS boot media, GPU, keyboard, and screen
2. boot it by advancing ticks
3. inject keyboard/clipboard input
4. dump screen text and colors
5. render an individual screen PNG without OpenGL
6. modify a host-backed Lua file and demonstrate the reload/restart loop
7. save and reload a snapshot

**Go/no-go gate:** one automated script can edit a Lua program, run it in Ocelot, interact with it, and assert its screen without Minecraft or manual input.

### Phase 1 — Headless service and CLI

- introduce the versioned manifest
- implement process lifecycle and JSON-RPC over stdio
- add logical IDs and structured errors
- add machine, input, screen read/capture, tick/run-until, filesystem, and snapshot commands
- emit event logs and a single diagnostic bundle on failure

This phase already delivers most coding and testing value.

### Phase 2 — Desktop adapter

Patch/fork Ocelot Desktop narrowly:

- start the same automation server in Desktop mode
- route simulation commands through the update task queue/tick lock
- route window/layout/capture commands through the UI task queue
- expose node add/remove/move/connect and window open/close/move/resize/focus
- expose whole-workspace and exact individual-screen captures
- disable or coordinate autosave during externally managed operations

### Phase 3 — Recording and agent ergonomics

- screen-delta subscription
- event/keyframe recording and FFmpeg export
- artifact directories and reproducible test reports
- Pi tools generated as thin RPC wrappers
- reusable agent actions such as `boot_and_wait`, `type_command`, `assert_screen`, and `collect_failure_bundle`

### Phase 4 — Declarative reconciliation, only if needed

Live-apply manifest topology changes to a running workspace with explicit replace/update rules. This is significantly harder than applying a manifest at startup and should not block the useful first version.

## Risks and mitigations

| Risk | Effect | Mitigation |
|---|---|---|
| Upstream internal APIs are not stable | Adapter breaks after updates | Pin commits; keep a narrow adapter; add contract tests |
| Concurrent Desktop/UI mutation | races or OpenGL failures | task queues, tick lock, UI-thread-only capture |
| Raw NBT/class-name persistence is implementation-coupled | save incompatibility | versioned manifest + Ocelot-owned NBT snapshots |
| Host directory grants broad file access | agent can expose/alter unintended files | allowed-root policy, canonical-path checks, read-only mounts |
| Visual capture differs between GPU/Desktop and headless renderer | pixel assertions diverge | label capture type; reserve exact Desktop tests for graphical mode |
| Video creates large artifacts | slow runs and excess storage | record deltas/keyframes; encode on demand; retention limits |
| Busy loops or unbounded simulation | hung agent runs | per-command tick/time budgets and cancellation |
| Sparse Desktop test suite | regressions in fork | tests around adapter boundary; avoid rewriting UI internals |

## Project and licensing status

- Ocelot Desktop and ocelot-brain use the permissive [MIT license](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/blob/586d6ef5bd2e5f11de9c2c0064ecda906ceeb977/LICENSE); retained OpenComputers code also carries a permissive notice in ocelot-brain.
- The repository is active enough to remain a practical dependency: the assessed `develop` commit is newer than stable release [v1.14.2](https://gitlab.com/cc-ru/ocelot/ocelot-desktop/-/releases/v1.14.2).
- Desktop uses Scala 2.13, SBT, Java 8, and LWJGL 2.9.3. The local machine has Java 8 but not SBT, so source compilation was not performed during this assessment.

## Final recommendation

Proceed with Ocelot, but treat **ocelot-brain as the automation engine** and **Ocelot Desktop as an optional visual frontend**.

```text
First prove:
headless edit → boot → interact → inspect → assert

Then add:
Desktop layout/window control → exact visual capture

Expose both through:
one local RPC protocol → general CLI → thin Pi tools
```

Do not begin by scripting the operating-system GUI or directly rewriting `workspace.nbt`. Both bypass the strong programmatic model Ocelot already provides and would produce a much more fragile system.
