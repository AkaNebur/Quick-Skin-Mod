#!/usr/bin/env python3
"""Validate and expose the checked-in Quick Skin release matrix.

The output for ``--kind`` is deliberately a compact, single-line GitHub Actions
matrix.  CI can safely assign it to a step output without maintaining a second
copy of the supported versions.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_RUNTIME_FIELDS = {
    "artifact_node",
    "runtime_version",
    "loader",
    "scenario",
    "jar_sha256",
    "port",
    "java",
    "loader_version",
    "installer",
    "architectury",
    "scheduled_anchor",
}

EXPECTED_ARTIFACT_NODES = {
    "fabric-1.20.1",
    "forge-1.20.1",
    "fabric-1.21.1",
    "neoforge-1.21.1",
    "fabric-1.21.11",
    "neoforge-1.21.11",
    "fabric-26.1.2",
    "neoforge-26.1.2",
    "fabric-26.2",
    "neoforge-26.2",
}


class MatrixError(ValueError):
    pass


def load_matrix(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise MatrixError(f"cannot read release matrix {path}: {exc}") from exc
    validate_matrix(data)
    return data


def validate_matrix(data: dict[str, Any]) -> None:
    if data.get("schema_version") != 1:
        raise MatrixError("release matrix schema_version must be 1")

    artifacts = data.get("artifacts")
    runtimes = data.get("runtimes")
    if not isinstance(artifacts, list) or len(artifacts) != 10:
        raise MatrixError("release matrix must contain exactly 10 artifacts")
    if not isinstance(runtimes, list) or len(runtimes) != 10:
        raise MatrixError("release matrix must contain exactly 10 runtime rows")
    project = data.get("project", {})
    for key in ("name", "mod_id", "description", "homepage", "sources", "issues", "license"):
        if not isinstance(project.get(key), str) or not project[key].strip():
            raise MatrixError(f"project.{key} must be a non-empty string")
    if not isinstance(project.get("modrinth_id"), str) or not project["modrinth_id"].strip():
        raise MatrixError("project.modrinth_id must be a non-empty string")
    if not isinstance(project.get("curseforge_id"), int) or project["curseforge_id"] <= 0:
        raise MatrixError("project.curseforge_id must be a positive integer")
    installers = data.get("installers", {})
    if not isinstance(installers, dict) or not installers:
        raise MatrixError("release matrix must lock runtime installers")
    for key, installer in installers.items():
        if not str(installer.get("url", "")).startswith("https://"):
            raise MatrixError(f"installer {key} must use an HTTPS URL")
        if not re_full_sha256(installer.get("sha256")):
            raise MatrixError(f"installer {key} has invalid SHA-256")

    artifact_by_node: dict[str, dict[str, Any]] = {}
    expected_loaders = {
        "fabric": 5,
        "forge": 1,
        "neoforge": 4,
    }
    loader_counts = {loader: 0 for loader in expected_loaders}
    for artifact in artifacts:
        required = {
            "artifact_node",
            "artifact_version",
            "loader",
            "java",
            "gradle_task",
            "harness_task",
            "jar",
            "harness_jar",
            "game_versions",
            "metadata",
        }
        missing = required - artifact.keys()
        if missing:
            raise MatrixError(
                f"artifact {artifact.get('artifact_node', '<unknown>')} missing {sorted(missing)}"
            )
        node = artifact["artifact_node"]
        if node in artifact_by_node:
            raise MatrixError(f"duplicate artifact_node {node}")
        artifact_by_node[node] = artifact
        loader = artifact["loader"]
        if loader not in loader_counts:
            raise MatrixError(f"unsupported artifact loader {loader!r}")
        loader_counts[loader] += 1
        version = artifact["artifact_version"]
        if node != f"{loader}-{version}":
            raise MatrixError(f"artifact node {node} does not match {loader} {version}")
        task_prefix = f":{loader}:{version}:"
        if not artifact["gradle_task"].startswith(task_prefix):
            raise MatrixError(f"artifact {node} Gradle task does not select its exact node")
        if not artifact["harness_task"].startswith(task_prefix):
            raise MatrixError(f"artifact {node} harness task does not select its exact node")
        if artifact["java"] not in (17, 21, 25):
            raise MatrixError(f"artifact {node} has unsupported Java {artifact['java']}")
        for key in ("jar", "harness_jar"):
            value = artifact[key].replace("\\", "/")
            if value.startswith("/") or ".." in Path(value).parts:
                raise MatrixError(f"artifact {node} has unsafe {key}: {value}")
            if "/src/v" in f"/{value}" or ".migration-archive" in value:
                raise MatrixError(f"artifact {node} points into excluded source history")
            if f"/{version}/" not in f"/{value}" or f" - {version}-" not in Path(value).name:
                raise MatrixError(f"artifact {node} {key} does not encode its exact version")
        versions = artifact["game_versions"]
        if versions != [artifact["artifact_version"]]:
            raise MatrixError(f"artifact {node} must advertise only its exact build version")

    if loader_counts != expected_loaders:
        raise MatrixError(f"artifact loader counts {loader_counts}, expected {expected_loaders}")
    if set(artifact_by_node) != EXPECTED_ARTIFACT_NODES:
        raise MatrixError(
            f"artifact nodes {sorted(artifact_by_node)}, expected {sorted(EXPECTED_ARTIFACT_NODES)}"
        )

    seen_runtime_keys: set[tuple[str, str, str]] = set()
    runtime_nodes: set[str] = set()
    for runtime in runtimes:
        missing = REQUIRED_RUNTIME_FIELDS - runtime.keys()
        if missing:
            raise MatrixError(f"runtime row missing {sorted(missing)}: {runtime}")
        node = runtime["artifact_node"]
        artifact = artifact_by_node.get(node)
        if artifact is None:
            raise MatrixError(f"runtime row refers to unknown artifact {node}")
        if runtime["loader"] != artifact["loader"]:
            raise MatrixError(f"runtime loader disagrees with artifact {node}")
        if runtime["runtime_version"] != artifact["artifact_version"]:
            raise MatrixError(f"runtime {node} must match its exact artifact version")
        if runtime["java"] != artifact["java"]:
            raise MatrixError(f"runtime Java disagrees with artifact {node}")
        if runtime["scenario"] != "phase0-smoke":
            raise MatrixError(f"runtime {node} must use the locked phase0-smoke scenario")
        if not isinstance(runtime["loader_version"], str) or not runtime["loader_version"]:
            raise MatrixError(f"runtime {node} must lock a loader version")
        if runtime["jar_sha256"] != "from:artifact-manifest":
            raise MatrixError(f"runtime {node}/{runtime['runtime_version']} must bind the build hash")
        if runtime["port"] != 0:
            raise MatrixError("checked-in runtime ports must be 0 (allocated per isolated profile)")
        if runtime["installer"] not in installers:
            raise MatrixError(f"runtime {node}/{runtime['runtime_version']} has no locked installer")
        expected_installer = (
            "fabric-1.1.0"
            if runtime["loader"] == "fabric"
            else f"{runtime['loader']}-{runtime['loader_version']}"
        )
        if runtime["installer"] != expected_installer:
            raise MatrixError(f"runtime {node} installer disagrees with its loader version")
        if runtime["loader"] == "fabric":
            if not isinstance(runtime.get("fabric_api"), str) or not runtime["fabric_api"]:
                raise MatrixError(f"Fabric runtime {node} must lock Fabric API")
        elif "fabric_api" in runtime:
            raise MatrixError(f"non-Fabric runtime {node} must not declare Fabric API")
        architectury = runtime.get("architectury", {})
        if architectury.get("kind") != "maven" or not architectury.get("version"):
            raise MatrixError(f"runtime {node} must use a locked Maven Architectury version")
        if runtime.get("scheduled_anchor") is not True:
            raise MatrixError(f"runtime {node} must be a scheduled native anchor")
        key = (node, runtime["runtime_version"], runtime["scenario"])
        if key in seen_runtime_keys:
            raise MatrixError(f"duplicate runtime row {key}")
        seen_runtime_keys.add(key)
        runtime_nodes.add(node)

    if runtime_nodes != EXPECTED_ARTIFACT_NODES:
        raise MatrixError("every supported artifact must have exactly one exact runtime row")
    used_installers = {row["installer"] for row in runtimes}
    if set(installers) != used_installers:
        raise MatrixError("release matrix contains an installer unused by supported runtimes")

    runtime_by_node = {row["artifact_node"]: row for row in runtimes}
    metadata_files = {
        "fabric": "fabric.mod.json",
        "forge": "META-INF/mods.toml",
        "neoforge": "META-INF/neoforge.mods.toml",
    }
    for node, artifact in artifact_by_node.items():
        loader = artifact["loader"]
        version = artifact["artifact_version"]
        metadata = artifact["metadata"]
        if metadata.get("file") != metadata_files[loader]:
            raise MatrixError(f"artifact {node} has the wrong loader metadata file")
        if version == "26.2":
            next_version = "26.2.1"
        else:
            parts = version.split(".")
            parts[-1] = str(int(parts[-1]) + 1)
            next_version = ".".join(parts)
        expected_minecraft = (
            f"={version}" if loader == "fabric" else f"[{version},{next_version})"
        )
        if metadata.get("minecraft") != expected_minecraft:
            raise MatrixError(f"artifact {node} metadata must target only {version}")
        architectury_version = runtime_by_node[node]["architectury"]["version"]
        expected_architectury = (
            f">={architectury_version}"
            if loader == "fabric"
            else f"[{architectury_version},)"
        )
        if metadata.get("architectury") != expected_architectury:
            raise MatrixError(f"artifact {node} metadata disagrees with its tested Architectury")

    scenarios = data.get("scheduled_scenarios")
    if scenarios != ["phase0-smoke", "propagation", "propagation-live", "full"]:
        raise MatrixError("scheduled_scenarios must contain the four locked E2E scenarios")


def re_full_sha256(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def read_mod_version(matrix_path: Path, data: dict[str, Any]) -> str:
    properties = matrix_path.resolve().parents[1] / "gradle.properties"
    key = data["project"]["mod_version_property"]
    for raw in properties.read_text(encoding="utf-8").splitlines():
        if raw.strip().startswith(f"{key}="):
            return raw.split("=", 1)[1].strip()
    raise MatrixError(f"{key} is missing from {properties}")


def gha_matrix(
    data: dict[str, Any], kind: str, mod_version: str
) -> dict[str, list[dict[str, Any]]]:
    if kind == "artifacts":
        include = []
        for artifact in data["artifacts"]:
            dependencies = ["architectury-api(required)"]
            if artifact["loader"] == "fabric":
                dependencies.append("fabric-api(required)")
            filename = Path(artifact["jar"].replace("{mod_version}", mod_version)).name
            include.append({
                "id": artifact["artifact_node"],
                "artifact_node": artifact["artifact_node"],
                "file": f"build/release/files/{filename}",
                "name": (
                    f"Quick Skin {mod_version} ["
                    f"{artifact['loader'].replace('neoforge', 'NeoForge').replace('fabric', 'Fabric').replace('forge', 'Forge')}] "
                    f"[MC {artifact['artifact_version']}]"
                ),
                "loader": artifact["loader"],
                "artifact_version": artifact["artifact_version"],
                "game_versions": "\n".join(artifact["game_versions"]),
                "dependencies": "\n".join(dependencies),
                "java": artifact["java"],
                "version": mod_version,
                "modrinth_id": data["project"]["modrinth_id"],
                "curseforge_id": data["project"]["curseforge_id"],
            })
    elif kind in {"runtime", "native-anchors"}:
        rows = data["runtimes"]
        if kind == "native-anchors":
            rows = [row for row in rows if row.get("scheduled_anchor")]
        include = []
        for row in rows:
            expanded = dict(row)
            expanded["id"] = (
                f"{row['artifact_node']}--{row['runtime_version']}--{row['scenario']}"
                .replace(".", "_")
            )
            include.append(expanded)
    else:  # pragma: no cover - argparse prevents this
        raise MatrixError(f"unsupported matrix kind {kind}")
    return {"include": include}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--matrix",
        type=Path,
        default=Path("release/release-matrix.json"),
        help="checked-in release matrix",
    )
    parser.add_argument(
        "--kind",
        choices=("artifacts", "runtime", "native-anchors"),
        help="emit a compact GitHub Actions matrix",
    )
    parser.add_argument("--pretty", action="store_true", help="pretty-print output")
    args = parser.parse_args()

    try:
        data = load_matrix(args.matrix)
        output: Any = (
            gha_matrix(data, args.kind, read_mod_version(args.matrix, data))
            if args.kind
            else data
        )
    except MatrixError as exc:
        print(f"release matrix error: {exc}", file=sys.stderr)
        return 2

    if args.pretty:
        print(json.dumps(output, indent=2, sort_keys=True))
    else:
        print(json.dumps(output, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
