package ocelot.harness.core.runtime

import java.nio.file.Path

final case class RuntimeConfig(
    runtimeDirectory: Path,
    nativeLibraryDirectory: Path
)
