# ADR 0003: HOCON project manifests and transport-neutral JSON-RPC control

- **Status:** Proposed
- **Decision owners:** Ocelot Harness maintainers

## Context

Projects need a reviewable desired-configuration format independent of Ocelot's compressed runtime NBT. Agent-owned subprocesses can use stdio, while separate shell CLI invocations require an attachable long-lived service transport.

## Decision

### Project contract

- Use `ocelot-harness.conf` as the canonical HOCON project manifest.
- Require `schemaVersion = 1`.
- Address resources through typed logical IDs, not generated OC addresses or brain class names.
- Resolve relative paths from the project root.
- Constrain read-write host paths to the project root unless service policy explicitly grants another canonical root.
- Keep runtime state, artifacts, connection metadata, and snapshots beneath `.ocelot-harness/`.
- Keep Ocelot workspace NBT as a runtime snapshot format rather than desired configuration.

### Control contract

- Use JSON-RPC 2.0 with a separately versioned Ocelot Harness protocol major version.
- Keep request dispatch independent of transport.
- Support newline-delimited JSON-RPC over stdio for an owning agent/test process.
- Support authenticated loopback transport for persistent service access by separate CLI invocations.
- Return artifact paths and SHA-256 metadata instead of embedding large binary payloads in JSON.

## Consequences

- Project topology can be reviewed and version-controlled.
- Random runtime addresses do not destabilize scripts or tests.
- Pi tooling and the general CLI use one command contract.
- The service can remain alive between shell commands.
- Loopback lifecycle metadata and authentication require dedicated security and stale-process tests.
- Schema and protocol changes require compatibility policy and contract tests.

## Required compatibility rules

- Reject unsupported manifest schema versions before creating a workspace.
- Reject incompatible protocol major versions during connection setup.
- Additive protocol fields remain optional within one major version.
- Unknown hardware/security manifest keys are validation errors.
- Snapshot metadata records harness, schema, protocol, and brain versions before restoration is attempted.
