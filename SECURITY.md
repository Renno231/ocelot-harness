# Ocelot Harness security policy

## Threat model

Ocelot Harness runs three classes of untrusted input:

```text
project manifest or imported Desktop workspace
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

### Deterministic manifest construction

- Schema-v2 project initialization refuses occupied destinations and writes only beneath the requested project root.
- Unknown device, inventory, option, and port keys are rejected before construction.
- Semantic slot, tier, rack-mount, cardinal-side, multiplicity, and topology limits are validated before any brain entity is created.
- Managed media uses the same canonical service-approved root policy as schema v1.
- Internet hardware is rejected unless both manifest and service policy opt in.

### Desktop workspace import

- Import requires the compatible pinned brain serialization format and preflights every serialized entity class before loading.
- Compressed NBT, decompressed allocations, entity/edge counts, copied file count, and copied bytes have hard ceilings.
- Source copies reject symbolic links, junction escapes, and special files.
- Managed-disk paths must resolve beneath the source and are rebound beneath the copied project.
- Private identity metadata is bounded, include-free, checksummed against `workspace.nbt`, and validated before runtime construction.
- Candidate workspace and snapshot loads are transactional; failure leaves an active session unchanged.
- The original Desktop workspace is read-only input and is never rewritten.

### Network

- Internet Card HTTP and TCP are disabled by the harness brain configuration by default.
- Enabling either requires both an explicit service policy and an explicit project request.
- The persistent control service binds to `127.0.0.1` only.
- Each run uses a cryptographically random 256-bit authentication token.
- Connection metadata is published atomically with owner-only filesystem permissions.
- Project locks and process-instance identity checks protect stale cleanup and forced termination.
- Tokens are excluded from ordinary logs, diagnostics, and command output.

### Optional viewer

- The graphical viewer is a separate authenticated loopback client; it does not run inside the daemon.
- Only the selected screen is polled, and only immutable protocol snapshots are rendered.
- Host clipboard content is read only after the user presses **Paste clipboard** and is then handled by the existing bounded paste-input contract.
- Clock buttons call the same authenticated, bounded simulation protocol as the CLI; viewer polling never advances simulation time.
- Closing the window closes its client connection and bounded worker without stopping or taking ownership of the daemon.

### Resource limits

Every run condition has both a maximum simulated tick count and wall-clock deadline. The harness also bounds:

- service and viewer command queues
- target TPS (1–1000), manual steps (1–10000), and rolling clock measurements
- missed tick deadlines are skipped instead of queued as catch-up work
- event retention
- device, inventory-item, connection, computer, screen, and managed-media counts
- capture frequency
- artifact size
- diagnostic size
- protocol line/message size
- shutdown grace period

Ocelot's computer execution timeout remains enabled.

## Secrets

Project manifests are not a secrets store. Diagnostic bundles include a redacted manifest and policy summary. Clients should pass secrets through a future dedicated secret-input mechanism; until that exists, projects requiring secrets are outside the supported threat model.

## Reporting a vulnerability

Report suspected vulnerabilities through [GitHub private vulnerability reporting](https://github.com/Renno231/ocelot-harness/security/advisories/new). Do not open a public issue for an undisclosed vulnerability.

Include:

- affected version or commit
- operating system and Java version
- minimal reproduction
- host access obtained or data exposed
- whether untrusted OC code, manifest data, or protocol input is required

Do not attach live tokens, private files, or unredacted diagnostic bundles to public discussions.

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
- automatic or background host-clipboard access
