# Contributing to Ocelot Harness

## Read first

Changes must preserve the contracts in:

1. [`docs/architecture/phase-1.md`](docs/architecture/phase-1.md)
2. [`docs/decisions/`](docs/decisions/)
3. the applicable plan under [`docs/plans/`](docs/plans/) when a change is plan-driven

Open a design discussion before changing a manifest or protocol contract, widening a public module boundary, weakening security limits, modifying the pinned brain source, or adding a new backend.

## Development baseline

- Java 8, 17 or 21 JDK
- Scala 2.13.16
- SBT 1.10.11
- Python 3.10+ for launcher and release-packaging checks
- initialized `lib/ocelot-brain` submodule at the recorded commit

Use `scripts/sbtw` or `scripts\sbtw.cmd` for targeted SBT work rather than a machine-global installation. After reconciling a complete milestone, run the platform's canonical entrypoint: `scripts/verify` on POSIX/Git Bash or `scripts\verify.cmd` on Windows.

## Change discipline

```text
one approved behavior
→ one failing test
→ smallest complete implementation
→ targeted verification
→ refactor while green
→ canonical verification
```

### Test through durable interfaces

- Test core behavior through the intention-level `HarnessSession` API.
- Use real ocelot-brain in integration tests.
- Use test doubles only at true external boundaries such as clocks, transports, and artifact sinks.
- Keep brain classes, generated addresses, raw slot indexes, and thread timing out of public expectations.
- Copy fixture projects into isolated temporary directories before tests mutate them.
- Replace arbitrary sleeps with bounded conditions. Low-level pacing/polling helpers must always accept a deadline.

### Preserve resource safety

Every acquired resource has an explicit owner and close path:

- `RuntimeOwner` owns process-global Ocelot lifecycle.
- A session owns its workspace, executor, subscriptions, and entities.
- A command owns its cancellation/deadline state.
- Artifact and snapshot writers own temporary files until atomic publication.

Tests that create a resource close it even on failure.

## Source boundaries

```text
harness-core
├─ owns emulation behavior and invariants
├─ validates schema-v1 profiles, schema-v2 semantic device graphs, and imported Desktop sources
├─ exposes immutable first-party models
└─ contains no JSON-RPC or CLI presentation

harness-app
├─ owns protocol, transports, CLI, optional viewer, and process entrypoints
├─ confines AWT/Swing state to the separate viewer process
└─ delegates emulation policy to harness-core
```

Do not introduce a generic emulator backend interface while ocelot-brain is the only backend. Organize private implementation by responsibility without creating pass-through public wrappers. Graphical viewer tests exercise pure decoding, geometry, protocol, and lifecycle behavior without requiring a display from canonical CI. Schema-v2 catalog changes update the generated project templates and [`docs/reference/manifest-v2.md`](docs/reference/manifest-v2.md) in the same change.

## Error handling

Expected failures use typed `HarnessError` values with stable codes. Unexpected brain/library failures are translated at the core boundary, logged with request/session context, and included in diagnostics. Normal protocol responses do not expose stack traces or secrets.

## Security defaults

- Internet Card HTTP/TCP remains disabled unless both service policy and project manifest allow it.
- Resolve symlinks and canonical paths before an allowed-root decision.
- Bind service sockets to loopback only.
- Never log service tokens.
- Treat emulated programs and client input as untrusted.
- Bound queues, events, ticks, wall time, captures, and artifacts.

## Dependency changes

A dependency update includes:

- reason and affected module
- exact old/new versions or commits
- license check
- clean dependency resolution
- targeted compatibility tests
- canonical verification

An ocelot-brain update must be a dedicated change. Keep the submodule unmodified; use an approved upstream contribution or documented fork commit when a patch is necessary.

## Building platform downloads

Run canonical verification from a clean committed checkout. The application JAR must report that exact commit and a clean source tree. Download the platform JRE archive specified by `project/runtime-distributions.json`, then run:

```text
python scripts/package_release.py --platform windows-x64 --java-version 21 --runtime-archive PATH_TO_JRE.zip --output dist
python scripts/package_release.py --platform linux-x64 --java-version 21 --runtime-archive PATH_TO_JRE.tar.gz --output dist
```

Repeat with `--java-version 8` and `--java-version 17` and their matching pinned archives to produce all six editions. Java 21 is the default. The offline packager checks the pinned runtime hash before extraction, copies only allowlisted product files, and writes an archive, payload manifest and SHA-256 sidecar. Repeat packaging with unchanged inputs under the same Python/zlib implementation produces identical bytes. Compression bytes across different zlib implementations are not guaranteed identical.

Before publication, run canonical verification on Windows and Linux with each supported JDK, then test all six extracted packages using their bundled runtimes, including real-brain boot, screen input/capture, daemon shutdown and the viewer entrypoint. Retain full runtime legal files and supply the three matching upstream Temurin source archives and checksums beside the downloads. Keep raw verification logs, local projects and diagnostic artifacts outside the published source and release assets.

## Documentation

Update public documentation in the same change as behavior. Examples must use stable logical IDs and explicit limits. Agent-facing instructions must stand alone for a new reader: include current inputs, authority, stop conditions, and expected evidence.

## Completion evidence

Report the exact commands run, exit codes, and test counts. Distinguish targeted verification from full canonical verification. A successful compile does not establish runtime, protocol, or resource-cleanup correctness.
