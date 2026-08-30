# Manifest schema v2

Schema v2 constructs deterministic ocelot-brain workspaces from semantic device, inventory, and port names. Callers never provide brain classes, runtime addresses, entity UUIDs, or raw inventory indexes.

Create and validate a project locally:

```text
scripts\ocelotctl.cmd project init demo --template mixed-network
scripts\ocelotctl.cmd --project demo project validate --json
```

Available templates are `single-computer`, `two-computers`, `rack-server`, and `mixed-network`. Initialization refuses an occupied destination and writes runnable host-backed Lua programs.

## Source envelope

```hocon
schemaVersion = 2
project { id = "demo" }
workspace { kind = "manifest" }
runtime {
  tickRate = 20
  internet { http = false, tcp = false }
}
```

`workspace.kind = "desktop"` selects an imported Desktop source instead; one project cannot mix imported and manifest topology.

## Devices

| `kind` | Options | Ports |
|---|---|---|
| `computer` | `tier`, `inventory` | `network` |
| `screen` | `tier`, `keyboard`, `aspectRatio` | `network` |
| `rack` | — | six cardinal sides, `mount-1`…`mount-4` |
| `server` | `tier`, `inventory` | `mount`, `network`, primary/secondary rack ports |
| `disk-drive` | floppy inventory | `network` |
| `raid` | `label`, `source`, read-write `access`, three HDDs | `network` |
| `hologram` | `tier` | `network` |
| `note-block`, `iron-note-block` | — | `network` |
| `microcontroller` | `tier`, `inventory` | six cardinal sides |
| `relay` | upgrade inventory | six cardinal sides |
| `cable` | — | `network` |

Tiered devices accept pinned-brain tiers 1–3, except hologram projectors which accept tiers 1–2. Server profiles accept tiers 1–3. Device keys are stable logical IDs.

## Inventory

Semantic slots are validated against the owning device and tier before construction. Supported kinds are:

```text
cpu, apu, memory, gpu, eeprom, component-bus
managed-hdd, unmanaged-hdd
managed-floppy, unmanaged-floppy
network, wireless, linked, data, redstone, internet
```

Managed media requires an ID, label, canonical service-approved source directory, and `read-write` or `read-only` access. Internet cards require both manifest request and service policy approval.

Example computer:

```hocon
devices.main {
  kind = "computer"
  tier = 3
  inventory = [
    { slot = "gpu", kind = "gpu", tier = 3 },
    { slot = "card-1", kind = "network", tier = 1 },
    { slot = "memory-1", kind = "memory", tier = 3.5 },
    {
      slot = "disk-1", kind = "managed-hdd", tier = 3
      id = "project", label = "project"
      source = "./computer", access = "read-write"
    },
    { slot = "cpu", kind = "cpu", tier = 3 },
    { slot = "eeprom", kind = "eeprom", builtin = "lua-bios" }
  ]
}
```

## Connections

Connections use validated `device:port` references:

```hocon
connections = [
  { from = "main:network", to = "display:network" },
  { from = "rack:mount-1", to = "server:mount" },
  { from = "relay:north", to = "cable:network" }
]
```

Rack mount and cardinal-side ports are single-use. Every server requires exactly one rack mount. Duplicate, self, unknown, directionally invalid, and over-limit connections are rejected before any brain entity is created.

## Bounds and compatibility

The service owns caps for computer-like devices, screens, all devices, connections, inventory items, and managed disks. Validation is deterministic and rejects unknown keys. Schema v1 remains supported unchanged. Harness snapshots retain private device identities, the complete constructed graph, managed-media bindings, and public logical IDs without exposing brain implementation types.
