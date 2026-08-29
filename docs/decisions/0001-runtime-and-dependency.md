# ADR 0001: Scala runtime and pinned ocelot-brain source dependency

- **Status:** Accepted
- **Decision owners:** Ocelot Harness maintainers

## Context

Ocelot Harness must call ocelot-brain entity, workspace, input, event, and persistence APIs directly. Ocelot-brain 0.24.2 is a Scala 2.13.10/SBT 1.8.3 project targeting the Java 8 environment used by upstream. It is not consumed from a conventional Maven release in Ocelot Desktop; upstream pins it as a Git submodule.

## Decision

- Implement Ocelot Harness in Scala 2.13.10 with SBT 1.8.3.
- Use Java 8 as the initial compatibility baseline.
- Add `lib/ocelot-brain` as a Git submodule pinned to commit `bec1cc6b1e9e588692f753e9c617063c74967fed`.
- Reference the submodule as an SBT project dependency.
- Keep the pinned upstream source unmodified.
- Record every future brain update as an explicit dependency-change pull request with integration verification evidence.

## Consequences

- Harness and brain share one JVM and can exchange typed objects without a second internal protocol.
- Source navigation and debugging include brain internals.
- Clones must initialize submodules.
- CI must fetch submodules and use a compatible JDK/SBT toolchain.
- A required brain fix triggers a separate decision: contribute upstream or pin a documented fork commit.
- Java versions newer than 8 may be added to the test matrix after the baseline spike passes; they do not replace the baseline without a compatibility decision.

## Verification

The build foundation proved:

```text
fresh clone with submodules
→ repository SBT wrapper downloads verified SBT 1.8.3 launcher
→ harness-core compiles against pinned brain
→ minimal process initializes and shuts down brain cleanly
```

The pinned source build records the exact brain runtime dependencies. The native boundary resolved and loaded OC-LuaJ `20220907.1`, OC-JNLua `20230530.0`, and OC-JNLua-Natives `20220928.1` on Windows and Linux Java 8. First-party code declares ScalaTest `3.2.19` for tests, and the runtime foundation declares Typesafe Config `1.4.4` for generated restrictive brain configuration; later feature libraries are declared with their first caller.
