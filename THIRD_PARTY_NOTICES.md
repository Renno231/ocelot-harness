# Third-party notices

Ocelot Harness is MIT licensed. The assembled application also contains the pinned ocelot-brain runtime and its retained OpenComputers resources.

| Material | Notice source | Terms |
|---|---|---|
| ocelot-brain | `lib/ocelot-brain/LICENSE` | MIT |
| Retained OpenComputers code/resources | `lib/ocelot-brain/LICENSE-oc` | MIT; images, textures, and localization strings are CC0-1.0 |
| GNU Unifont-derived `font.hex` | `lib/ocelot-brain/LICENSE-unifont` | SIL Open Font License 1.1 |
| wcwidth table | `lib/ocelot-brain/src/main/resources/LICENSE-wcwidth` | license text retained with the resource |
| Bundled Lua firmware resources | `lib/ocelot-brain/src/main/resources/assets/opencomputers/lua/LICENSE-*` | individual license texts retained with the resources |

Maven runtime and test dependencies, versions, origins, and SPDX-style license identifiers are listed in [`docs/release/dependencies.md`](docs/release/dependencies.md). The machine-readable runtime inventory is [`docs/release/sbom.cdx.json`](docs/release/sbom.cdx.json).

## Java runtime in platform downloads

Windows x64 and Linux x64 downloads include Eclipse Temurin HotSpot **8u504-b01**. It is distributed under **GPL-2.0 with the Classpath Exception**, with additional terms/notices retained in the runtime's `LICENSE`, `ASSEMBLY_EXCEPTION`, `NOTICE` and `THIRD_PARTY_LICENSE` files. The complete runtime directory is preserved in each package. Ocelot Harness retains its own MIT license.

Exact upstream archive URLs and SHA-256 pins are in source file `project/runtime-distributions.json` and in each package's `RELEASE-MANIFEST.json`. Corresponding upstream runtime source is supplied as the separate `OpenJDK8U-jdk-sources_8u504b01.tar.gz` release asset, alongside its checksum. Upstream distributions and source are at [Temurin jdk8u504-b01](https://github.com/adoptium/temurin8-binaries/releases/tag/jdk8u504-b01); build scripts and instructions are maintained by [adoptium/temurin-build](https://github.com/adoptium/temurin-build).

The Maven dependency inventory above covers the application graph, not the separately bundled Java runtime. The authoritative full license texts remain in the paths above and in the packaged dependency resources. This notice summarizes them; it does not replace their terms.
