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
ocelotctl project init <project-directory> --template <single-computer|two-computers|rack-server|mixed-network> [--json]
ocelotctl project inspect-desktop <desktop-directory> [--json]
ocelotctl project import-desktop <desktop-directory> <project-directory> [--json]
ocelotctl --project <project-directory> project validate [--json]
ocelotctl version
ocelotctl batch
ocelotctl workspace describe
ocelotctl machine <start|stop|reset> <computer-id>
ocelotctl machine status <computer-id>
ocelotctl simulation run --screen <id> --contains <text> [--max-ticks <n>] [--timeout <n>ms|<n>s]
ocelotctl simulation start [--tps <1..1000>]
ocelotctl simulation pause
ocelotctl simulation resume
ocelotctl simulation step [count]
ocelotctl simulation rate <1..1000>
ocelotctl simulation status
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
ocelotctl snapshot diff <from-name> <to-name>
ocelotctl media eject <disk-drive-id>
ocelotctl redstone input <computer-id> <side> <0..15>
ocelotctl diagnostics collect [--path <relative-path>]
```

Observability commands:

```text
ocelotctl trace [--computer <id>] [--kind <call,signal_queued,signal_taken,input,crash,started,stopped,note>] [--name <text>] [--after <sequence>] [--limit <1..5000>]
ocelotctl metrics [--computer <id>]
ocelotctl metrics --history <n> [--computer <id>]
ocelotctl metrics reset
ocelotctl profile start [--computer <id>]
ocelotctl profile stop [--path <relative-path>]
ocelotctl simulation mode [threaded | deterministic [--server-tps <n>]]
ocelotctl record start <name>
ocelotctl record stop
ocelotctl replay <name> [--until-screen <screen-id> --contains <text>] [--timeout <n>ms|<n>s]
ocelotctl sweep <snapshot-name> --screen <screen-id> --contains <text> [--runs <n>] [--max-ticks <n>] [--min-tps <n>] [--max-tps <n>] [--seed <n>] [--timeout <n>ms|<n>s]
ocelotctl debug break <file> [line ...]
ocelotctl debug <pause|state|stack|continue|step|next|out>
ocelotctl debug wait [--after <pause-sequence>] [--timeout <n>ms|<n>s]
ocelotctl debug vars [<reference>]
ocelotctl jfr start
ocelotctl jfr stop [--path <relative-path>]
```

`trace` reads the flight recorder: every component call with its arguments, results and time, every signal queued and taken (modem messages included), and the input the harness delivered. `metrics` shows how close each computer runs to OpenComputers' limits: memory, the longest run without yielding against the 5 s timeout, the per-tick call budget, and the 256-signal queue. `profile` samples where Lua spends its time and writes a flame graph (`.svg`) and folded stacks (`.folded.txt`). `simulation mode deterministic` runs every computer on the tick thread in a fixed order, so a run repeats exactly; `--server-tps` models a server running slower, which gives computers more slices per tick. `record` saves a state and logs every input and speed change after it; `replay` runs that again, reports the first tick where it diverges, and with `--until-screen` stops at the first tick the text shows. `sweep` reruns a save state under seeded server-lag patterns and lists the seeds that fail; each seed reruns the same pattern. `debug break` sets the breakpoints for one file (no lines clears them); a computer stopped on a breakpoint pauses the whole simulation until it continues. `snapshot diff` compares two save states by device names, card slots and screen lines, and the files of host-folder disks when both copied them. `jfr` records the service JVM itself with Java Flight Recorder for JDK Mission Control. `ocelot-dap` is the debug adapter for the VS Code extension in `tools/vscode-ocelot`.

Daemon lifecycle commands:

```text
ocelot-harnessd up --project <path>
ocelot-harnessd status --project <path> [--json]
ocelot-harnessd down --project <path> [--json]
ocelot-harnessd force-stop --project <path>
ocelot-harnessd serve <--stdio|--loopback> --project <path>
```

Project init/inspect/import/validate commands run locally without a daemon. Desktop import copies bounded compatible source data and never modifies the original directory. Schema-v2 clocks auto-start by default; schema-v1 sessions start paused. Target TPS is bounded to 1–1000, manual step count to 1–10000, and measured TPS is informational rather than a real-time guarantee. Coordinates are one-based. All waits require positive tick and wall-clock bounds. Human-readable screen reads and waits request compact protocol projections; `--json` retains complete protocol responses. `redstone input` sets what a computer's redstone card reads on one side (bottom, top, back, front, right, left, or 0–5), as a neighbouring block would; a rise to the card's wake threshold starts a stopped computer. `batch` keeps one process for many commands: each stdin line is a JSON array of arguments (global options included), and each answer is one stdout line `{"exit", "stdout", "stderr"}`. Typed keys wait whole ticks for room in the computer's 256-signal queue, so typing never drops keys, and typing and pasting return once the computer has taken the input, so the next screen read shows what it did; a computer that takes no input for 2000 ticks in a row fails it. Each key down and up costs the computer a slice, about 12 ms; a paste is one signal per line. Artifact paths are project-relative and remain inside the configured artifact root.
