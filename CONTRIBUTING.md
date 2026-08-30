# Contributing to Ocelot Harness

## Read first

Changes must preserve the contracts in:

1. [`docs/architecture/phase-1.md`](docs/architecture/phase-1.md)
2. [`docs/decisions/`](docs/decisions/)
3. the applicable plan under [`docs/plans/`](docs/plans/) when a change is plan-driven

Open a design discussion before changing a manifest or protocol contract, widening a public module boundary, weakening security limits, modifying the pinned brain source, or adding a new backend.

## Development baseline

- Java 8
- Scala 2.13.10
- SBT 1.8.3
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

See [`SECURITY.md`](SECURITY.md).

## Dependency changes

A dependency update includes:

- reason and affected module
- exact old/new versions or commits
- license check
- clean dependency resolution
- targeted compatibility tests
- canonical verification

An ocelot-brain update must be a dedicated change. Keep the submodule unmodified; use an approved upstream contribution or documented fork commit when a patch is necessary.

## Documentation

Update public documentation in the same change as behavior. Examples must use stable logical IDs and explicit limits. Agent-facing instructions must stand alone for a new reader: include current inputs, authority, stop conditions, and expected evidence.

## Completion evidence

Report the exact commands run, exit codes, and test counts. Distinguish targeted verification from full canonical verification. A successful compile does not establish runtime, protocol, or resource-cleanup correctness.
