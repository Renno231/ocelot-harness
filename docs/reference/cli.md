# Ocelot Harness CLI reference

Generated from `OcelotCtl.ReferenceMarkdown`. Regenerate with the packaged application by running:

```text
java -cp ocelot-harness.jar ocelot.harness.app.OcelotCtl --help
```

Global options:

- `--project <path>` — project directory; defaults to the current directory
- `--json` — emit the command result as compact JSON

Commands:

```text
ocelotctl project inspect-desktop <desktop-directory> [--json]
ocelotctl project import-desktop <desktop-directory> <project-directory> [--json]
ocelotctl --project <project-directory> project validate [--json]
ocelotctl version
ocelotctl workspace describe
ocelotctl machine <start|stop|reset> <computer-id>
ocelotctl simulation run --screen <id> --contains <text> [--max-ticks <n>] [--timeout <n>ms|<n>s]
ocelotctl screen read <screen-id>
ocelotctl screen wait <screen-id> --contains <text> [--max-ticks <n>] [--timeout <n>ms|<n>s]
ocelotctl screen touch <screen-id> <x> <y> [--button <n>]
ocelotctl screen drag <screen-id> <from-x> <from-y> <to-x> <to-y> [--button <n>] [--steps <n>]
ocelotctl screen drop <screen-id> <x> <y> [--button <n>]
ocelotctl screen scroll <screen-id> <x> <y> <delta>
ocelotctl screen paste <screen-id> <text>
ocelotctl screen type <screen-id> <text> [--inter-key-ticks <n>]
ocelotctl screen key-down <screen-id> <key> [--character <text>]
ocelotctl screen key-up <screen-id> <key> [--character <text>]
ocelotctl screen capture <screen-id> --format <png|text|cells-json> [--path <relative-path>] [--scale <n>]
ocelotctl snapshot save <name> [--host-disks <reference-only|copy>]
ocelotctl snapshot load <name>
ocelotctl diagnostics collect [--path <relative-path>]
```

Daemon lifecycle commands:

```text
ocelot-harnessd up --project <path>
ocelot-harnessd status --project <path> [--json]
ocelot-harnessd down --project <path> [--json]
ocelot-harnessd force-stop --project <path>
ocelot-harnessd serve <--stdio|--loopback> --project <path>
```

Project inspect/import/validate commands run locally without a daemon. Desktop import copies bounded compatible source data and never modifies the original directory. Coordinates are one-based. All waits require positive tick and wall-clock bounds. Artifact paths are project-relative and remain inside the configured artifact root.
