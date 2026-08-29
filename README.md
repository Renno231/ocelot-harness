# Ocelot Harness

Ocelot Harness is a headless control plane for developing and testing OpenComputers software against the real [ocelot-brain](https://gitlab.com/cc-ru/ocelot/ocelot-brain) runtime—without Minecraft or Ocelot Desktop.

```text
project manifest + host files
→ emulated computers, screens, disks, and networks
→ key/paste/touch/drag/drop/scroll input
→ screen text, exact cells/colors, PNGs, events, and test artifacts
```

## Status

Phase 1 architecture and implementation are approved. The completed foundation and project-construction milestones provide a reproducible Java 8/SBT build, clean pinned-brain integration, process-global lifecycle ownership, pure schema-v1 manifest validation, canonical host-path policy, and construction of one legal tier-3 computer/screen topology. Four cohesive milestones build the remaining agent-facing control plane.

Start here:

1. [`docs/ocelot-automation-feasibility.md`](docs/ocelot-automation-feasibility.md)
2. [`docs/architecture/phase-1.md`](docs/architecture/phase-1.md)
3. [`docs/plans/phase-1-implementation.md`](docs/plans/phase-1-implementation.md)
4. [`CONTRIBUTING.md`](CONTRIBUTING.md)

## Planned Phase 1 capabilities

- versioned HOCON project manifests with stable logical device IDs
- validated OpenComputers hardware profiles
- multiple computers, screens, keyboards, filesystems, and network connections
- host-directory-backed managed disks
- machine lifecycle and bounded simulation control
- emulated user input
- text, cell/color, and headless PNG screen capture
- event collection, diagnostics, and snapshots
- JSON-RPC over agent-owned stdio and authenticated loopback transport
- a general CLI

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

The scripts verify the submodule pin, checked SBT bootstrap, formatting, strict first-party compilation, tests, assembly, and a packaged real-brain lifecycle smoke that constructs, describes, and closes a valid project before shutdown.

## License

Ocelot Harness is available under the [MIT License](LICENSE). Ocelot-brain remains under its own included licenses.
