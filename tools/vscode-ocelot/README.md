# Ocelot Harness Debugger for VS Code

Set breakpoints in a project's Lua files, stop a running computer on them, look at its stack,
locals, upvalues and table contents, and step over, into or out of lines. While a computer is
stopped the whole simulation is paused, so timers, other computers and messages wait too.

## Install

Copy this folder into your VS Code extensions folder and restart VS Code:

- Windows: `%USERPROFILE%\.vscode\extensions\ocelot-harness-debug`
- Linux: `~/.vscode/extensions/ocelot-harness-debug`

Then set **Ocelot Harness: Home** (`ocelotHarness.home`) to the Ocelot Harness folder: the
extracted download (it has `bin/ocelot-dap`) or the source checkout (it has
`scripts/ocelot-dap`).

## Use

1. Start the project's service: `ocelot-harnessd up --project <project>`, and start its computers.
2. Add a launch configuration:

   ```json
   {
     "type": "ocelot",
     "request": "attach",
     "name": "Debug Ocelot computers",
     "project": "${workspaceFolder}"
   }
   ```

3. Set breakpoints in Lua files that the project's disks load from the project folder, then
   press F5. Pause stops whichever computer runs next.

Stopping the debug session removes its breakpoints and lets a stopped computer continue.

Breakpoints match files that a computer loads from a host folder disk by their path inside that
disk, so a file loaded under a different name or from a copied disk image does not stop.
