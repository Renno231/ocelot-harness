# Ocelot Harness 0.1.0 dependency report

Generated from `harnessApp/dependencyTree` for the assembled runtime on 2026-08-29. Test-only dependencies are listed separately and are not packaged.

## Runtime graph

| Component | Version | Origin | License |
|---|---:|---|---|
| Scala library | 2.13.10 | Maven Central | Apache-2.0 |
| ocelot-brain | 0.24.2 / `bec1cc6b1e9e588692f753e9c617063c74967fed` | pinned source submodule | MIT; retained resources also carry MIT/CC0-1.0/OFL-1.1 notices |
| Typesafe Config | 1.4.4 | Maven Central | Apache-2.0 |
| ujson | 3.3.1 | Maven Central | MIT |
| upickle-core | 3.3.1 | Maven Central | MIT |
| geny | 1.1.0 | Maven Central | MIT |
| Guava | 33.4.8-jre | Maven Central | Apache-2.0 |
| failureaccess | 1.0.3 | Maven Central | Apache-2.0 |
| listenablefuture | 9999.0-empty-to-avoid-conflict-with-guava | Maven Central | Apache-2.0 |
| error_prone_annotations | 2.36.0 | Maven Central | Apache-2.0 |
| j2objc-annotations | 3.0.0 | Maven Central | Apache-2.0 |
| jspecify | 1.0.0 | Maven Central | Apache-2.0 |
| Commons Codec | 1.19.0 | Maven Central | Apache-2.0 |
| Commons IO | 2.20.0 | Maven Central | Apache-2.0 |
| Commons Lang | 3.18.0 | Maven Central | Apache-2.0 |
| Commons Text | 1.14.0 | Maven Central | Apache-2.0 |
| Log4j API | 2.25.5 | Maven Central; harness security override | Apache-2.0 |
| Log4j Core | 2.25.5 | Maven Central; harness security override | Apache-2.0 |
| ASM | 9.8 | Maven Central | BSD-3-Clause |
| OC-LuaJ | 20220907.1 | pinned brain dependency | upstream OpenComputers distribution |
| OC-JNLua | 20230530.0 | pinned brain dependency | upstream OpenComputers distribution |
| OC-JNLua-Natives | 20220928.1 | pinned brain dependency | upstream OpenComputers distribution |

## Test-only direct dependency

| Component | Version | License |
|---|---:|---|
| ScalaTest | 3.2.19 | Apache-2.0 |

## Vulnerability review

An OSV Maven `querybatch` scan on 2026-08-29 covered the Maven coordinates and exact versions above. The original brain resolution selected Log4j 2.25.1 and returned five moderate advisories: `GHSA-qv9r-c865-cp47`, `GHSA-3pxv-7cmr-fjr4`, `GHSA-445c-vh5m-36rj`, `GHSA-6hg6-v5c8-fphq`, and `GHSA-vc5p-v9hr-52mj`. Ocelot Harness overrides Log4j API/Core to 2.25.5 without modifying the pinned brain source. A follow-up exact-version OSV query returned no advisories for either 2.25.5 artifact. The full exact-version batch returned no remaining advisories and therefore no critical finding requiring disposition.

This is point-in-time evidence, not a guarantee against later disclosures. Refresh the dependency tree, SBOM, and OSV query for every release.
