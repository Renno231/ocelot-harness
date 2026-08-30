# Ocelot Harness live viewer

`ocelot-viewer` is an optional graphical client for a running loopback daemon. It displays the exact headless screen raster while agents and CLI clients continue to control the same project session.

```text
agent or CLI ─┐
              ├─ authenticated loopback ─→ ocelot-harnessd ─→ ocelot-brain
live viewer ──┘
```

The viewer is not Ocelot Desktop: it shows emulated computer screens, not the Desktop canvas, nodes, cables, cases, or windows. A graphical desktop is required only by the viewer process; the daemon remains headless.

## Start it

Build the packaged application:

```text
scripts\sbtw.cmd "harnessApp/assembly"      # Windows
scripts/sbtw "harnessApp/assembly"          # POSIX
```

Start a persistent project session and launch the viewer:

```text
scripts\ocelot-harnessd.cmd up --project examples/two-computers
scripts\ocelotctl.cmd --project examples/two-computers machine start alpha
scripts\ocelotctl.cmd --project examples/two-computers simulation start --tps 20
scripts\ocelot-viewer.cmd --project examples/two-computers --screen alpha
```

POSIX uses the equivalent launcher:

```text
scripts/ocelot-viewer --project examples/two-computers --screen alpha
```

The checked example is schema v1, so the explicit `simulation start` command enables its continuous clock. Newly initialized schema-v2 projects auto-start at their configured target TPS. The daemon must already be running. Closing the window closes only its authenticated client connection; it does not stop the daemon. Stop the project separately:

```text
scripts\ocelot-harnessd.cmd down --project examples/two-computers
```

## Options

```text
ocelot-viewer [--project <path>] [--screen <id>] [--scale <1..8>] [--refresh-ms <50..2000>]
```

- `--project` defaults to the current directory.
- `--screen` selects the initial logical screen; otherwise the first declared screen is selected.
- `--scale` is an integer nearest-neighbor scale and defaults to `2`.
- `--refresh-ms` is the selected-screen polling interval and defaults to `100` milliseconds.

Only the selected screen is polled. A persistent authenticated connection is reused, and the image is rerendered only when the immutable screen revision changes.

## Controls

| Host action | Emulated input |
|---|---|
| Left click | Left touch and drop |
| Right click | Right touch and drop |
| Left/right drag | Bounded touch drag |
| Mouse wheel | Scroll at the pointed cell |
| Printable keyboard input | Typed text |
| Enter, arrows, Backspace, Tab, Delete, Escape | Key down/up |
| Shift and Control | Modifier key down/up |
| **Paste clipboard** button | Explicit host clipboard paste |
| Screen selector | Switch the observed logical screen |
| **Pause/Resume** | Stop or continue the daemon-owned simulation clock |
| **Step** | Advance exactly one tick while paused |
| TPS spinner + **Set TPS** | Change the target rate within 1–1000 TPS |

The status line reports clock state, target TPS, bounded rolling measured TPS, and cumulative overruns alongside the selected screen revision. Measured TPS describes achieved host performance rather than a real-time guarantee.

Mouse coordinates are mapped to one-based emulated cells. Tier-1 screens reject touch operations as required by OpenComputers behavior. Input, clock control, and polling share one bounded background lane; Swing updates occur only on the event-dispatch thread.

The daemon clock—not viewer polling—owns simulation time. Agents and CLI clients can continue issuing bounded machine, simulation, snapshot, and input commands while one or more users watch the same session.
