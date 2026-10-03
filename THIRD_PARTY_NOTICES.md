# Third-party notices

Ocelot Harness is MIT licensed. The assembled application also contains the pinned ocelot-brain runtime and its retained OpenComputers resources.

| Material | Notice source | Terms |
|---|---|---|
| ocelot-brain | `lib/ocelot-brain/LICENSE` | MIT |
| Retained OpenComputers code/resources | `lib/ocelot-brain/LICENSE-oc` | MIT; images, textures, and localization strings are CC0-1.0 |
| GNU Unifont-derived `font.hex` | `lib/ocelot-brain/LICENSE-unifont` | SIL Open Font License 1.1 |
| wcwidth table | `lib/ocelot-brain/src/main/resources/LICENSE-wcwidth` | license text retained with the resource |
| Bundled Lua firmware resources | `lib/ocelot-brain/src/main/resources/assets/opencomputers/lua/LICENSE-*` | individual license texts retained with the resources |

The jar also bundles these libraries:

| Library | License |
|---|---|
| Scala library, Typesafe Config, Guava (with failureaccess, listenablefuture, error_prone_annotations, j2objc-annotations, jspecify), Apache Commons Codec/IO/Lang/Text, Log4j API/Core | Apache-2.0 |
| ujson, upickle-core, geny | MIT |
| ASM | BSD-3-Clause |
| OC-LuaJ, OC-JNLua, OC-JNLua-Natives | upstream OpenComputers distribution terms |

## Java runtime in platform downloads

Windows x64 and Linux x64 downloads include the selected Eclipse Temurin HotSpot edition: **8u504-b01**, **17.0.20.1+1** or **21.0.12.1+1**. Temurin is distributed under **GPL-2.0 with the Classpath Exception**, with additional terms/notices retained in the complete runtime directory. Java 8 retains `LICENSE`, `ASSEMBLY_EXCEPTION`, `NOTICE` and `THIRD_PARTY_README`; Java 17/21 retain their `NOTICE` and modular `legal/` trees, including license symlinks on Linux. Ocelot Harness retains its own MIT license.

Exact upstream archive URLs and SHA-256 pins are in source file `project/runtime-distributions.json` and in each package's `RELEASE-MANIFEST.json`. Corresponding upstream runtime sources are supplied as the separate `OpenJDK8U-jdk-sources_*`, `OpenJDK17U-jdk-sources_*` and `OpenJDK21U-jdk-sources_*` release assets, alongside their checksums. Exact source URLs and hashes are also pinned in `project/runtime-distributions.json`. Build scripts and instructions are maintained by [adoptium/temurin-build](https://github.com/adoptium/temurin-build).

The library table above covers the application jar, not the separately bundled Java runtime. The authoritative full license texts remain in the paths above and in the packaged dependency resources. This notice summarizes them; it does not replace their terms.
