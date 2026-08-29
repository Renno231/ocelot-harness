# ADR 0002: Two first-party modules with a concrete brain-backed core

- **Status:** Accepted
- **Decision owners:** Ocelot Harness maintainers

## Context

The project needs a reusable headless automation API and an executable external interface. Ocelot global lifecycle, asynchronous machine execution, permissive raw inventories, and concurrent screen changes impose rules that clients must not coordinate themselves.

## Decision

Create two first-party SBT modules:

- `harness-core`: owns runtime lifecycle, project validation, legal hardware construction, simulation control, input, observations, events, and snapshots.
- `harness-app`: owns JSON-RPC contracts, transports, CLI presentation, and the executable process.

`harness-core` exposes intention-level operations using first-party immutable models. Brain classes, raw inventory indexes, synchronization, subscriptions, and cleanup remain private.

Use one concrete brain-backed implementation. Do not create a generic emulator backend interface in Phase 1. A shared backend interface becomes justified when a second real backend—such as the future Ocelot Desktop adapter—has concrete requirements.

## Consequences

- CLI, agent integrations, and tests share one behavioral core.
- Wire formats cannot leak into emulation policy.
- Brain upgrades are localized behind the core boundary.
- Packages may split private implementation by responsibility without widening the public interface.
- Core integration tests exercise real ocelot-brain rather than mocks.
- The application can evolve transports without changing emulation behavior.

## Invariants

```text
one Ocelot initialization per process
one active BrainSession per service
one serialized mutation/tick lane per session
one owner for logical-to-runtime identity
all waits bounded by ticks and wall time
all session EventBus subscriptions cancelled on close
all screen snapshots copied under screen synchronization
```

## Verification

The runtime foundation places every direct `Ocelot.initialize()` and `Ocelot.shutdown()` call behind the concrete core `RuntimeOwner`. A forked real-brain process constructs, describes, and closes a validated project before global shutdown; application code observes only first-party lifecycle and logical-topology models.
