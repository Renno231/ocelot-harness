# ADR 0004: Real-brain integration tests and one canonical verification entrypoint

- **Status:** Accepted
- **Decision owners:** Ocelot Harness maintainers

## Context

Ocelot-brain owns process-global registries, global worker pools, asynchronous machine execution, and a global event bus. Mocking these behaviors would hide the lifecycle, timing, native-library, and cleanup failures that matter most. The repository must also behave consistently for human developers, coding agents, and future hosted CI.

## Decision

- Develop behavior test-first through the durable `harness-core` and protocol interfaces.
- Exercise emulation behavior against the pinned real ocelot-brain dependency.
- Run core integration suites in isolated forked JVMs with parallel execution disabled.
- Initialize Ocelot once per integration process and shut it down once.
- Copy mutable fixtures to a fresh temporary project root for each test.
- Use logical IDs, bounded conditions, and immutable observations; do not depend on generated addresses or arbitrary sleeps.
- Provide `scripts/verify` and `scripts/verify.cmd` as equivalent canonical verification entrypoints.
- Make future hosted CI call the canonical script rather than duplicate build logic in a provider workflow.
- Require clean-clone Windows Java 8 and Linux Java 8 evidence before a release.

## Verification order

```text
submodule pin
→ formatting
→ strict first-party compilation
→ unit tests
→ real-brain integration tests
→ process/protocol tests
→ assembly
→ packaged vertical smoke test
```

## Consequences

- The test suite is slower than a mock-heavy suite but detects actual integration failures.
- Global state cannot leak silently between parallel tests.
- Platform/native-library failures appear before release.
- Developers can run targeted suites during iteration, but completion requires canonical verification.
- CI host selection is deferred without weakening repository reproducibility.
