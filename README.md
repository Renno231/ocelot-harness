# Ocelot Harness

Ocelot Harness is a headless control plane for developing and testing OpenComputers software against the real [ocelot-brain](https://gitlab.com/cc-ru/ocelot/ocelot-brain) runtime—without Minecraft or Ocelot Desktop.

```text
project manifest + host files
→ emulated computers, screens, disks, and networks
→ key/paste/touch/drag/drop/scroll input
→ screen text, exact cells/colors, PNGs, events, and test artifacts
```

## Status

Phase 1 is implemented. The six completed milestones provide a reproducible Java 8/SBT build, sole process-global lifecycle ownership, bounded schema-v1 project construction, serialized interactive execution, deterministic artifacts and recovery, versioned external control, multi-device coverage, and release evidence on Windows and Linux.

Start here:

1. [`docs/ocelot-automation-feasibility.md`](docs/ocelot-automation-feasibility.md)
2. [`docs/architecture/phase-1.md`](docs/architecture/phase-1.md)
3. [`docs/plans/phase-1-implementation.md`](docs/plans/phase-1-implementation.md)
4. [`CONTRIBUTING.md`](CONTRIBUTING.md)

## Implemented capabilities

- versioned HOCON project manifests with stable logical device IDs
- validated tier-1, tier-2, and tier-3 computer/screen profiles with service-owned topology caps
- host-directory-backed managed disks with canonical allowed-root policy
- machine start, stop, reset, and condition-driven bounded simulation
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
- checked-in [CLI reference](docs/reference/cli.md), [live-viewer guide](docs/reference/viewer.md), [two-computer example](examples/two-computers/), [SBOM](docs/release/sbom.cdx.json), [dependency report](docs/release/dependencies.md), and [third-party notices](THIRD_PARTY_NOTICES.md)

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

## Live viewer

Start the loopback daemon, then attach the separate Swing viewer to the same session:

```text
java -jar modules/app/target/ocelot-harness.jar up --project examples/two-computers
scripts\ocelot-viewer.cmd --project examples/two-computers --screen alpha
```

The daemon remains headless and can be controlled concurrently through `ocelotctl`; closing the window does not stop it. See the [live-viewer guide](docs/reference/viewer.md) for POSIX usage, controls, scaling, and refresh bounds.

## License

Ocelot Harness is available under the [MIT License](LICENSE). Ocelot-brain remains under its own included licenses.
