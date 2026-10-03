#!/usr/bin/env python3
"""Unit tests for the bounded offline release packager."""

from __future__ import annotations

import hashlib
import io
import json
import stat
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from scripts import package_release


SOURCE_COMMIT = "1" * 40
BRAIN_COMMIT = "2" * 40
RUNTIME_ROOTS = {
    "8": "jdk8u504-b01-jre",
    "17": "jdk-17.0.0+fixture-jre",
    "21": "jdk-21.0.0+fixture-jre",
}
RUNTIME_ROOT = RUNTIME_ROOTS[package_release.DEFAULT_JAVA_VERSION]
FIXTURE_JAVA_VERSIONS = {
    "8": "8u0-fixture",
    "17": "17.0.0+fixture",
    "21": "21.0.0+fixture",
}


class ReleaseFixture:
    def __init__(self, base: Path) -> None:
        self.repository = base / "repository"
        self.output = base / "output"
        self.repository.mkdir()
        self._write_sources()
        self.jar = self.repository / "fixture.jar"
        self._write_jar()

    def _put(self, relative: str, content: str) -> None:
        path = self.repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")

    def _write_sources(self) -> None:
        for name in package_release.LAUNCHERS:
            self._put(f"scripts/{name}", f"fixture launcher {name}\n")
        self._put("LICENSE", "harness license\n")
        self._put("THIRD_PARTY_NOTICES.md", "harness notices\n")
        self._put("README.md", "repository readme must not be packaged\n")
        self._put("docs/downloads.md", "download release readme\n")
        for name in package_release.REFERENCE_DOCUMENTS:
            self._put(name, f"public document {name}\n")
        for name in package_release.EXAMPLE_FILES:
            self._put(name, f"synthetic example file {name}\n")

        # These inputs must never enter an allowlist-driven release, including
        # ignored runtime output nested inside the public example directory.
        self._put("robot-programs/private.lua", "secret robot program\n")
        self._put(".cache/raw-evidence.txt", "private evidence\n")
        self._put("docs/README.md", "source-only docs index\n")
        self._put("docs/history/internal.md", "not a release reference\n")
        self._put("examples/private-project/token.txt", "private example\n")
        self._put(
            "examples/two-computers/.ocelot-harness/worlds/private.nbt",
            "ignored emulated world\n",
        )
        self._put("examples/two-computers/raw-evidence/token.txt", "private evidence\n")

    def _write_jar(self, *, dirty: bool = False) -> None:
        with zipfile.ZipFile(self.jar, "w") as jar:
            jar.writestr("application.class", b"fixture bytecode")
            jar.writestr(
                "ocelot-harness-build.properties",
                f"harness.commit={SOURCE_COMMIT}\nharness.dirty={'true' if dirty else 'false'}\n",
            )

    def set_pin(
        self,
        platform: str,
        archive: Path,
        java_version: str = package_release.DEFAULT_JAVA_VERSION,
    ) -> None:
        sha256 = hashlib.sha256(archive.read_bytes()).hexdigest()
        suffix = ".zip" if platform == "windows-x64" else ".tar.gz"
        path = self.repository / "project/runtime-distributions.json"
        if path.exists():
            pins = json.loads(path.read_text(encoding="utf-8"))
        else:
            pins = {"schemaVersion": 2, "runtimes": {}}
        pins["runtimes"].setdefault(java_version, {})[platform] = {
            "distribution": "Fixture Temurin",
            "implementation": "HotSpot",
            "javaVersion": FIXTURE_JAVA_VERSIONS[java_version],
            "archive": f"fixture-java{java_version}-jre{suffix}",
            "archiveRoot": RUNTIME_ROOTS[java_version],
            "url": f"https://example.invalid/fixture-java{java_version}-jre{suffix}",
            "sha256": sha256,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(pins), encoding="utf-8")

    def package(
        self,
        platform: str,
        runtime: Path,
        output: Path | None = None,
        java_version: str = package_release.DEFAULT_JAVA_VERSION,
    ) -> Path:
        return package_release.package_release(
            repository_root=self.repository,
            platform=platform,
            java_version=java_version,
            runtime_archive=runtime,
            output_dir=output or self.output,
            jar_path=self.jar,
            source_commit=SOURCE_COMMIT,
            brain_commit=BRAIN_COMMIT,
        )


def _tar_entry(name: str, data: bytes | None = None, mode: int = 0o644) -> tuple[tarfile.TarInfo, io.BytesIO | None]:
    info = tarfile.TarInfo(name)
    info.mtime = 123456789
    info.mode = mode
    if data is None:
        info.type = tarfile.DIRTYPE
        return info, None
    info.size = len(data)
    return info, io.BytesIO(data)


def make_linux_runtime(
    path: Path,
    *,
    java_version: str = package_release.DEFAULT_JAVA_VERSION,
    extra_entries=(),
    links=(),
) -> None:
    runtime_root = RUNTIME_ROOTS[java_version]
    with tarfile.open(path, "w:gz") as archive:
        entries = (
            _tar_entry(f"{runtime_root}/"),
            _tar_entry(f"{runtime_root}/ASSEMBLY_EXCEPTION", b"assembly exception\n"),
            _tar_entry(f"{runtime_root}/LICENSE", b"runtime license\n"),
            _tar_entry(f"{runtime_root}/NOTICE", b"runtime notice\n"),
            _tar_entry(f"{runtime_root}/bin/"),
            _tar_entry(f"{runtime_root}/bin/java", b"java fixture\n", 0o755),
            _tar_entry(f"{runtime_root}/lib/"),
            _tar_entry(f"{runtime_root}/lib/amd64/"),
            _tar_entry(f"{runtime_root}/lib/amd64/libjsig.so", b"libjsig fixture\n", 0o755),
            _tar_entry(f"{runtime_root}/lib/amd64/server/"),
            _tar_entry(f"{runtime_root}/man/"),
            _tar_entry(f"{runtime_root}/man/ja_JP.UTF-8/"),
        )
        for info, stream in (*entries, *extra_entries):
            archive.addfile(info, stream)
        default_links = (
            (f"{runtime_root}/man/ja", "ja_JP.UTF-8"),
            (f"{runtime_root}/lib/amd64/server/libjsig.so", "../libjsig.so"),
        )
        for name, target in (*default_links, *links):
            info = tarfile.TarInfo(name)
            info.type = tarfile.SYMTYPE
            info.mode = 0o777
            info.linkname = target
            archive.addfile(info)


def _zip_write(archive: zipfile.ZipFile, name: str, data: bytes, mode: int) -> None:
    info = zipfile.ZipInfo(name, (2020, 1, 2, 3, 4, 6))
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | mode) << 16
    archive.writestr(info, data)


def make_windows_runtime(
    path: Path,
    *,
    java_version: str = package_release.DEFAULT_JAVA_VERSION,
    unsafe_name: str | None = None,
) -> None:
    runtime_root = RUNTIME_ROOTS[java_version]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        _zip_write(archive, f"{runtime_root}/ASSEMBLY_EXCEPTION", b"assembly exception\n", 0o544)
        _zip_write(archive, f"{runtime_root}/LICENSE", b"runtime license\n", 0o544)
        _zip_write(archive, f"{runtime_root}/NOTICE", b"runtime notice\n", 0o544)
        _zip_write(archive, f"{runtime_root}/bin/java.exe", b"java fixture\n", 0o775)
        if unsafe_name is not None:
            _zip_write(archive, unsafe_name, b"escape\n", 0o644)


class PackageReleaseTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.fixture = ReleaseFixture(self.base)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_published_runtime_pins_are_exact(self) -> None:
        path = Path(__file__).resolve().parent.parent / "project/runtime-distributions.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(document["schemaVersion"], 2)
        pins = document["runtimes"]
        expected_sources = {
            "8": (
                "jdk8u504-b01-src",
                "86cd14f299616dddca13268cc2fa794eb4d28fc732dedaad8c5b8a3078e5d3c9",
            ),
            "17": (
                "jdk-17.0.20.1+1-src",
                "21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214",
            ),
            "21": (
                "jdk-21.0.12.1+1-src",
                "573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234",
            ),
        }
        self.assertEqual(set(document["sources"]), set(package_release.JAVA_VERSIONS))
        for java_version, values in expected_sources.items():
            source = document["sources"][java_version]
            self.assertEqual((source["archiveRoot"], source["sha256"]), values)
            self.assertIn(source["archive"], source["url"])

        expected = {
            ("8", "linux-x64"): (
                "8u504-b01",
                "jdk8u504-b01-jre",
                "52dcd578baca1d3e449ea86768a9129c0ee04d7b22565695498353cc66940c61",
            ),
            ("8", "windows-x64"): (
                "8u504-b01",
                "jdk8u504-b01-jre",
                "82e2cdc6693737c5998445b31f69668fa0da77c7705121053f6508ac84961123",
            ),
            ("17", "linux-x64"): (
                "17.0.20.1+1",
                "jdk-17.0.20.1+1-jre",
                "0b2b640e3046b64c8ec504de0ab9d91bb5610182bda21fad454681ce54d45a62",
            ),
            ("17", "windows-x64"): (
                "17.0.20.1+1",
                "jdk-17.0.20.1+1-jre",
                "bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352",
            ),
            ("21", "linux-x64"): (
                "21.0.12.1+1",
                "jdk-21.0.12.1+1-jre",
                "2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500",
            ),
            ("21", "windows-x64"): (
                "21.0.12.1+1",
                "jdk-21.0.12.1+1-jre",
                "d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636",
            ),
        }
        self.assertEqual(set(pins), set(package_release.JAVA_VERSIONS))
        for (java_version, platform), values in expected.items():
            pin = pins[java_version][platform]
            self.assertEqual(
                (pin["javaVersion"], pin["archiveRoot"], pin["sha256"]), values
            )
            self.assertIn(pin["archive"], pin["url"])

    def test_all_java_versions_and_platforms_have_versioned_names_and_manifest_identity(self) -> None:
        for java_version in package_release.JAVA_VERSIONS:
            for platform in package_release.PLATFORMS:
                with self.subTest(java_version=java_version, platform=platform):
                    suffix = ".zip" if platform == "windows-x64" else ".tar.gz"
                    runtime = self.base / f"runtime-java{java_version}-{platform}{suffix}"
                    if platform == "windows-x64":
                        make_windows_runtime(runtime, java_version=java_version)
                    else:
                        make_linux_runtime(runtime, java_version=java_version)
                    self.fixture.set_pin(platform, runtime, java_version)
                    result = self.fixture.package(platform, runtime, java_version=java_version)
                    root = f"ocelot-harness-0.1.1-{platform}-java{java_version}"
                    self.assertEqual(result.name, root + suffix)

                    if platform == "windows-x64":
                        with zipfile.ZipFile(result) as archive:
                            manifest = json.loads(
                                archive.read(f"{root}/RELEASE-MANIFEST.json")
                            )
                    else:
                        with tarfile.open(result, "r:gz") as archive:
                            stream = archive.extractfile(f"{root}/RELEASE-MANIFEST.json")
                            assert stream is not None
                            manifest = json.load(stream)
                    self.assertEqual(manifest["platform"], platform)
                    self.assertEqual(manifest["source"]["commit"], SOURCE_COMMIT)
                    self.assertEqual(
                        manifest["source"]["ocelotBrainCommit"], BRAIN_COMMIT
                    )
                    self.assertEqual(manifest["runtime"]["javaMajor"], java_version)
                    self.assertEqual(
                        manifest["runtime"]["javaVersion"],
                        FIXTURE_JAVA_VERSIONS[java_version],
                    )
                    self.assertEqual(
                        manifest["runtime"]["archiveRoot"], RUNTIME_ROOTS[java_version]
                    )
                    self.assertEqual(
                        manifest["runtime"]["sha256"],
                        hashlib.sha256(runtime.read_bytes()).hexdigest(),
                    )

    def test_cli_defaults_to_java_21(self) -> None:
        options = package_release._parse_arguments(
            [
                "--platform",
                "linux-x64",
                "--runtime-archive",
                "runtime.tar.gz",
                "--output",
                "output",
            ]
        )
        self.assertEqual(options.java_version, "21")

    def test_wrong_java_major_and_platform_runtime_archives_are_rejected(self) -> None:
        java8_linux = self.base / "java8-linux.tar.gz"
        java17_linux = self.base / "java17-linux.tar.gz"
        java17_windows = self.base / "java17-windows.zip"
        make_linux_runtime(java8_linux, java_version="8")
        make_linux_runtime(java17_linux, java_version="17")
        make_windows_runtime(java17_windows, java_version="17")
        self.fixture.set_pin("linux-x64", java8_linux, "8")
        self.fixture.set_pin("linux-x64", java17_linux, "17")
        self.fixture.set_pin("windows-x64", java17_windows, "17")

        with self.assertRaisesRegex(package_release.PackagingError, "SHA-256 mismatch"):
            self.fixture.package("linux-x64", java8_linux, java_version="17")
        with self.assertRaisesRegex(package_release.PackagingError, "SHA-256 mismatch"):
            self.fixture.package("windows-x64", java17_linux, java_version="17")

        pins_path = self.fixture.repository / "project/runtime-distributions.json"
        pins = json.loads(pins_path.read_text(encoding="utf-8"))
        pins["runtimes"]["17"]["windows-x64"]["javaVersion"] = "21.0.0+wrong"
        pins_path.write_text(json.dumps(pins), encoding="utf-8")
        with self.assertRaisesRegex(package_release.PackagingError, "different Java major"):
            self.fixture.package("windows-x64", java17_windows, java_version="17")

    def test_linux_archive_has_expected_allowlisted_layout_notices_modes_and_links(self) -> None:
        runtime = self.base / "runtime.tar.gz"
        make_linux_runtime(runtime)
        self.fixture.set_pin("linux-x64", runtime)
        result = self.fixture.package("linux-x64", runtime)
        root = "ocelot-harness-0.1.1-linux-x64-java21"

        with tarfile.open(result, "r:gz") as archive:
            members = {member.name.rstrip("/"): member for member in archive.getmembers()}
            expected = {
                f"{root}/bin/{name}" for name in package_release.LAUNCHERS
            } | {
                f"{root}/lib/ocelot-harness.jar",
                f"{root}/runtime/bin/java",
                f"{root}/runtime/LICENSE",
                f"{root}/runtime/NOTICE",
                f"{root}/runtime/ASSEMBLY_EXCEPTION",
                f"{root}/LICENSE",
                f"{root}/THIRD_PARTY_NOTICES.md",
                f"{root}/README.md",
                f"{root}/RELEASE-MANIFEST.json",
                f"{root}/docs/reference/cli.md",
                f"{root}/docs/reference/manifest-v2.md",
                f"{root}/docs/reference/viewer.md",
                f"{root}/docs/reference/desktop-import.md",
                *(f"{root}/{name}" for name in package_release.EXAMPLE_FILES),
            }
            self.assertTrue(expected.issubset(members))
            all_names = set(members)
            self.assertFalse(any("robot-programs" in name for name in all_names))
            self.assertFalse(any(".cache" in name for name in all_names))
            self.assertFalse(any("private-project" in name for name in all_names))
            self.assertFalse(any("docs/history" in name for name in all_names))
            self.assertFalse(any(".ocelot-harness" in name for name in all_names))
            self.assertFalse(any("raw-evidence" in name for name in all_names))
            self.assertNotIn(f"{root}/docs/README.md", all_names)
            readme_stream = archive.extractfile(members[f"{root}/README.md"])
            assert readme_stream is not None
            self.assertEqual(readme_stream.read(), b"download release readme\n")
            self.assertEqual(members[f"{root}/bin/ocelotctl"].mode, 0o755)
            self.assertEqual(members[f"{root}/bin/ocelotctl.cmd"].mode, 0o644)
            self.assertEqual(members[f"{root}/runtime/bin/java"].mode, 0o755)
            self.assertTrue(members[f"{root}/runtime/man/ja"].issym())
            self.assertEqual(members[f"{root}/runtime/man/ja"].linkname, "ja_JP.UTF-8")
            self.assertTrue(members[f"{root}/runtime/lib/amd64/server/libjsig.so"].issym())
            self.assertEqual(
                members[f"{root}/runtime/lib/amd64/server/libjsig.so"].linkname,
                "../libjsig.so",
            )
            manifest_stream = archive.extractfile(members[f"{root}/RELEASE-MANIFEST.json"])
            assert manifest_stream is not None
            manifest = json.load(manifest_stream)

        self.assertEqual(manifest["source"]["commit"], SOURCE_COMMIT)
        self.assertEqual(manifest["source"]["ocelotBrainCommit"], BRAIN_COMMIT)
        self.assertNotIn(str(self.fixture.repository), json.dumps(manifest))
        inventory = {entry["path"]: entry for entry in manifest["files"]}
        self.assertEqual(inventory["runtime/bin/java"]["mode"], "0755")
        self.assertEqual(inventory["runtime/man/ja"]["target"], "ja_JP.UTF-8")
        self.assertRegex(inventory["lib/ocelot-harness.jar"]["sha256"], r"^[0-9a-f]{64}$")
        sidecar = Path(f"{result}.sha256").read_text(encoding="ascii")
        self.assertEqual(sidecar, f"{hashlib.sha256(result.read_bytes()).hexdigest()}  {result.name}\n")

    def test_windows_zip_has_one_root_and_preserves_runtime_and_launcher_modes(self) -> None:
        runtime = self.base / "runtime.zip"
        make_windows_runtime(runtime)
        self.fixture.set_pin("windows-x64", runtime)
        result = self.fixture.package("windows-x64", runtime)
        root = "ocelot-harness-0.1.1-windows-x64-java21"

        with zipfile.ZipFile(result) as archive:
            names = set(archive.namelist())
            self.assertIn(f"{root}/runtime/bin/java.exe", names)
            self.assertIn(f"{root}/runtime/LICENSE", names)
            self.assertIn(f"{root}/runtime/NOTICE", names)
            self.assertIn(f"{root}/runtime/ASSEMBLY_EXCEPTION", names)
            self.assertIn(f"{root}/bin/run-harness.cmd", names)
            self.assertTrue(all(name == f"{root}/" or name.startswith(f"{root}/") for name in names))
            java_mode = (archive.getinfo(f"{root}/runtime/bin/java.exe").external_attr >> 16) & 0o777
            shell_mode = (archive.getinfo(f"{root}/bin/run-harness").external_attr >> 16) & 0o777
            cmd_mode = (archive.getinfo(f"{root}/bin/run-harness.cmd").external_attr >> 16) & 0o777
            self.assertEqual(java_mode, 0o775)
            self.assertEqual(shell_mode, 0o755)
            self.assertEqual(cmd_mode, 0o644)

    def test_read_only_runtime_modes_are_preserved_without_leaving_staging(self) -> None:
        runtime = self.base / "runtime.zip"
        make_windows_runtime(runtime)
        self.fixture.set_pin("windows-x64", runtime)
        result = self.fixture.package("windows-x64", runtime)
        root = "ocelot-harness-0.1.1-windows-x64-java21"

        self.assertEqual(
            list(self.fixture.output.glob(".ocelot-release-stage-*")),
            [],
            "output-local staging must be removed on Windows",
        )
        with zipfile.ZipFile(result) as archive:
            license_info = archive.getinfo(f"{root}/runtime/LICENSE")
            self.assertEqual((license_info.external_attr >> 16) & 0o777, 0o544)
            self.assertEqual(archive.read(license_info), b"runtime license\n")

    def test_private_outputs_nested_in_example_are_not_packaged(self) -> None:
        runtime = self.base / "runtime.zip"
        make_windows_runtime(runtime)
        self.fixture.set_pin("windows-x64", runtime)
        result = self.fixture.package("windows-x64", runtime)

        with zipfile.ZipFile(result) as archive:
            names = archive.namelist()
        self.assertFalse(any(".ocelot-harness" in name for name in names))
        self.assertFalse(any("raw-evidence" in name for name in names))
        for relative in package_release.EXAMPLE_FILES:
            self.assertTrue(any(name.endswith("/" + relative) for name in names), relative)

    def test_git_timeout_becomes_bounded_packaging_error(self) -> None:
        with mock.patch.object(
            package_release.subprocess,
            "run",
            side_effect=subprocess.TimeoutExpired(cmd=("git",), timeout=15),
        ):
            with self.assertRaisesRegex(package_release.PackagingError, "timed out"):
                package_release._git_output(self.fixture.repository, "status")

    def test_checksum_failure_happens_before_archive_parsing(self) -> None:
        runtime = self.base / "not-an-archive.tar.gz"
        runtime.write_bytes(b"not a tar archive")
        self.fixture.set_pin("linux-x64", runtime)
        pins_path = self.fixture.repository / "project/runtime-distributions.json"
        pins = json.loads(pins_path.read_text(encoding="utf-8"))
        pins["runtimes"]["21"]["linux-x64"]["sha256"] = "0" * 64
        pins_path.write_text(json.dumps(pins), encoding="utf-8")

        with self.assertRaisesRegex(package_release.PackagingError, "SHA-256 mismatch"):
            self.fixture.package("linux-x64", runtime)
        self.assertFalse(self.fixture.output.exists() and any(self.fixture.output.iterdir()))

    def test_runtime_traversal_and_escaping_links_are_rejected_inside_staging(self) -> None:
        traversal = self.base / "traversal.tar.gz"
        make_linux_runtime(
            traversal,
            extra_entries=(_tar_entry(f"{RUNTIME_ROOT}/../escaped.txt", b"escape\n"),),
        )
        self.fixture.set_pin("linux-x64", traversal)
        with self.assertRaisesRegex(package_release.PackagingError, "unsafe runtime archive path"):
            self.fixture.package("linux-x64", traversal)
        self.assertFalse((self.base / "escaped.txt").exists())

        escaping_link = self.base / "escaping-link.tar.gz"
        make_linux_runtime(escaping_link, links=((f"{RUNTIME_ROOT}/bad-link", "../outside"),))
        self.fixture.set_pin("linux-x64", escaping_link)
        with self.assertRaisesRegex(package_release.PackagingError, "escapes runtime root"):
            self.fixture.package("linux-x64", escaping_link)
        self.assertFalse((self.base / "outside").exists())

    def test_windows_backslash_traversal_is_rejected(self) -> None:
        runtime = self.base / "unsafe.zip"
        make_windows_runtime(runtime, unsafe_name=f"{RUNTIME_ROOT}\\..\\escaped.txt")
        self.fixture.set_pin("windows-x64", runtime)
        with self.assertRaisesRegex(package_release.PackagingError, "unsafe runtime archive path"):
            self.fixture.package("windows-x64", runtime)
        self.assertFalse((self.base / "escaped.txt").exists())

    def test_rebuild_is_byte_deterministic_and_owned_output_may_be_replaced(self) -> None:
        runtime = self.base / "runtime.tar.gz"
        make_linux_runtime(runtime)
        self.fixture.set_pin("linux-x64", runtime)
        first = self.fixture.package("linux-x64", runtime)
        first_bytes = first.read_bytes()
        first_sidecar = Path(f"{first}.sha256").read_bytes()

        second = self.fixture.package("linux-x64", runtime)
        self.assertEqual(second, first)
        self.assertEqual(second.read_bytes(), first_bytes)
        self.assertEqual(Path(f"{second}.sha256").read_bytes(), first_sidecar)

    def test_unrelated_existing_output_and_dirty_jar_are_rejected(self) -> None:
        runtime = self.base / "runtime.zip"
        make_windows_runtime(runtime)
        self.fixture.set_pin("windows-x64", runtime)
        self.fixture.output.mkdir()
        unrelated = self.fixture.output / "ocelot-harness-0.1.1-windows-x64-java21.zip"
        unrelated.write_bytes(b"do not overwrite")
        with self.assertRaisesRegex(package_release.PackagingError, "refusing to overwrite"):
            self.fixture.package("windows-x64", runtime)
        self.assertEqual(unrelated.read_bytes(), b"do not overwrite")

        clean_output = self.base / "dirty-output"
        self.fixture._write_jar(dirty=True)
        with self.assertRaisesRegex(package_release.PackagingError, "dirty"):
            self.fixture.package("windows-x64", runtime, clean_output)
        self.assertFalse(clean_output.exists())


if __name__ == "__main__":
    unittest.main()
