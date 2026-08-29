# Ocelot Harness Phase 1 implementation plan

- **Status:** Approved — milestones 0 and 1 complete; interactive execution ready
- **Architecture:** [`docs/architecture/phase-1.md`](../architecture/phase-1.md)
- **Dependency:** ocelot-brain `bec1cc6b1e9e588692f753e9c617063c74967fed`

## Objective

Deliver an agent-usable, headless OpenComputers environment that can configure legal hardware, execute software through ocelot-brain, inject user interaction, inspect/render screens, control multiple devices, and expose the behavior through a stable local protocol and CLI.

The first end-to-end proof is:

```text
edit host main.lua
→ build a valid emulated computer and screen
→ boot through a small EEPROM loader
→ wait for READY
→ inject touch and paste
→ observe TOUCHED and pasted text
→ capture exact cells and PNG
→ change main.lua, reset, and observe the new marker
→ save and restore a snapshot
```

## Delivery milestones

| Milestone | Outcome | Status |
|---|---|---|
| 0. Bootstrap/runtime foundation | Reproducible build and sole process-global runtime ownership | Complete |
| 1. Project construction | Validated manifest becomes a legal live topology | Complete |
| 2. Interactive execution | Bounded run/input/observation through the host-backed vertical loop | Ready |
| 3. Artifacts/recovery | Deterministic captures, snapshots, and diagnostics | Planned |
| 4. External control plane | Versioned stdio/loopback RPC, service lifecycle, and CLI | Planned |
| 5. Hardening/release | Multi-device coverage and release evidence | Planned |

Each milestone is one cohesive delivery unit. Its capability sections are test and acceptance checklists, not independent stop/verify/commit cycles.

## Operating rules for every milestone

1. Read the architecture and relevant ADRs before editing.
2. Work one milestone at a time; preserve its stated public boundary.
3. Use test-driven development at meaningful behavior boundaries:
   - add one failing behavior test
   - run it and record the expected failure
   - implement the smallest complete behavior
   - run targeted tests
   - refactor while green
4. Exercise ocelot-brain through real integration tests. Test doubles are limited to true external boundaries such as clocks, artifact sinks, and transports.
5. Use logical IDs in tests. Generated OC addresses are diagnostic data, not expected values.
6. Use bounded condition waits. Fixed sleeps are permitted only inside the low-level tick pacer and process-test polling helper, both with deadlines.
7. Keep all public models free of `totoro.ocelot` types.
8. Keep Internet Card HTTP/TCP disabled unless a test explicitly starts the service with an allow policy.
9. Use targeted tests while implementing capability sections. After the complete milestone diff is reconciled, run the canonical repository verification command once, update documentation, and perform a clean resource-leak check.
10. Stop for design review when implementation requires widening a public interface, changing a manifest/protocol contract, modifying the brain submodule, or weakening path/authentication policy.

## Planned repository layout

```text
build.sbt
project/build.properties
project/plugins.sbt
modules/core/src/main/scala/ocelot/harness/core/
modules/core/src/test/scala/ocelot/harness/core/
modules/app/src/main/scala/ocelot/harness/app/
modules/app/src/test/scala/ocelot/harness/app/
fixtures/vertical-spike/
scripts/sbtw
scripts/sbtw.cmd
scripts/verify
scripts/verify.cmd
lib/ocelot-brain/
```

`harness-core` owns all emulation policy and concurrency. `harness-app` owns protocol, transports, command-line presentation, and process lifecycle.

## Milestone 0 — Bootstrap and runtime foundation

### Reproducible build and dependency proof

- **Status:** Complete
- **Verified profiles:** Windows Temurin Java 8; clean ephemeral Linux Temurin Java 8

#### Deliverables

- multi-project SBT build with `harness-core`, `harness-app`, and pinned brain project reference
- `project/build.properties` pinning SBT 1.8.3
- pinned plugin versions for Scalafmt and assembly
- checked-download SBT wrappers for POSIX and Windows
- `scripts/verify` and `scripts/verify.cmd` as the canonical local verification entrypoints
- direct dependency declarations; no reliance on brain transitive dependencies
- Java 8 compatibility check and clear failure message
- no production behavior beyond a dependency probe

#### Tests first

- wrapper checksum test rejects a changed launcher payload
- build definition test resolves the pinned brain project
- process probe initializes and shuts down ocelot-brain once in a forked JVM

#### Acceptance

```text
fresh clone
→ initialize submodule
→ run scripts/verify(.cmd)
→ download checksum-verified SBT launcher
→ compile both first-party modules
→ run tests
→ initialize and shut down brain successfully
```

Do not proceed if native Lua libraries or dependency downloads cannot be reproduced on Windows and Linux. Record exact resolved dependency versions.

The pinned source build is the authoritative dependency record. The build foundation resolved its exact runtime graph from `lib/ocelot-brain/build.sbt`, including OC-LuaJ `20220907.1`, OC-JNLua `20230530.0`, and OC-JNLua-Natives `20220928.1`; first-party test code directly declares ScalaTest `3.2.19`. No unused future feature dependencies are carried.

### Process runtime ownership

- **Status:** Complete
- **Verified profile:** Windows Temurin Java 8

#### Public behavior

Implement `RuntimeOwner` and session lifecycle models from the architecture.

#### Tests first

- `RuntimeOwnerSpec`: starts Ocelot once
- `RuntimeOwnerSpec`: rejects a second owner in the same process
- `RuntimeOwnerSpec`: closes an active session before global shutdown
- `RuntimeOwnerSpec`: startup failure releases partial resources
- `RuntimeOwnerProcessSpec`: orderly shutdown leaves no non-daemon harness threads

#### Implementation notes

- run integration tests in a forked JVM with test parallelism disabled
- keep the Ocelot singleton behind `RuntimeOwner`
- supply an explicit generated brain config with HTTP/TCP disabled and filesystem buffering disabled
- register one JVM shutdown hook that delegates idempotently to the owner
- separate stdout protocol output from stderr logging from the first executable probe

#### Acceptance

A process can start the brain, open and close one empty workspace, and terminate without hanging.

The runtime foundation added a concrete `RuntimeOwner`, core-owned first-party lifecycle errors/configuration, one-active-session enforcement, a single idempotent JVM shutdown hook, and a generated brain configuration that disables HTTP/TCP and filesystem buffering. The forked process proof requires stdout to contain only ordered lifecycle evidence markers, keeps brain logging on stderr, closes the active session before global shutdown, and reports no live non-daemon harness threads.

## Milestone 1 — Project construction

- **Status:** Complete
- **Verified profile:** Windows Temurin Java 8

### Project manifest, IDs, and host-path policy

- **Status:** Complete

#### Public behavior

Implement:

- `ProjectLoader.load(root)`
- immutable validated project models
- typed logical IDs
- canonical project/artifact/snapshot paths
- service-level allowed roots
- HOCON schema version 1 parsing

#### Tests first

- valid minimum manifest expands defaults
- invalid manifests report multiple independent errors
- unknown hardware/security keys fail
- duplicate and malformed IDs fail
- unsupported schema fails before runtime creation
- relative paths resolve from the manifest directory
- `..` traversal outside the project root fails
- symlink escape fails
- an external path succeeds only when service policy grants its canonical root
- a manifest cannot grant itself a broader root
- Internet access requires both service policy and manifest request

#### Acceptance

Manifest validation is pure, deterministic, and performs no brain construction or artifact writes. Error ordering is stable.

### Legal hardware catalog and workspace construction

- **Status:** Complete

#### Public behavior

Implement the first hardware profile:

- tier-3 case
- tier-3 CPU and GPU
- up to two compatible memory modules
- EEPROM
- managed HDDs
- supported cards needed by the spike
- tier-3 screen and optional keyboard
- explicit computer-to-screen connection

The manifest uses semantic roles. Brain inventory indexes remain private.

#### Tests first

- valid tier-3 profile creates the expected logical inventory description
- missing CPU, memory, GPU, or EEPROM reports a profile violation
- excessive component count fails
- incompatible component tier fails
- singleton roles and bounded collections expose no duplicate or raw slot assignment
- invalid connection endpoint/kind fails
- host-backed disk receives canonical path and requested label
- construction failure disposes all already-created entities
- runtime addresses map back to stable logical IDs

#### Integration acceptance

`HarnessSession.describe()` returns a complete logical topology for one computer and screen. No raw brain class name or numeric slot appears in the public model.

#### Scope boundary

Tier 1, tier 2, creative, servers, racks, and broad addon coverage are added only through new profile tests in the hardening milestone. The initial vertical path must remain small enough to diagnose.

### Milestone acceptance

A versioned manifest resolves only policy-approved canonical paths, constructs one legal tier-3 computer and screen, and exposes a complete logical topology without public brain types, raw inventory indexes, or generated-address identity.

The completed implementation keeps loading pure and deterministic, rejects unsupported schemas before field interpretation, returns stable multi-error validation, restricts includes and host paths to canonical policy-approved roots, and enforces Internet double opt-in. The private catalog owns tier-3 slot legality and partial-construction cleanup; a forked real-brain test proves canonical managed-disk paths and labels, stable logical topology, diagnostic runtime addresses, and reverse-order entity disposal. The packaged lifecycle smoke now constructs and describes a valid project before shutdown.

## Milestone 2 — Interactive execution

- **Status:** Ready

### Serialized simulation, machine state, and events

#### Public behavior

Implement:

- one serialized session command/tick lane
- machine start, stop, and reset
- `RunRequest` with tick and wall-clock limits
- condition evaluation after coherent observation points
- bounded EventBus collection and subscription cleanup
- cancellation

#### Tests first

- concurrent callers are linearized
- machine lifecycle reports typed resulting state
- max-tick expiration returns `ConditionTimedOut`
- wall-clock expiration returns `ConditionTimedOut`
- cancellation terminates a run and leaves the session usable
- event buffer drops oldest events at its configured bound and reports drop count
- close cancels subscriptions and rejects later commands
- a tight accelerated loop still yields to brain worker execution
- stop reason, elapsed ticks, and final revisions are consistent

#### Acceptance

A minimal EEPROM writes a marker to an attached screen, and `run` reaches it without an unbounded sleep. The result states explicitly that tick control is condition-driven, not bit-for-bit deterministic.

### Immutable screen observations and emulated input

#### Public behavior

Implement synchronized `ScreenSnapshot` capture and:

- text and cells formats
- monotonically increasing screen revision
- key down/up
- typed text
- clipboard paste through `ClipboardSplitter`
- touch, drag, drop, and scroll
- one-based public coordinates
- default `agent` user

#### Tests first

- snapshot is immutable after the brain buffer changes
- text preserves Unicode and screen dimensions
- foreground/background and palette data are copied exactly
- revision changes on relevant screen state changes
- key input requires an attached keyboard
- touch input enforces screen tier
- public `(1,1)` becomes brain `(0,0)` and received OC event `(1,1)`
- drag emits ordered touch/drag/drop semantics
- paste preserves Ocelot clipboard splitting and limits
- input and tick operations cannot interleave inconsistently

#### Acceptance

The spike Lua program displays `READY`, then changes to `TOUCHED` after touch and displays pasted text after clipboard input.

### Host-backed development loop vertical spike

#### Fixture

Create `fixtures/vertical-spike/` containing:

```text
ocelot-harness.conf
computer/main.lua
firmware/project-loader.lua
expected/
```

The EEPROM loader locates the filesystem labeled `project`, loads `/main.lua`, reports boot errors on the screen, and executes it. The GUI fixture reacts to touch and clipboard signals.

#### Tests first

- manifest loads and constructs the fixture
- first boot reaches `READY` within explicit budgets
- touch reaches `TOUCHED`
- paste displays its payload
- editing host `main.lua`, resetting, and rerunning displays a changed marker
- two successive tests receive isolated temporary fixture copies
- failure captures screen text, events, machine state, and timeline

#### Acceptance

This vertical-spike capability satisfies the Phase 1 technical go/no-go gate. Stop and review evidence before expanding the interface if the real-brain loop is unreliable.

### Milestone acceptance

A host-file edit can be booted, driven through bounded condition waits, touched and pasted into through emulated input, observed through immutable screen state, reset, and rerun against real ocelot-brain with bounded failure diagnostics.

## Milestone 3 — Artifacts and recovery

- **Status:** Planned

### Headless screen renderer and artifact store

#### Public behavior

Implement:

- OpenComputers `font.hex` parser
- pure cell/color rasterizer
- PNG output
- atomic artifact writes beneath the artifact root
- SHA-256, media type, size, and relative-path metadata
- capture limits

#### Tests first

- known glyph rows render exact pixels
- foreground/background and palette colors render exactly
- wide characters consume the correct cells
- powered-off screen rendering follows the documented capture rule
- output dimensions equal cell resolution times glyph dimensions and scale
- artifact names cannot escape the artifact root
- writes are atomic and checksummed
- golden PNG test is deterministic on Java 8 Windows/Linux

#### Acceptance

The vertical fixture produces text, cells JSON, and a reproducible PNG without creating an AWT window or OpenGL context.

### Snapshots and diagnostic bundles

#### Public behavior

Implement atomic brain workspace snapshots with harness metadata and failure diagnostic bundles.

#### Tests first

- save/restore preserves machine, screen, identity mapping, and in-game time
- metadata records harness, schema, protocol, and brain versions
- incompatible metadata fails before replacing the active session
- corrupt NBT leaves the current session intact
- transactional replacement closes the old session only after successful restoration
- diagnostic manifest redacts service token and policy-sensitive roots
- diagnostic bundle is bounded and includes declared checksums
- host disk snapshot policy is explicit: reference-only by default, copy only by request

#### Acceptance

The vertical fixture saves, closes, restores, and exposes the expected screen and logical topology. Failed restore is non-destructive.

### Milestone acceptance

The vertical fixture produces deterministic text/cell/PNG artifacts and bounded diagnostics, then saves, closes, and transactionally restores its logical topology and observed screen state.

## Milestone 4 — External control plane

- **Status:** Planned

### Versioned JSON-RPC and agent-owned stdio

#### Public behavior

Implement protocol major version 1, transport-neutral dispatch, NDJSON framing, stdio service mode, and artifact references.

Initial methods:

```text
harness.version
workspace.describe
machine.start
machine.stop
machine.reset
simulation.run
screen.read
screen.input
snapshot.save
snapshot.load
diagnostics.collect
service.shutdown
```

#### Tests first

- JSON-RPC success, notification, parse error, invalid request, method not found, and domain error contracts
- protocol major mismatch fails during handshake
- additive unknown response fields are tolerated by the client codec
- request IDs correlate under concurrent reads
- malformed line cannot corrupt the next framed request
- logs never appear on stdout
- EOF closes session and process cleanly
- large artifacts return metadata rather than inline bytes

#### Process acceptance

A test launches the fat JAR with `serve --stdio`, drives the complete vertical fixture over stdin/stdout, validates diagnostics, sends shutdown, and observes exit code 0.

### Authenticated loopback service and CLI

#### Public behavior

Implement:

- loopback-only service binding on an OS-assigned port
- random per-run token authentication
- atomic connection metadata
- project owner lock and stale-owner recovery
- `ocelot-harnessd up/down/status`
- `ocelotctl` commands corresponding to protocol methods
- machine-readable `--json` and concise human output

#### Tests first

- service binds only `127.0.0.1`
- unauthenticated and wrong-token clients receive no command access
- tokens are not logged
- second live owner for the project fails
- stale metadata is removed only after process identity verification
- concurrent CLI commands correlate correctly
- `down` is graceful and bounded, with explicit forced-stop command if needed
- every CLI failure maps to a stable nonzero exit category
- paths containing spaces work on Windows

#### End-to-end acceptance

```text
ocelot-harnessd up --project fixtures/vertical-spike
ocelotctl machine start main
ocelotctl screen wait main --contains READY --max-ticks 1000 --timeout 30s
ocelotctl screen touch main 10 5
ocelotctl screen capture main --format png
ocelot-harnessd down
```

All commands address the same running session.

### Milestone acceptance

Agent-owned stdio and an authenticated loopback service expose the same versioned transport-neutral control contract; the CLI drives one persistent project session without protocol/log stream contamination.

## Milestone 5 — Multi-device coverage and release hardening

- **Status:** Planned

### Scope

Add profiles and tests in value order:

1. multiple computers and screens
2. network cards and relay topology
3. tier 1 and tier 2 computers
4. multiple host disks and read-only disks
5. servers/racks only after their profile contract is separately approved

### Tests first

- two computers retain independent machine/filesystem/screen state
- targeted input reaches only the selected screen path
- network packets follow configured connectivity
- removing/disconnecting a device updates descriptions and behavior safely
- diagnostic bundle captures every relevant screen on a multi-device failure
- resource caps hold under configured maximum device counts

### Release acceptance

- clean-clone verification on Windows Java 8 and Linux Java 8
- generated CLI reference and example project
- SBOM/dependency report
- license notices include brain and retained resources
- no critical dependency vulnerability without an explicit documented disposition
- protocol and manifest compatibility tests pass
- release JAR reports exact harness and brain commits

Hosted CI configuration is added when the repository host is selected. It invokes the same canonical verification scripts used locally.

## Canonical verification target

After the foundation milestone, these commands are the required interfaces:

```text
scripts/verify          # POSIX/Git Bash
scripts\verify.cmd      # Windows Command Prompt/PowerShell
```

They must run, in order:

```text
submodule pin check
→ formatting check
→ compile with strict first-party warnings
→ unit tests
→ real-brain integration tests in isolated fork
→ process/protocol tests
→ assembly
→ packaged vertical smoke test
```

A targeted developer command may run less, but no milestone is complete until the canonical verification target passes from a clean state.

## Review gates

Stop and request maintainer review at these points:

1. before foundation implementation
2. after the foundation milestone if dependency/native setup differs from this plan
3. after the interactive-execution vertical go/no-go evidence
4. before publishing protocol version 1
5. before enabling Internet Card access or external host roots
6. before modifying or forking ocelot-brain
7. before adding Ocelot Desktop integration

## Phase 1 definition of done

Phase 1 is complete when:

- a fresh clone bootstraps reproducibly on the supported Java 8 platforms
- one manifest creates legal single- and multi-device environments
- the host edit/run/interact/observe loop passes against real ocelot-brain
- all waits and resources are bounded
- text, cells, PNGs, events, snapshots, and diagnostics are available
- stdio agent control and persistent CLI control share one versioned protocol
- security defaults restrict network and filesystem access
- all public contracts and user workflows are documented
- canonical clean-state verification passes
