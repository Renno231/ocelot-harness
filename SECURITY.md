# Ocelot Harness security policy

## Threat model

Ocelot Harness runs three classes of untrusted input:

```text
project manifest
client protocol requests
OpenComputers programs running inside ocelot-brain
```

The service is a local development tool, not a network sandbox or multi-tenant boundary. Its controls reduce accidental and agent-driven host exposure; they do not make arbitrary JVM code safe.

## Default protections

### Host filesystem

- Project-relative read-write paths remain beneath the canonical project root.
- Symlinks and `..` segments are resolved before policy checks.
- Additional host roots are granted by service startup policy, never by the manifest alone.
- Artifacts, runtime metadata, and snapshots remain under their configured canonical roots.
- Artifact and snapshot publication uses temporary files followed by atomic replacement where supported.

### Network

- Internet Card HTTP and TCP are disabled by the harness brain configuration by default.
- Enabling either requires both an explicit service policy and an explicit project request.
- The persistent control service binds to `127.0.0.1` only.
- Each run uses a cryptographically random 256-bit authentication token.
- Connection metadata is published atomically with owner-only filesystem permissions.
- Project locks and process-instance identity checks protect stale cleanup and forced termination.
- Tokens are excluded from ordinary logs, diagnostics, and command output.

### Resource limits

Every run condition has both a maximum simulated tick count and wall-clock deadline. The harness also bounds:

- service command queue
- event retention
- device count
- capture frequency
- artifact size
- diagnostic size
- protocol line/message size
- shutdown grace period

Ocelot's computer execution timeout remains enabled.

## Secrets

Project manifests are not a secrets store. Diagnostic bundles include a redacted manifest and policy summary. Clients should pass secrets through a future dedicated secret-input mechanism; until that exists, projects requiring secrets are outside the supported threat model.

## Supported reporting

Until a public repository security contact is selected, report suspected vulnerabilities privately to the repository owner. Include:

- affected version/commit
- operating system and Java version
- minimal reproduction
- host access obtained or data exposed
- whether untrusted OC code, manifest data, or protocol input is required

Do not attach live tokens, private files, or unredacted diagnostic bundles to a public issue.

## Security-sensitive changes

The following require explicit maintainer review and targeted abuse tests:

- new allowed-root behavior
- Internet Card enablement
- non-loopback listeners
- authentication or connection-metadata changes
- snapshot deserialization changes
- arbitrary class/plugin loading
- command execution outside ocelot-brain
- reduced resource bounds
