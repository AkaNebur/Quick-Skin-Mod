#!/usr/bin/env python3
"""Fail-closed verification and staging for all Quick Skin release artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path
from typing import Any

from matrix import MatrixError, load_matrix


class VerificationError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_gradle_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def resolve_template(value: str, mod_version: str) -> str:
    return value.replace("{mod_version}", mod_version)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def dependency_by_id(metadata: dict[str, Any], mod_id: str) -> dict[str, Any]:
    dependencies = metadata.get("dependencies", {}).get("quickskin", [])
    matches = [entry for entry in dependencies if entry.get("modId") == mod_id]
    require(len(matches) == 1, f"expected one {mod_id} dependency, found {len(matches)}")
    return matches[0]


def verify_fabric_metadata(
    raw: bytes,
    names: set[str],
    artifact: dict[str, Any],
    project: dict[str, Any],
    mod_version: str,
) -> None:
    try:
        metadata = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise VerificationError(f"invalid fabric.mod.json: {exc}") from exc
    require(metadata.get("id") == project["mod_id"], "wrong Fabric mod id")
    require(metadata.get("version") == mod_version, "wrong Fabric mod version")
    require(metadata.get("name") == project["name"], "wrong Fabric display name")
    require(metadata.get("description") == project["description"], "wrong Fabric description")
    require(metadata.get("license") == project["license"], "wrong Fabric license")
    contact = metadata.get("contact", {})
    for key in ("homepage", "sources", "issues"):
        require(contact.get(key) == project[key], f"wrong Fabric contact.{key}")
    depends = metadata.get("depends", {})
    require(
        depends.get("minecraft") == artifact["metadata"]["minecraft"],
        "Fabric Minecraft range disagrees with release matrix",
    )
    require(
        depends.get("architectury") == artifact["metadata"]["architectury"],
        "Fabric Architectury range disagrees with release matrix",
    )
    require("fabricloader" in depends and "fabric-api" in depends, "Fabric dependencies incomplete")
    expected_suggestions = artifact["metadata"].get("suggests")
    if expected_suggestions is not None:
        require(metadata.get("suggests") == expected_suggestions, "Fabric suggestions disagree with matrix")
    else:
        require("suggests" not in metadata, "unexpected Fabric suggestions outside release matrix")
    for mixin in metadata.get("mixins", []):
        name = mixin if isinstance(mixin, str) else mixin.get("config")
        require(bool(name) and name in names, f"Fabric metadata references missing mixin {name!r}")
    access_widener = metadata.get("accessWidener")
    if access_widener:
        require(access_widener in names, f"Fabric metadata references missing {access_widener}")


def verify_fml_metadata(
    raw: bytes,
    names: set[str],
    artifact: dict[str, Any],
    project: dict[str, Any],
    mod_version: str,
) -> None:
    try:
        metadata = tomllib.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise VerificationError(f"invalid FML metadata: {exc}") from exc
    mods = metadata.get("mods", [])
    require(len(mods) == 1, f"expected one FML mod entry, found {len(mods)}")
    mod = mods[0]
    require(mod.get("modId") == project["mod_id"], "wrong FML mod id")
    require(mod.get("version") == mod_version, "wrong FML mod version")
    require(mod.get("displayName") == project["name"], "wrong FML display name")
    require(project["description"] in mod.get("description", ""), "wrong FML description")
    require(metadata.get("license") == project["license"], "wrong FML license")
    require(metadata.get("issueTrackerURL") == project["issues"], "wrong FML issue URL")
    require(mod.get("displayURL") == project["homepage"], "wrong FML homepage")
    mc = dependency_by_id(metadata, "minecraft")
    architectury = dependency_by_id(metadata, "architectury")
    require(
        mc.get("versionRange") == artifact["metadata"]["minecraft"],
        "FML Minecraft range disagrees with release matrix",
    )
    require(
        architectury.get("versionRange") == artifact["metadata"]["architectury"],
        "FML Architectury range disagrees with release matrix",
    )
    loader_dependency = "forge" if artifact["loader"] == "forge" else "neoforge"
    dependency_by_id(metadata, loader_dependency)
    for mixin in metadata.get("mixins", []):
        name = mixin.get("config")
        require(bool(name) and name in names, f"FML metadata references missing mixin {name!r}")


def verify_jar(
    path: Path,
    artifact: dict[str, Any],
    project: dict[str, Any],
    mod_version: str,
) -> dict[str, Any]:
    require(path.is_file(), f"missing production artifact: {path}")
    require(path.suffix.lower() == ".jar", f"artifact is not a jar: {path}")
    forbidden_filename_tokens = ("-raw", "dev-shadow", "sources", "javadoc")
    require(
        not any(token in path.name.lower() for token in forbidden_filename_tokens),
        f"non-production jar selected: {path.name}",
    )
    try:
        with zipfile.ZipFile(path) as jar:
            bad_member = jar.testzip()
            require(bad_member is None, f"corrupt zip entry {bad_member} in {path.name}")
            members = jar.infolist()
            names_list = [entry.filename for entry in members]
            names = set(names_list)
            require(len(names_list) == len(names), f"duplicate zip entries in {path.name}")
            require(
                not any(".migration-archive" in name or "/src/v" in name for name in names),
                f"historical source leaked into {path.name}",
            )
            require(
                not any(
                    name.startswith("com/quickskin/mod/e2e/")
                    or name in {
                        "qs_e2e_test_skin.png",
                        "qs_e2e_test_cape.gif",
                    }
                    for name in names
                ),
                f"test harness leaked into {path.name}",
            )
            require("com/quickskin/mod/QuickSkin.class" in names, f"QuickSkin class missing from {path.name}")
            require("quickskin.mixins.json" in names, f"common mixin config missing from {path.name}")
            require(
                len([name for name in names if name.endswith("/PlatformMethods.class")]) == 1,
                f"expected one transformed Architectury PlatformMethods class in {path.name}",
            )
            require(
                "com/luciad/imageio/webp/WebP.class" in names,
                f"bundled WebP dependency missing from {path.name}",
            )
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
            require("Stonecutter-" not in manifest, f"Stonecutter metadata leaked into {path.name}")
            metadata_file = artifact["metadata"]["file"]
            require(metadata_file in names, f"loader metadata {metadata_file} missing from {path.name}")
            raw_metadata = jar.read(metadata_file)
            if artifact["loader"] == "fabric":
                verify_fabric_metadata(raw_metadata, names, artifact, project, mod_version)
            else:
                verify_fml_metadata(raw_metadata, names, artifact, project, mod_version)
    except zipfile.BadZipFile as exc:
        raise VerificationError(f"invalid jar {path}: {exc}") from exc

    return {
        "filename": path.name,
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def verify_harness(path: Path, artifact: dict[str, Any]) -> dict[str, Any]:
    require(path.is_file(), f"missing packaged E2E harness: {path}")
    try:
        with zipfile.ZipFile(path) as jar:
            require(jar.testzip() is None, f"corrupt E2E harness {path.name}")
            names = set(jar.namelist())
            require(
                "com/quickskin/mod/e2e/E2EHarness.class" in names,
                f"E2E entrypoint missing from {path.name}",
            )
            require(
                "com/quickskin/mod/QuickSkin.class" not in names,
                f"production QuickSkin classes leaked into separate harness {path.name}",
            )
            class_entries = [name for name in names if name.endswith(".class")]
            require(
                bool(class_entries)
                and all(name.startswith("com/quickskin/mod/e2e/") for name in class_entries),
                f"non-E2E classes leaked into separate harness {path.name}",
            )
            metadata = artifact["metadata"]["file"]
            require(metadata in names, f"loader metadata missing from E2E harness {path.name}")
            raw_metadata = jar.read(metadata)
            if artifact["loader"] == "fabric":
                parsed = json.loads(raw_metadata)
                require(parsed.get("id") == "quick-skin-e2e", "wrong Fabric E2E mod id")
                require(parsed.get("version") == "0.0.0", "wrong Fabric E2E version")
                require(parsed.get("license") == "All Rights Reserved", "wrong Fabric E2E license")
                require(parsed.get("environment") == "client", "Fabric E2E harness must be client-only")
                require(parsed.get("depends", {}).get("quickskin") == "*", "Fabric E2E dependency missing")
                entrypoints = parsed.get("entrypoints", {}).get("client", [])
                require(len(entrypoints) == 1, "Fabric E2E harness must have one client entrypoint")
                entrypoint_class = str(entrypoints[0]).replace(".", "/") + ".class"
                require(entrypoint_class in names, "Fabric E2E client entrypoint class is missing")
            else:
                parsed = tomllib.loads(raw_metadata.decode("utf-8"))
                mods = parsed.get("mods", [])
                require(len(mods) == 1, f"expected one E2E mod entry in {path.name}")
                require(mods[0].get("modId") == "quick_skin_e2e", "wrong FML E2E mod id")
                require(mods[0].get("version") == "0.0.0", "wrong FML E2E version")
                require(
                    mods[0].get("displayTest") == "IGNORE_ALL_VERSION",
                    "FML E2E harness must ignore the intentional server mod-list mismatch",
                )
                require(parsed.get("license") == "All Rights Reserved", "wrong FML E2E license")
                dependencies = parsed.get("dependencies", {}).get("quick_skin_e2e", [])
                require(
                    any(dependency.get("modId") == "quickskin" for dependency in dependencies),
                    "FML E2E dependency on Quick Skin is missing",
                )
    except zipfile.BadZipFile as exc:
        raise VerificationError(f"invalid E2E harness {path}: {exc}") from exc
    except (UnicodeDecodeError, json.JSONDecodeError, tomllib.TOMLDecodeError) as exc:
        raise VerificationError(f"invalid E2E metadata in {path}: {exc}") from exc
    return {
        "filename": path.name,
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def git_commit(repo: Path) -> str | None:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repo, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def safe_stage_dir(repo: Path, stage: Path) -> Path:
    resolved = stage.resolve()
    build_root = (repo / "build").resolve()
    require(
        resolved == build_root or build_root in resolved.parents,
        f"stage directory must remain under {build_root}",
    )
    return resolved


def stage_file(source: Path, destination_dir: Path) -> Path:
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = destination_dir / source.name
    shutil.copy2(source, destination)
    require(sha256(source) == sha256(destination), f"staged hash mismatch for {source.name}")
    return destination


def build_manifest(
    repo: Path,
    matrix_path: Path,
    stage: Path,
    mod_version: str,
    data: dict[str, Any],
) -> dict[str, Any]:
    files_dir = stage / "files"
    harness_dir = stage / "harness"
    for directory in (files_dir, harness_dir):
        if directory.exists():
            shutil.rmtree(directory)

    records: list[dict[str, Any]] = []
    for artifact in data["artifacts"]:
        source = repo / resolve_template(artifact["jar"], mod_version)
        harness_source = repo / resolve_template(artifact["harness_jar"], mod_version)
        verified = verify_jar(source, artifact, data["project"], mod_version)
        harness_verified = verify_harness(harness_source, artifact)
        staged = stage_file(source, files_dir)
        staged_harness = stage_file(harness_source, harness_dir)
        verified.update(
            {
                "artifact_node": artifact["artifact_node"],
                "artifact_version": artifact["artifact_version"],
                "loader": artifact["loader"],
                "game_versions": artifact["game_versions"],
                "path": staged.relative_to(stage).as_posix(),
                "harness": {
                    **harness_verified,
                    "path": staged_harness.relative_to(stage).as_posix(),
                },
            }
        )
        records.append(verified)

    require(len({record["sha256"] for record in records}) == 10, "production jar hashes are not unique")
    return {
        "schema_version": 1,
        "matrix": matrix_path.relative_to(repo).as_posix(),
        "mod_version": mod_version,
        "git_commit": git_commit(repo),
        "artifacts": records,
    }


def verify_staged_manifest(
    stage: Path,
    manifest: dict[str, Any],
    data: dict[str, Any],
    mod_version: str,
    expected_commit: str | None,
) -> None:
    require(manifest.get("schema_version") == 1, "unsupported artifact manifest schema")
    require(manifest.get("mod_version") == mod_version, "artifact manifest version mismatch")
    require(manifest.get("git_commit") == expected_commit, "artifact manifest commit mismatch")
    records = manifest.get("artifacts", [])
    require(
        isinstance(records, list)
        and len(records) == 10
        and all(isinstance(record, dict) for record in records),
        "artifact manifest must contain exactly 10 object records",
    )
    artifact_by_node = {artifact["artifact_node"]: artifact for artifact in data["artifacts"]}
    require(
        {record.get("artifact_node") for record in records} == set(artifact_by_node),
        "artifact manifest node inventory disagrees with release matrix",
    )

    def staged_path(relative: Any, label: str) -> Path:
        require(isinstance(relative, str) and relative, f"{label} path is missing")
        resolved = (stage / relative).resolve()
        require(stage == resolved or stage in resolved.parents, f"{label} escapes stage: {relative}")
        return resolved

    for record in records:
        artifact = artifact_by_node[record["artifact_node"]]
        harness_record = record.get("harness")
        require(isinstance(harness_record, dict), f"missing harness record for {record['artifact_node']}")
        expected_jar_name = Path(resolve_template(artifact["jar"], mod_version)).name
        expected_harness_name = Path(resolve_template(artifact["harness_jar"], mod_version)).name
        require(record.get("path") == f"files/{expected_jar_name}", "staged production path mismatch")
        require(
            harness_record.get("path") == f"harness/{expected_harness_name}",
            "staged harness path mismatch",
        )
        require(record.get("loader") == artifact["loader"], "staged loader identity mismatch")
        require(
            record.get("artifact_version") == artifact["artifact_version"],
            "staged artifact version mismatch",
        )
        require(record.get("game_versions") == artifact["game_versions"], "staged game versions mismatch")
        jar = staged_path(record["path"], "production artifact")
        harness = staged_path(harness_record["path"], "E2E harness")
        require(jar.is_file(), f"staged artifact missing: {jar}")
        require(harness.is_file(), f"staged harness missing: {harness}")
        verified = verify_jar(jar, artifact, data["project"], mod_version)
        harness_verified = verify_harness(harness, artifact)
        require(verified["filename"] == record.get("filename"), f"staged filename mismatch: {jar.name}")
        require(verified["bytes"] == record.get("bytes"), f"staged byte count mismatch: {jar.name}")
        require(verified["sha256"] == record.get("sha256"), f"staged artifact hash mismatch: {jar.name}")
        require(
            harness_verified["filename"] == harness_record.get("filename")
            and harness_verified["bytes"] == harness_record.get("bytes")
            and harness_verified["sha256"] == harness_record.get("sha256"),
            f"staged harness hash mismatch: {harness.name}",
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--manifest", type=Path, default=Path("build/release/artifacts.json"))
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    parser.add_argument(
        "--verify-staged",
        action="store_true",
        help="only verify an existing staged manifest after CI artifact download",
    )
    args = parser.parse_args()

    repo = Path(__file__).resolve().parents[2]
    matrix_path = (repo / args.matrix).resolve() if not args.matrix.is_absolute() else args.matrix.resolve()
    manifest_path = (repo / args.manifest).resolve() if not args.manifest.is_absolute() else args.manifest.resolve()
    stage = safe_stage_dir(repo, repo / args.stage if not args.stage.is_absolute() else args.stage)

    try:
        data = load_matrix(matrix_path)
        properties = read_gradle_properties(repo / "gradle.properties")
        mod_version = properties.get(data["project"]["mod_version_property"])
        require(bool(mod_version), "mod_version is missing from gradle.properties")
        current_commit = git_commit(repo)
        if args.verify_staged:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            verify_staged_manifest(stage, manifest, data, mod_version, current_commit)
            print(f"Verified 10 staged production jars and 10 packaged E2E harnesses in {stage}")
            return 0

        manifest = build_manifest(repo, matrix_path, stage, mod_version, data)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        verify_staged_manifest(stage, manifest, data, mod_version, current_commit)
    except (MatrixError, VerificationError, OSError, json.JSONDecodeError) as exc:
        print(f"release verification failed: {exc}", file=sys.stderr)
        return 1

    print(f"Verified and staged 10 production jars at {stage}")
    for record in manifest["artifacts"]:
        print(f"  {record['artifact_node']}: {record['sha256']}  {record['filename']}")
    print(f"Wrote {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
