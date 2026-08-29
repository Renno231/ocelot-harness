# Ocelot Harness

Ocelot Harness is a headless control plane for developing and testing OpenComputers software against the real [ocelot-brain](https://gitlab.com/cc-ru/ocelot/ocelot-brain) runtime—without Minecraft or Ocelot Desktop.

```text
project manifest + host files
→ emulated computers, screens, disks, and networks
→ key/paste/touch/drag/drop/scroll input
→ screen text, exact cells/colors, PNGs, events, and test artifacts
```

## Status

Phase 1 architecture and implementation are approved. The completed foundation, project-construction, and interactive-execution milestones provide a reproducible Java 8/SBT build, sole process-global lifecycle ownership, schema-v1 project construction, serialized bounded simulation, immutable screen observations, emulated input, and a real host-edit → boot → interact → observe loop. Three cohesive milestones remain: artifacts/recovery, the external control plane, and release hardening.

Start here:

1. [`docs/ocelot-automation-feasibility.md`](docs/ocelot-automation-feasibility.md)
2. [`docs/architecture/phase-1.md`](docs/architecture/phase-1.md)
3. [`docs/plans/phase-1-implementation.md`](docs/plans/phase-1-implementation.md)
4. [`CONTRIBUTING.md`](CONTRIBUTING.md)

## Implemented capabilities

- versioned HOCON project manifests with stable logical device IDs
- one validated tier-3 computer/screen hardware profile
- host-directory-backed managed disks with canonical allowed-root policy
- machine start, stop, reset, and condition-driven bounded simulation
- immutable Unicode text/cell/color/palette screen snapshots
- key, typed-text, paste, touch, drag, drop, and scroll input
- bounded events and run-failure observations
- an isolated real-brain vertical fixture under `fixtures/vertical-spike/`

## Remaining Phase 1 capabilities

- deterministic PNG artifacts, snapshots, and diagnostic bundles
- broader multi-device and hardware-profile coverage
- JSON-RPC over agent-owned stdio and authenticated loopback transport
- the `ocelotctl` CLI and release hardening

Ocelot Desktop canvas/window automation is outside Phase 1.

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

The scripts verify the submodule pin, checked SBT bootstrap, formatting, strict first-party compilation, tests, assembly, and a packaged real-brain vertical smoke that boots the fixture, injects touch and paste, observes the screen, reloads a host-file edit, and shuts down without live harness threads.

## License

Ocelot Harness is available under the [MIT License](LICENSE). Ocelot-brain remains under its own included licenses.
