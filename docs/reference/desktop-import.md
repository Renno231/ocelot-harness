# Import an Ocelot Desktop workspace

Ocelot Harness can inspect and copy a workspace saved by compatible Ocelot Desktop commit `586d6ef`, which uses the pinned ocelot-brain commit `bec1cc6b1e9e588692f753e9c617063c74967fed`.

```text
Desktop workspace directory
├─ workspace.nbt
└─ disk and device data
        ↓ bounded local import
Harness schema-v2 project
├─ ocelot-harness.conf
├─ desktop/                         copied source
└─ .ocelot-harness/desktop-import.conf
```

The original Desktop directory is never modified.

## Inspect compatibility

Inspection is local and does not require a running daemon:

```text
scripts\ocelotctl.cmd project inspect-desktop <desktop-directory> --json
```

The result reports the source checksum, entity and edge counts, discovered computers and screens, and assigned logical IDs. Import stops before mutation when the workspace has malformed or oversized NBT, unavailable classes, external managed-disk paths, links or special files, or excessive source/graph size.

| Import bound | Limit |
|---|---:|
| Compressed/decompressed NBT | 64 MiB each |
| Copied source | 512 MiB |
| Copied files | 4,096 |
| Brain entities | 4,096 |
| Serialized edges | 16,384 |

## Import

The destination must not already exist:

```text
scripts\ocelotctl.cmd project import-desktop <desktop-directory> <project-directory> --json
scripts\ocelotctl.cmd --project <project-directory> project validate --json
```

Import copies the complete backend graph and retained Desktop frontend data. Harness interprets frontend labels only as logical-ID hints. Duplicate labels receive deterministic numeric suffixes. Private metadata binds logical IDs to persistent brain entity UUIDs, so IDs remain stable across daemon reopen and Harness snapshot restore.

Managed disks with custom paths must resolve beneath the Desktop source. Their paths are rebound to the copied project data before the workspace is loaded. Desktop-only addon classes absent from the pinned runtime are reported as compatibility errors rather than executed.

## Run and view

```text
scripts\ocelot-harnessd.cmd up --project <project-directory>
scripts\ocelotctl.cmd --project <project-directory> machine start <computer-id>
scripts\ocelot-viewer.cmd --project <project-directory> --screen <logical-screen-id>
```

Imported schema-v2 projects auto-start a 20 TPS clock by default; use the ordinary simulation commands or viewer clock controls to pause, step, or change the rate. Use the machine, screen, artifact, snapshot, and diagnostic commands with the logical IDs returned by inspection:

```text
scripts\ocelotctl.cmd --project <project-directory> workspace describe
scripts\ocelotctl.cmd --project <project-directory> machine start <computer-id>
scripts\ocelotctl.cmd --project <project-directory> screen read <screen-id>
scripts\ocelotctl.cmd --project <project-directory> snapshot save imported-ready
```

Harness directly loads the saved `back` compound into the pinned brain runtime. Every brain entity and edge remains present even when it has no specialized Harness command. The copied `front` compound is retained as source data, but Desktop canvas positions, windows, and OpenGL presentation are not displayed by the Harness viewer.
