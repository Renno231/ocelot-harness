# ADR 0003: HOCON project manifests and transport-neutral JSON-RPC control

- **Status:** Accepted
- **Decision owners:** Ocelot Harness maintainers

## Context

Projects need a reviewable desired-configuration format independent of Ocelot's compressed runtime NBT. Agent-owned subprocesses can use stdio, while separate shell CLI invocations and an optional headed screen viewer require an attachable long-lived service transport.

## Decision

### Project contract

- Use `ocelot-harness.conf` as the canonical HOCON project manifest.
- Preserve `schemaVersion = 1` compatibility and use `schemaVersion = 2` for deterministic expanded topology, Desktop sources, and continuous-clock settings.
- Address resources through typed logical IDs, not generated OC addresses or brain class names.
- Resolve relative paths from the project root.
- Constrain read-write host paths to the project root unless service policy explicitly grants another canonical root.
- Keep runtime state, artifacts, connection metadata, and snapshots beneath `.ocelot-harness/`.
- Keep Ocelot workspace NBT as a runtime snapshot format; compatible Desktop `back` graphs may also be imported through the bounded schema-v2 source adapter.
- Schema-v2 projects default to one daemon-owned auto-started 20 TPS clock; schema-v1 projects remain non-continuous.

### Control contract

- Use JSON-RPC 2.0 with a separately versioned Ocelot Harness protocol major version.
- Keep request dispatch independent of transport.
- Support newline-delimited JSON-RPC over stdio for an owning agent/test process.
- Support authenticated loopback transport for persistent service access by separate CLI and viewer processes.
- Return artifact paths and SHA-256 metadata instead of embedding large binary payloads in JSON.
- Expose additive simulation start/pause/resume/step/rate/status methods while retaining bounded condition runs.

## Consequences

- Project topology can be reviewed and version-controlled.
- Random runtime addresses do not destabilize scripts or tests.
- Pi tooling and the general CLI use one command contract.
- The service can remain alive and advance continuously between shell commands while users observe and control the same session through one or more viewers.
- Loopback lifecycle metadata and authentication require dedicated security and stale-process tests.
- Schema and protocol changes require compatibility policy and contract tests.

## Required compatibility rules

- Reject unsupported manifest schema versions before creating a workspace.
- Reject incompatible protocol major versions during connection setup.
- Additive protocol fields remain optional within one major version.
- Unknown hardware/security manifest keys are validation errors.
- Snapshot metadata records harness, schema, protocol, and brain versions before restoration is attempted.

## Verification

Project construction parses schema versions 1 and 2 into immutable typed models before creating or importing brain objects. Contract tests prove deterministic multi-error validation, strict security/hardware keys, project-local includes, typed logical IDs, canonical path enforcement across traversal and Windows junctions, service-owned external roots, Internet double opt-in, expanded device legality, Desktop graph preservation, and service-owned topology caps.

Protocol and process tests prove strict bounded JSON-RPC framing, major-version negotiation, distinct protocol/domain errors, artifact references, protocol-only stdio, constant-time loopback authentication, owner-only atomic metadata, verified project-owner cleanup, loopback-only persistent access, continuous clock controls and lossless counters, concurrent CLI calls beside persistent viewer connections, bounded shutdown, and path handling with spaces.
