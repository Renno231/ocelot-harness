# Ocelot Harness

Ocelot Harness is a development, testing, and automation harness for OpenComputers software powered by the real [ocelot-brain](https://gitlab.com/cc-ru/ocelot/ocelot-brain) runtime. Its daemon runs without Minecraft or Ocelot Desktop, while an optional live viewer lets people watch and control emulated screens.

```text
project manifest + host files
→ emulated computers, screens, disks, and networks
→ key/paste/touch/drag/drop/scroll input
→ screen text, exact cells/colors, PNGs, events, and test artifacts
```

## Status

Phase 1 and the workspace/runtime expansion are implemented. The harness provides reproducible Java 8/SBT builds, sole process-global lifecycle ownership, compatible Ocelot Desktop import, deterministic schema-v2 workspace authoring, serialized interactive execution, daemon-owned continuous simulation time, deterministic artifacts and recovery, versioned external control, live viewing, multi-device coverage, and release evidence on Windows and Linux.

Start here:

1. [Create a deterministic project](#create-a-deterministic-project)
2. [Import an Ocelot Desktop workspace](docs/reference/desktop-import.md)
3. [Use the CLI](docs/reference/cli.md)
4. [Open the live viewer](docs/reference/viewer.md)
5. [Define schema-v2 hardware and topology](docs/reference/manifest-v2.md)
6. [Browse all documentation](docs/README.md)
7. [Understand the architecture](docs/architecture/phase-1.md)
8. [Contribute](CONTRIBUTING.md)

The documentation index separates current user guides from historical design and implementation records.

## Related project: OC Robot Accelerator

[OC Robot Accelerator](https://github.com/Renno231/oc-robot-accelerator) runs real OpenComputers robot programs in accelerated Minecraft 1.12.2 / Forge worlds, with configurable native hardware, bounded jobs and offline sampled replay. It is an independent project with its own runtime, build and releases; neither project depends on the other. The local sibling checkout is `../oc-robot-accelerator/`.

## Implemented capabilities

- versioned HOCON project manifests with stable logical device IDs
- bounded import of compatible Ocelot Desktop workspaces with complete brain-graph preservation
- deterministic label/UUID identity binding across reopen and snapshot restore
- schema-v2 construction of cases, screens/keyboards, racks/servers, disk drives/floppies, RAID, holograms, note blocks, microcontrollers, relays, and cables
- semantic legal inventories covering processors, memory, graphics, EEPROMs, buses, storage, and supported cards
- typed device/port topology with service-owned device, inventory, connection, computer, screen, and storage caps
- local runnable project templates for single-computer, two-computer, rack/server, and mixed-network environments
- host-directory-backed managed disks with canonical allowed-root policy
- machine start, stop, reset, condition-driven bounded runs, and continuous configurable 1–1000 TPS simulation
- immutable Unicode text/cell/color/palette screen snapshots
- key, typed-text, paste, touch, drag, drop, and scroll input
- bounded events and run-failure observations
- atomic text, cells JSON, and deterministic headless PNG artifacts
- compatible, bounded workspace snapshots with non-destructive transactional restore
- checksummed diagnostic bundles with redacted project paths and disk sources
- JSON-RPC 2.0 protocol-major handshake and bounded newline framing
- agent-owned stdio with protocol-only stdout
- authenticated `127.0.0.1` service ownership with atomic connection metadata
- `ocelot-harnessd` lifecycle commands and `ocelotctl` machine, screen, snapshot, and diagnostic commands
- an optional live `ocelot-viewer` window sharing the daemon session with agents and CLI clients
- an isolated vertical fixture and forked real-brain multi-device integration project
- independent computers, screens, host disks, targeted input paths, diagnostics, and configured network connectivity
- exact harness/brain commit identity in packaged protocol responses
- checked-in [CLI reference](docs/reference/cli.md), [manifest-v2 reference](docs/reference/manifest-v2.md), [Desktop import guide](docs/reference/desktop-import.md), [live-viewer guide](docs/reference/viewer.md), [two-computer example](examples/two-computers/), [SBOM](docs/release/sbom.cdx.json), [dependency report](docs/release/dependencies.md), and [third-party notices](THIRD_PARTY_NOTICES.md)

The optional viewer renders and controls emulated screens only. Ocelot Desktop canvas/window automation remains outside the harness.

## Toolchain decision

| Tool | Baseline |
|---|---|
| Java | 8 |
| Scala | 2.13.10 |
| SBT | 1.8.3 |
| ocelot-brain | pinned submodule at `bec1cc6b1e9e588692f753e9c617063c74967fed` |

## Build and verification

Initialize the pinned dependency after cloning:

```bash
git submodule update --init --recursive
```

Run the equivalent canonical verification entrypoint for the current platform:

```text
scripts/verify          # POSIX shell or Git Bash
scripts\verify.cmd      # Windows Command Prompt or PowerShell
```

The scripts verify the submodule pin, checked SBT bootstrap, formatting, strict first-party compilation, tests, assembly, packaged viewer entrypoint, exact build identity, and a packaged stdio protocol smoke that boots the real-brain fixture, captures a PNG, saves and restores a snapshot, injects touch and paste, emits diagnostics, and shuts down cleanly with protocol-only stdout.

## Create a deterministic project

Generate a runnable schema-v2 project without manually writing hardware topology:

```text
scripts\ocelotctl.cmd project init demo --template mixed-network
scripts\ocelotctl.cmd --project demo project validate
scripts\ocelot-harnessd.cmd up --project demo
```

See the [manifest-v2 reference](docs/reference/manifest-v2.md) for supported devices, semantic inventories, typed ports, and security bounds.

## Import a Desktop workspace

Inspect and import a compatible saved Ocelot Desktop directory without starting a daemon:

```text
scripts\ocelotctl.cmd project inspect-desktop <desktop-directory> --json
scripts\ocelotctl.cmd project import-desktop <desktop-directory> <project-directory> --json
scripts\ocelotctl.cmd --project <project-directory> project validate
```

The importer copies bounded source data, preserves the complete brain entity/edge graph, assigns stable logical IDs to discovered computers and screens, safely rebinds contained managed disks, and leaves the original directory unchanged. See the [Desktop import guide](docs/reference/desktop-import.md) for compatibility and security boundaries.

## Live viewer

Start the loopback daemon, then attach the separate Swing viewer to the same session:

```text
scripts\ocelot-harnessd.cmd up --project examples/two-computers
scripts\ocelotctl.cmd --project examples/two-computers machine start alpha
scripts\ocelotctl.cmd --project examples/two-computers simulation start --tps 20
scripts\ocelot-viewer.cmd --project examples/two-computers --screen alpha
```

Schema-v2 projects auto-start their simulation clock at the manifest `runtime.tickRate` (20 TPS by default). The viewer displays target/measured TPS and overruns and provides pause/resume, exact single-step, and rate controls. The daemon remains headless and can be controlled concurrently through `ocelotctl`; closing the window does not stop it. See the [live-viewer guide](docs/reference/viewer.md) for POSIX usage, controls, scaling, and refresh bounds.

Clock control is also available without the viewer:

```text
scripts\ocelotctl.cmd --project demo simulation status
scripts\ocelotctl.cmd --project demo simulation rate 100
scripts\ocelotctl.cmd --project demo simulation pause
scripts\ocelotctl.cmd --project demo simulation step 1
scripts\ocelotctl.cmd --project demo simulation resume
```

## License

Ocelot Harness is available under the [MIT License](LICENSE). Ocelot-brain remains under its own included licenses.
