#!/usr/bin/env python3
"""Build deterministic, offline Ocelot Harness release archives.

The runtime archive must already be present locally.  This tool never downloads it and
verifies its pinned SHA-256 before opening or extracting it.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import posixpath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable, Mapping, Sequence


DEFAULT_VERSION = "0.1.1"
DEFAULT_JAVA_VERSION = "21"
JAVA_VERSIONS = ("8", "17", "21")
PLATFORMS = ("windows-x64", "linux-x64")
MAX_RUNTIME_FILES = 10_000
MAX_RUNTIME_BYTES = 1_000_000_000
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)

LAUNCHERS = (
    "ocelotctl",
    "ocelot-harnessd",
    "ocelot-viewer",
    "ocelotctl.cmd",
    "ocelot-harnessd.cmd",
    "ocelot-viewer.cmd",
    "run-harness",
    "run-harness.cmd",
    "java-common.sh",
    "java-common.ps1",
)
ROOT_FILE_COPIES = (
    ("LICENSE", "LICENSE"),
    ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
    ("docs/downloads.md", "README.md"),
)
REFERENCE_DOCUMENTS = (
    "docs/reference/cli.md",
    "docs/reference/manifest-v2.md",
    "docs/reference/viewer.md",
    "docs/reference/desktop-import.md",
    "docs/release/dependencies.md",
    "docs/release/sbom.cdx.json",
)
EXAMPLE_FILES = (
    "examples/two-computers/README.md",
    "examples/two-computers/alpha/computer/main.lua",
    "examples/two-computers/alpha/firmware/project-loader.lua",
    "examples/two-computers/alpha/init.lua",
    "examples/two-computers/beta/computer/main.lua",
    "examples/two-computers/beta/firmware/project-loader.lua",
    "examples/two-computers/beta/init.lua",
    "examples/two-computers/ocelot-harness.conf",
)
HEX_COMMIT = re.compile(r"[0-9a-f]{40}\Z")
SAFE_VERSION = re.compile(r"[0-9A-Za-z][0-9A-Za-z._-]*\Z")


class PackagingError(RuntimeError):
    """A safe, user-facing packaging failure."""


@dataclass(frozen=True)
class RuntimePin:
    platform: str
    java_major: str
    distribution: str
    implementation: str
    java_version: str
    archive: str
    archive_root: str
    url: str
    sha256: str


@dataclass(frozen=True)
class VirtualSymlink:
    path: PurePosixPath
    target: str
    mode: int = 0o777


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _safe_archive_parts(name: str) -> tuple[str, ...]:
    if not name or "\x00" in name or "\\" in name or name.startswith("/"):
        raise PackagingError(f"unsafe runtime archive path: {name!r}")
    trimmed = name[:-1] if name.endswith("/") else name
    parts = tuple(trimmed.split("/"))
    if not trimmed or any(part in ("", ".", "..") for part in parts):
        raise PackagingError(f"unsafe runtime archive path: {name!r}")
    if re.match(r"^[A-Za-z]:", parts[0]):
        raise PackagingError(f"unsafe runtime archive path: {name!r}")
    return parts


def _runtime_relative(name: str, expected_root: str) -> PurePosixPath | None:
    parts = _safe_archive_parts(name)
    if parts[0] != expected_root:
        raise PackagingError(
            f"runtime archive entry is outside expected root {expected_root!r}: {name!r}"
        )
    if len(parts) == 1:
        return None
    return PurePosixPath(*parts[1:])


def _validate_link(path: PurePosixPath, target: str) -> None:
    if not target or "\x00" in target or "\\" in target or target.startswith("/"):
        raise PackagingError(f"unsafe runtime symlink target at {path}: {target!r}")
    target_parts = target.split("/")
    if any(part == "" for part in target_parts):
        raise PackagingError(f"unsafe runtime symlink target at {path}: {target!r}")
    resolved = posixpath.normpath(posixpath.join(path.parent.as_posix(), target))
    if resolved == ".." or resolved.startswith("../") or resolved.startswith("/"):
        raise PackagingError(f"runtime symlink escapes runtime root at {path}: {target!r}")


def _validate_member_graph(paths: Iterable[PurePosixPath], symlink_paths: set[PurePosixPath]) -> None:
    for path in paths:
        for parent in path.parents:
            if parent == PurePosixPath("."):
                break
            if parent in symlink_paths:
                raise PackagingError(f"runtime archive entry descends through symlink: {path}")


def _load_runtime_pin(repository_root: Path, platform: str, java_version: str) -> RuntimePin:
    pins_path = repository_root / "project" / "runtime-distributions.json"
    try:
        data = json.loads(pins_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PackagingError(f"cannot read runtime pins from {pins_path}: {error}") from error
    if data.get("schemaVersion") != 2 or not isinstance(data.get("runtimes"), dict):
        raise PackagingError("unsupported runtime-distributions.json schema")
    try:
        item = data["runtimes"][java_version][platform]
        pin = RuntimePin(
            platform=platform,
            java_major=java_version,
            distribution=item["distribution"],
            implementation=item["implementation"],
            java_version=item["javaVersion"],
            archive=item["archive"],
            archive_root=item["archiveRoot"],
            url=item["url"],
            sha256=item["sha256"].lower(),
        )
    except (KeyError, TypeError, AttributeError) as error:
        raise PackagingError(
            f"runtime pin for Java {java_version} on {platform} is incomplete"
        ) from error
    if not re.fullmatch(r"[0-9a-f]{64}", pin.sha256):
        raise PackagingError(
            f"runtime pin for Java {java_version} on {platform} has an invalid SHA-256"
        )
    if len(_safe_archive_parts(pin.archive_root)) != 1:
        raise PackagingError(
            f"runtime pin for Java {java_version} on {platform} has an invalid archive root"
        )
    if not re.match(rf"^{re.escape(java_version)}(?:[u.]|$)", pin.java_version):
        raise PackagingError(
            f"runtime pin for Java {java_version} on {platform} identifies a different Java major"
        )
    return pin


def _extract_tar_runtime(
    archive: Path, destination: Path, pin: RuntimePin
) -> tuple[list[VirtualSymlink], dict[PurePosixPath, int]]:
    try:
        source = tarfile.open(archive, mode="r:gz")
    except (OSError, tarfile.TarError) as error:
        raise PackagingError(f"invalid pinned Linux runtime archive: {error}") from error

    with source:
        entries: list[tuple[tarfile.TarInfo, PurePosixPath | None]] = []
        seen: set[PurePosixPath] = set()
        symlink_paths: set[PurePosixPath] = set()
        total_size = 0
        for member in source.getmembers():
            relative = _runtime_relative(member.name, pin.archive_root)
            if relative is None:
                if not member.isdir():
                    raise PackagingError("runtime archive root must be a directory")
                continue
            if relative in seen:
                raise PackagingError(f"duplicate runtime archive entry: {relative}")
            seen.add(relative)
            if member.isfile():
                total_size += member.size
            elif member.isdir():
                pass
            elif member.issym():
                _validate_link(relative, member.linkname)
                symlink_paths.add(relative)
            else:
                raise PackagingError(f"unsupported runtime archive entry type: {member.name}")
            entries.append((member, relative))

        if len(entries) > MAX_RUNTIME_FILES or total_size > MAX_RUNTIME_BYTES:
            raise PackagingError("runtime archive exceeds extraction limits")
        _validate_member_graph((path for _, path in entries if path is not None), symlink_paths)

        virtual_links: list[VirtualSymlink] = []
        file_modes: dict[PurePosixPath, int] = {}
        for member, relative in entries:
            assert relative is not None
            target = destination.joinpath(*relative.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                extracted = source.extractfile(member)
                if extracted is None:
                    raise PackagingError(f"cannot read runtime archive entry: {member.name}")
                with extracted, target.open("wb") as output:
                    shutil.copyfileobj(extracted, output)
                mode = member.mode & 0o777
                file_modes[PurePosixPath("runtime") / relative] = mode
            else:
                virtual_links.append(
                    VirtualSymlink(PurePosixPath("runtime") / relative, member.linkname, member.mode & 0o777)
                )
        return virtual_links, file_modes


def _zip_mode(info: zipfile.ZipInfo, default: int) -> int:
    mode = (info.external_attr >> 16) & 0xFFFF
    return mode & 0o777 if mode else default


def _extract_zip_runtime(
    archive: Path, destination: Path, pin: RuntimePin
) -> tuple[list[VirtualSymlink], dict[PurePosixPath, int]]:
    try:
        source = zipfile.ZipFile(archive, mode="r")
    except (OSError, zipfile.BadZipFile) as error:
        raise PackagingError(f"invalid pinned Windows runtime archive: {error}") from error

    with source:
        entries: list[tuple[zipfile.ZipInfo, PurePosixPath | None]] = []
        seen: set[PurePosixPath] = set()
        total_size = 0
        for info in source.infolist():
            relative = _runtime_relative(info.filename, pin.archive_root)
            if relative is None:
                if not info.is_dir():
                    raise PackagingError("runtime archive root must be a directory")
                continue
            if relative in seen:
                raise PackagingError(f"duplicate runtime archive entry: {relative}")
            seen.add(relative)
            mode_type = stat.S_IFMT((info.external_attr >> 16) & 0xFFFF)
            if info.is_dir():
                pass
            elif mode_type not in (0, stat.S_IFREG):
                raise PackagingError(f"unsupported Windows runtime archive entry type: {info.filename}")
            else:
                total_size += info.file_size
            entries.append((info, relative))

        if len(entries) > MAX_RUNTIME_FILES or total_size > MAX_RUNTIME_BYTES:
            raise PackagingError("runtime archive exceeds extraction limits")

        file_modes: dict[PurePosixPath, int] = {}
        for info, relative in entries:
            assert relative is not None
            target = destination.joinpath(*relative.parts)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                try:
                    with source.open(info, "r") as input_stream, target.open("wb") as output:
                        shutil.copyfileobj(input_stream, output)
                except (OSError, zipfile.BadZipFile) as error:
                    raise PackagingError(f"cannot read runtime archive entry {info.filename}: {error}") from error
                mode = _zip_mode(info, 0o644)
                file_modes[PurePosixPath("runtime") / relative] = mode
    return [], file_modes


def _extract_runtime(
    archive: Path, destination: Path, pin: RuntimePin
) -> tuple[list[VirtualSymlink], dict[PurePosixPath, int]]:
    if not archive.is_file():
        raise PackagingError(f"runtime archive is not a regular file: {archive}")
    actual_hash = _sha256(archive)
    if actual_hash != pin.sha256:
        raise PackagingError(
            f"runtime archive SHA-256 mismatch for {pin.platform}: "
            f"expected {pin.sha256}, got {actual_hash}"
        )
    destination.mkdir(mode=0o755)
    if pin.platform == "linux-x64":
        return _extract_tar_runtime(archive, destination, pin)
    return _extract_zip_runtime(archive, destination, pin)


def _require_source_file(repository_root: Path, relative: PurePosixPath) -> Path:
    source = repository_root.joinpath(*relative.parts)
    try:
        source.resolve(strict=True).relative_to(repository_root.resolve(strict=True))
    except (OSError, ValueError) as error:
        raise PackagingError(f"required release input escapes repository: {relative}") from error
    if source.is_symlink() or not source.is_file():
        raise PackagingError(f"required release input is not a regular file: {relative}")
    return source


def _copy_file(source: Path, destination: Path, mode: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(mode)


def _read_jar_identity(jar_path: Path) -> Mapping[str, str]:
    if jar_path.is_symlink() or not jar_path.is_file():
        raise PackagingError(f"application JAR is not a regular file: {jar_path}")
    try:
        with zipfile.ZipFile(jar_path, "r") as jar:
            raw = jar.read("ocelot-harness-build.properties").decode("utf-8")
    except (OSError, KeyError, UnicodeDecodeError, zipfile.BadZipFile) as error:
        raise PackagingError(f"application JAR has no valid build identity: {error}") from error
    properties: dict[str, str] = {}
    for line in raw.splitlines():
        line = line.strip()
        if line and not line.startswith(('#', '!')) and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


def _git_output(repository_root: Path, *arguments: str) -> str:
    try:
        completed = subprocess.run(
            ("git", "-C", str(repository_root), *arguments),
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=15,
        )
    except subprocess.TimeoutExpired as error:
        raise PackagingError("timed out determining release source identity") from error
    except subprocess.CalledProcessError as error:
        raise PackagingError(
            f"cannot determine release source identity: {error.stderr.strip()}"
        ) from error
    except OSError as error:
        raise PackagingError(f"cannot determine release source identity: {error}") from error
    return completed.stdout.strip()


def _source_identities(repository_root: Path) -> tuple[str, str]:
    source_commit = _git_output(repository_root, "rev-parse", "HEAD")
    submodule_line = _git_output(repository_root, "ls-tree", "HEAD", "--", "lib/ocelot-brain")
    fields = submodule_line.split()
    if not HEX_COMMIT.fullmatch(source_commit) or len(fields) < 3 or fields[0] != "160000":
        raise PackagingError("repository or pinned ocelot-brain source identity is invalid")
    brain_commit = fields[2]
    if not HEX_COMMIT.fullmatch(brain_commit):
        raise PackagingError("pinned ocelot-brain commit is invalid")
    return source_commit, brain_commit


def _verify_release_inputs_clean(repository_root: Path) -> None:
    release_inputs = [
        *(f"scripts/{launcher}" for launcher in LAUNCHERS),
        *(source for source, _ in ROOT_FILE_COPIES),
        *REFERENCE_DOCUMENTS,
        *EXAMPLE_FILES,
        "project/runtime-distributions.json",
        "lib/ocelot-brain",
    ]
    status = _git_output(
        repository_root,
        "status",
        "--porcelain",
        "--untracked-files=all",
        "--ignore-submodules=none",
        "--",
        *release_inputs,
    )
    if status:
        changed = ", ".join(line[3:] for line in status.splitlines())
        raise PackagingError(f"release inputs differ from the source commit: {changed}")


def _validate_commit(value: str, label: str) -> str:
    value = value.lower()
    if not HEX_COMMIT.fullmatch(value):
        raise PackagingError(f"invalid {label}: {value!r}")
    return value


def _file_inventory(
    root: Path,
    virtual_links: Sequence[VirtualSymlink],
    file_modes: Mapping[PurePosixPath, int],
) -> list[dict[str, object]]:
    inventory: list[dict[str, object]] = []
    for path in sorted((item for item in root.rglob("*") if item.is_file()), key=lambda item: item.as_posix()):
        relative_path = PurePosixPath(path.relative_to(root).as_posix())
        inventory.append(
            {
                "path": relative_path.as_posix(),
                "type": "file",
                "mode": f"{file_modes.get(relative_path, 0o644):04o}",
                "sha256": _sha256(path),
            }
        )
    for link in virtual_links:
        inventory.append(
            {
                "path": link.path.as_posix(),
                "type": "symlink",
                "mode": f"{link.mode:04o}",
                "target": link.target,
            }
        )
    inventory.sort(key=lambda item: str(item["path"]))
    return inventory


def _write_manifest(
    release_root: Path,
    version: str,
    platform: str,
    source_commit: str,
    brain_commit: str,
    pin: RuntimePin,
    virtual_links: Sequence[VirtualSymlink],
    file_modes: Mapping[PurePosixPath, int],
) -> None:
    manifest = {
        "schemaVersion": 1,
        "name": "ocelot-harness",
        "version": version,
        "platform": platform,
        "source": {
            "commit": source_commit,
            "ocelotBrainCommit": brain_commit,
        },
        "runtime": {
            "distribution": pin.distribution,
            "implementation": pin.implementation,
            "javaMajor": pin.java_major,
            "javaVersion": pin.java_version,
            "archive": pin.archive,
            "archiveRoot": pin.archive_root,
            "url": pin.url,
            "sha256": pin.sha256,
        },
        "files": _file_inventory(release_root, virtual_links, file_modes),
    }
    output = release_root / "RELEASE-MANIFEST.json"
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    output.chmod(0o644)


def _stage_release(
    repository_root: Path,
    release_root: Path,
    jar_path: Path,
    runtime_archive: Path,
    pin: RuntimePin,
    version: str,
    platform: str,
    source_commit: str,
    brain_commit: str,
) -> tuple[list[VirtualSymlink], dict[PurePosixPath, int]]:
    release_root.mkdir(mode=0o755)
    file_modes: dict[PurePosixPath, int] = {}
    for launcher in LAUNCHERS:
        source = _require_source_file(repository_root, PurePosixPath("scripts") / launcher)
        mode = 0o644 if launcher.endswith((".cmd", ".ps1")) else 0o755
        relative = PurePosixPath("bin") / launcher
        _copy_file(source, release_root.joinpath(*relative.parts), mode)
        file_modes[relative] = mode
    _copy_file(jar_path, release_root / "lib" / "ocelot-harness.jar", 0o644)
    file_modes[PurePosixPath("lib/ocelot-harness.jar")] = 0o644
    for source_name, destination_name in ROOT_FILE_COPIES:
        source = _require_source_file(repository_root, PurePosixPath(source_name))
        _copy_file(source, release_root / destination_name, 0o644)
    for document in (*REFERENCE_DOCUMENTS, *EXAMPLE_FILES):
        relative = PurePosixPath(document)
        source = _require_source_file(repository_root, relative)
        _copy_file(source, release_root.joinpath(*relative.parts), 0o644)
    virtual_links, runtime_modes = _extract_runtime(runtime_archive, release_root / "runtime", pin)
    file_modes.update(runtime_modes)
    _write_manifest(
        release_root,
        version,
        platform,
        source_commit,
        brain_commit,
        pin,
        virtual_links,
        file_modes,
    )
    file_modes[PurePosixPath("RELEASE-MANIFEST.json")] = 0o644
    return virtual_links, file_modes


def _all_paths(root: Path) -> list[Path]:
    return sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix())


def _write_zip(
    archive: Path,
    release_root: Path,
    virtual_links: Sequence[VirtualSymlink],
    file_modes: Mapping[PurePosixPath, int],
) -> None:
    if virtual_links:
        raise PackagingError("Windows releases cannot contain virtual symlinks")
    root_name = release_root.name
    with zipfile.ZipFile(
        archive, mode="w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=True
    ) as output:
        root_info = zipfile.ZipInfo(root_name + "/", FIXED_ZIP_TIME)
        root_info.create_system = 3
        root_info.external_attr = (stat.S_IFDIR | 0o755) << 16
        output.writestr(root_info, b"")
        for path in _all_paths(release_root):
            relative = path.relative_to(release_root).as_posix()
            archive_name = f"{root_name}/{relative}"
            if path.is_dir():
                info = zipfile.ZipInfo(archive_name + "/", FIXED_ZIP_TIME)
                mode = 0o755
                data = b""
                kind = stat.S_IFDIR
            elif path.is_file():
                info = zipfile.ZipInfo(archive_name, FIXED_ZIP_TIME)
                mode = file_modes.get(PurePosixPath(relative), 0o644)
                data = path.read_bytes()
                kind = stat.S_IFREG
            else:
                raise PackagingError(f"staging contains unsupported entry: {path}")
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (kind | mode) << 16
            output.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def _tar_info(name: str, mode: int, type_: bytes, size: int = 0) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.mode = mode
    info.type = type_
    info.size = size
    info.mtime = 0
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    return info


def _write_tar(
    archive: Path,
    release_root: Path,
    virtual_links: Sequence[VirtualSymlink],
    file_modes: Mapping[PurePosixPath, int],
) -> None:
    root_name = release_root.name
    with archive.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as output:
                output.addfile(_tar_info(root_name + "/", 0o755, tarfile.DIRTYPE))
                for path in _all_paths(release_root):
                    relative = path.relative_to(release_root).as_posix()
                    name = f"{root_name}/{relative}"
                    if path.is_dir():
                        output.addfile(_tar_info(name + "/", 0o755, tarfile.DIRTYPE))
                    elif path.is_file():
                        mode = file_modes.get(PurePosixPath(relative), 0o644)
                        info = _tar_info(name, mode, tarfile.REGTYPE, path.stat().st_size)
                        with path.open("rb") as stream:
                            output.addfile(info, stream)
                    else:
                        raise PackagingError(f"staging contains unsupported entry: {path}")
                for link in sorted(virtual_links, key=lambda item: item.path.as_posix()):
                    info = _tar_info(
                        f"{root_name}/{link.path.as_posix()}", link.mode, tarfile.SYMTYPE
                    )
                    info.linkname = link.target
                    output.addfile(info)


def _existing_output_is_owned(archive: Path, sidecar: Path) -> bool:
    if not archive.exists() and not sidecar.exists():
        return False
    if archive.is_symlink() or sidecar.is_symlink() or not archive.is_file() or not sidecar.is_file():
        raise PackagingError(f"refusing to overwrite unrelated output: {archive.name}")
    expected = f"{_sha256(archive)}  {archive.name}\n"
    try:
        actual = sidecar.read_text(encoding="ascii")
    except (OSError, UnicodeDecodeError) as error:
        raise PackagingError(f"cannot validate existing output sidecar: {error}") from error
    if actual != expected:
        raise PackagingError(f"refusing to overwrite unrelated output: {archive.name}")
    return True


def package_release(
    *,
    repository_root: Path,
    platform: str,
    runtime_archive: Path,
    output_dir: Path,
    jar_path: Path | None = None,
    version: str = DEFAULT_VERSION,
    java_version: str = DEFAULT_JAVA_VERSION,
    source_commit: str | None = None,
    brain_commit: str | None = None,
) -> Path:
    """Create one release archive and its SHA-256 sidecar.

    Explicit commits are intended for isolated unit fixtures.  The command-line path
    always derives both commits from the repository.
    """
    repository_root = Path(repository_root)
    runtime_archive = Path(runtime_archive)
    output_dir = Path(output_dir)
    if platform not in PLATFORMS:
        raise PackagingError(f"unsupported platform: {platform}")
    if java_version not in JAVA_VERSIONS:
        raise PackagingError(f"unsupported Java version: {java_version}")
    if not SAFE_VERSION.fullmatch(version):
        raise PackagingError(f"invalid release version: {version!r}")
    pin = _load_runtime_pin(repository_root, platform, java_version)

    identities_from_repository = source_commit is None and brain_commit is None
    if identities_from_repository:
        source_commit, brain_commit = _source_identities(repository_root)
        _verify_release_inputs_clean(repository_root)
    elif source_commit is None or brain_commit is None:
        raise PackagingError("source and brain commits must be supplied together")
    source_commit = _validate_commit(source_commit, "source commit")
    brain_commit = _validate_commit(brain_commit, "ocelot-brain commit")

    if jar_path is None:
        jar_path = repository_root / "modules" / "app" / "target" / "ocelot-harness.jar"
    jar_path = Path(jar_path)
    identity = _read_jar_identity(jar_path)
    if identity.get("harness.commit") != source_commit or identity.get("harness.dirty") != "false":
        raise PackagingError(
            "application JAR is dirty or was not built from the release source commit"
        )

    if output_dir.exists() and (output_dir.is_symlink() or not output_dir.is_dir()):
        raise PackagingError(f"output is not a directory: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)

    basename = f"ocelot-harness-{version}-{platform}-java{java_version}"
    extension = ".zip" if platform == "windows-x64" else ".tar.gz"
    archive = output_dir / f"{basename}{extension}"
    sidecar = output_dir / f"{archive.name}.sha256"
    _existing_output_is_owned(archive, sidecar)

    staging = Path(tempfile.mkdtemp(prefix=".ocelot-release-stage-", dir=output_dir))
    temporary_archive: Path | None = None
    temporary_sidecar: Path | None = None
    try:
        release_root = staging / basename
        virtual_links, file_modes = _stage_release(
            repository_root,
            release_root,
            jar_path,
            runtime_archive,
            pin,
            version,
            platform,
            source_commit,
            brain_commit,
        )
        archive_fd, archive_name = tempfile.mkstemp(
            prefix=f".{archive.name}.", suffix=".tmp", dir=output_dir
        )
        os.close(archive_fd)
        temporary_archive = Path(archive_name)
        if platform == "windows-x64":
            _write_zip(temporary_archive, release_root, virtual_links, file_modes)
        else:
            _write_tar(temporary_archive, release_root, virtual_links, file_modes)
        archive_hash = _sha256(temporary_archive)
        sidecar_fd, sidecar_name = tempfile.mkstemp(
            prefix=f".{sidecar.name}.", suffix=".tmp", dir=output_dir
        )
        os.close(sidecar_fd)
        temporary_sidecar = Path(sidecar_name)
        temporary_sidecar.write_text(
            f"{archive_hash}  {archive.name}\n", encoding="ascii", newline="\n"
        )
        os.replace(temporary_archive, archive)
        temporary_archive = None
        os.replace(temporary_sidecar, sidecar)
        temporary_sidecar = None
    finally:
        try:
            shutil.rmtree(staging)
        finally:
            if temporary_archive is not None:
                temporary_archive.unlink(missing_ok=True)
            if temporary_sidecar is not None:
                temporary_sidecar.unlink(missing_ok=True)
    return archive


def _parse_arguments(arguments: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create an offline Ocelot Harness release from a verified pinned JRE archive."
    )
    parser.add_argument("--platform", required=True, choices=PLATFORMS)
    parser.add_argument(
        "--java-version", choices=JAVA_VERSIONS, default=DEFAULT_JAVA_VERSION
    )
    parser.add_argument("--runtime-archive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--version", default=DEFAULT_VERSION)
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    options = _parse_arguments(arguments)
    repository_root = Path(__file__).resolve().parent.parent
    try:
        archive = package_release(
            repository_root=repository_root,
            platform=options.platform,
            runtime_archive=options.runtime_archive,
            output_dir=options.output,
            jar_path=options.jar,
            version=options.version,
            java_version=options.java_version,
        )
    except PackagingError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(archive)
    print(f"{archive}.sha256")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
