# Ocelot Harness — download and run

Ocelot Harness runs OpenComputers software in the Ocelot Brain emulator, without Minecraft. It provides a headless daemon, command-line control and an optional live screen viewer.

## Download

Get a platform archive from the [GitHub releases page](https://github.com/Renno231/ocelot-harness/releases/latest):

| Bundled fallback Java | Windows x64 | glibc-based Linux x64 |
|---|---|---|
| **21 (recommended)** | `ocelot-harness-0.1.1-windows-x64-java21.zip` | `ocelot-harness-0.1.1-linux-x64-java21.tar.gz` |
| 17 | `ocelot-harness-0.1.1-windows-x64-java17.zip` | `ocelot-harness-0.1.1-linux-x64-java17.tar.gz` |
| 8 | `ocelot-harness-0.1.1-windows-x64-java8.zip` | `ocelot-harness-0.1.1-linux-x64-java8.tar.gz` |

Each includes the same application, launchers and examples, plus the named Eclipse Temurin runtime as a fallback. **Your installed Java is used first.** **You do not need to install Java, SBT, Scala or a compiler, or change your system Java.** Extract the complete archive before running it. Do not move individual launchers away from the rest of the package. macOS, ARM and musl/Alpine packages are not provided.

Compare the archive's SHA-256 with its adjacent `.sha256` file. On Windows use `Get-FileHash PATH_TO_ZIP -Algorithm SHA256`; on Linux use `sha256sum -c PATH_TO_ARCHIVE.sha256` from the directory containing both files. The archive also contains `RELEASE-MANIFEST.json` with source, dependency/runtime identity and payload hashes.

## Windows quick start

Open PowerShell in the extracted application directory. These commands create a project beside it:

```powershell
.\bin\ocelotctl.cmd project init ..\demo --template single-computer
.\bin\ocelot-harnessd.cmd up --project ..\demo
.\bin\ocelotctl.cmd --project ..\demo machine start main
.\bin\ocelot-viewer.cmd --project ..\demo
```

Closing the viewer leaves the daemon running. Stop the project when finished:

```powershell
.\bin\ocelot-harnessd.cmd down --project ..\demo
```

The launchers use Windows PowerShell, included with supported Windows installations. Paths containing spaces work when quoted.

## Linux quick start

Extract using `tar -xzf PATH_TO_ARCHIVE.tar.gz`, preserving executable permissions and runtime symlinks. From the extracted application directory:

```sh
./bin/ocelotctl project init ../demo --template single-computer
./bin/ocelot-harnessd up --project ../demo
./bin/ocelotctl --project ../demo machine start main
./bin/ocelot-viewer --project ../demo
# When finished:
./bin/ocelot-harnessd down --project ../demo
```

The viewer requires a graphical desktop/display and the usual X11/font libraries. A headless server can use the daemon and CLI without opening the viewer. The bundled JRE still requires a compatible system C library; the package is not an OS container.

## Using the application

- `bin/ocelotctl --help` lists commands. On Windows use `bin\ocelotctl.cmd`.
- Projects contain a manifest and host-backed program files. Edit a project's program, then use the machine lifecycle commands to restart it.
- Schema-v2 projects advance at 20 TPS by default. Use `ocelotctl --project PATH simulation status` to inspect the clock or `simulation rate 100` to change its target rate.
- The daemon is headless. The separate viewer observes and controls the same emulated screen; closing it does not stop the project.
- `examples/two-computers/` provides a small public example. Copy it to your own project directory before modifying or running it.
- The included CLI, manifest, viewer and Desktop-import guides are under `docs/reference/`. Where source-checkout examples use `scripts/`, use `bin/` in a download.

## Java selection and troubleshooting

Launchers select Java in this order:

1. `OCELOT_JAVA`, if explicitly set to a Java executable.
2. `JAVA_HOME/bin/java`, if `JAVA_HOME` is set.
3. `java` on `PATH`.
4. The package's bundled `runtime/`, when no system Java is configured or found.

**Java 8, 17 and 21 are supported for both application execution and source builds.** The edition name identifies the fallback runtime, not a requirement to use it. Unset `OCELOT_JAVA` to return to automatic system-first selection. A selected Java that is missing, fails to start, or is below Java 8 produces an error rather than silently switching versions; fix the selection or explicitly choose the bundle. Other Java major versions are allowed but outside the verified matrix.

To explicitly use the bundled runtime, from the extracted application directory:

```powershell
$env:OCELOT_JAVA = (Resolve-Path .\runtime\bin\java.exe).Path
```

```sh
export OCELOT_JAVA="$PWD/runtime/bin/java"
```

For a source checkout, set `JAVA_HOME` to a Java 8, 17 or 21 JDK or `OCELOT_JAVA` to its Java executable. Build wrappers honor those settings directly; a global PATH change is unnecessary.

If the application JAR is reported missing, re-extract the full download. If a Linux launcher is not executable, extract with `tar` rather than copying selected files. Diagnostics and project state are stored beneath the project's `.ocelot-harness/`; review these files for private program output and paths before sharing them.

## Licenses and runtime source

Ocelot Harness is MIT licensed. Its bundled Java runtime uses GPL version 2 with the Classpath Exception and retained additional notices. Keep `LICENSE`, `THIRD_PARTY_NOTICES.md` and the runtime's complete legal files with redistributed copies.

The release includes matching upstream `OpenJDK8U-jdk-sources_*`, `OpenJDK17U-jdk-sources_*` and `OpenJDK21U-jdk-sources_*` archives as separate optional source downloads. They are not needed to run the application. Exact upstream runtime/source URLs and hashes are pinned in the source repository's `project/runtime-distributions.json`; runtime provenance and payload hashes are also included in each package's `RELEASE-MANIFEST.json`.
