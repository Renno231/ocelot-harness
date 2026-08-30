# Two-computer example

This schema-v1 project creates independent tier-3 and tier-2 computers, screens, keyboards, managed host disks, and wired network cards. The `beta` disk is read-only.

```text
ocelot-harnessd up --project examples/two-computers
ocelot-viewer --project examples/two-computers --screen alpha
ocelotctl --project examples/two-computers machine start alpha
ocelotctl --project examples/two-computers simulation start --tps 20
ocelotctl --project examples/two-computers screen wait alpha --contains ALPHA --max-ticks 2000 --timeout 10s
ocelotctl --project examples/two-computers screen touch alpha 1 1
ocelotctl --project examples/two-computers screen read beta
ocelot-harnessd down --project examples/two-computers
```

The optional viewer and CLI attach to the same persistent daemon session. This compatibility example uses schema v1, so its clock is started explicitly; newly initialized schema-v2 projects auto-start at their configured target TPS. The two disconnected screen paths demonstrate targeted input isolation. Add an approved connecting device or connection when programs need a shared component network.
