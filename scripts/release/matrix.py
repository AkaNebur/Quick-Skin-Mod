#!/usr/bin/env python3
"""Validate and expose the checked-in Quick Skin release matrix.

The output for ``--kind`` is deliberately a compact, single-line GitHub Actions
matrix.  CI can safely assign it to a step output without maintaining a second
copy of the supported versions.
"""

from __future__ import annotations

import argparse
import json
import re
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
    "pr_anchor",
}

SUPPORTED_LOADERS = {"fabric", "forge"}
VERSIONED_PROPERTY_PREFIXES = (
    "minecraft_version_",
    "java_version_",
    "architectury_api_version_",
    "fabric_loader_version_",
    "fabric_api_version_",
    "forge_version_",
)


class MatrixError(ValueError):
    pass


def load_matrix(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise MatrixError(f"cannot read release matrix {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise MatrixError("release matrix root must be an object")
    validate_matrix(data)
    validate_build_properties(path, data)
    validate_source_roots(path, data)
    return data


def read_properties(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise MatrixError(f"cannot read build properties {path}: {exc}") from exc
    properties: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() in properties:
            raise MatrixError(f"duplicate Gradle property {key.strip()}")
        properties[key.strip()] = value.strip()
    return properties


def validate_build_properties(matrix_path: Path, data: dict[str, Any]) -> None:
    properties = read_properties(matrix_path.resolve().parents[1] / "gradle.properties")
    runtimes = {row["artifact_node"]: row for row in data["runtimes"]}
    versions: dict[str, dict[str, Any]] = {}
    for artifact in data["artifacts"]:
        version = artifact["artifact_version"]
        version_info = versions.setdefault(version, {"java": artifact["java"]})
        if version_info["java"] != artifact["java"]:
            raise MatrixError(f"release lanes disagree on Java for Minecraft {version}")

        suffix = version.replace(".", "_")
        runtime = runtimes[artifact["artifact_node"]]
        expected_properties = {
            f"architectury_api_version_{suffix}": runtime["architectury"]["version"],
        }
        if artifact["loader"] == "fabric":
            expected_properties.update(
                {
                    f"fabric_loader_version_{suffix}": runtime["loader_version"],
                    f"fabric_api_version_{suffix}": runtime["fabric_api"],
                }
            )
        elif artifact["loader"] == "forge":
            expected_properties[f"forge_version_{suffix}"] = runtime["loader_version"]
        else:
            raise MatrixError(f"unsupported artifact loader {artifact['loader']!r}")
        for key, expected in expected_properties.items():
            if properties.get(key) != str(expected):
                raise MatrixError(
                    f"Gradle property {key}={properties.get(key)!r} disagrees with matrix {expected!r}"
                )

    for version, version_info in versions.items():
        suffix = version.replace(".", "_")
        expected = {
            f"minecraft_version_{suffix}": version,
            f"java_version_{suffix}": str(version_info["java"]),
        }
        for key, value in expected.items():
            if properties.get(key) != value:
                raise MatrixError(
                    f"Gradle property {key}={properties.get(key)!r} disagrees with matrix {value!r}"
                )

    supported_suffixes = {version.replace(".", "_") for version in versions}
    for key in properties:
        for prefix in VERSIONED_PROPERTY_PREFIXES:
            if key.startswith(prefix) and key.removeprefix(prefix) not in supported_suffixes:
                raise MatrixError(
                    f"Gradle property {key} belongs to no supported Minecraft version"
                )
            if key.startswith(prefix):
                break


def validate_source_roots(matrix_path: Path, data: dict[str, Any]) -> None:
    """Fail on unreferenced live overlays or a reintroduced version-snapshot tree."""
    repository = matrix_path.resolve().parents[1]
    overlays = data["source_overlays"]
    for module, routes in overlays.items():
        source_root = repository / module / "src"
        actual = {
            path.name
            for path in source_root.glob("legacy*")
            if path.is_dir() and any(child.is_file() for child in path.rglob("*"))
        }
        expected = set(routes.values())
        if actual != expected:
            raise MatrixError(
                f"{module} overlay roots disagree with matrix: "
                f"expected {sorted(expected)}, found {sorted(actual)}"
            )
        for overlay in expected:
            path = source_root / overlay
            if not path.is_dir() or not any(child.is_file() for child in path.rglob("*")):
                raise MatrixError(f"matrix references missing {module} overlay root {overlay}")

        retired_snapshots = [
            path for path in source_root.glob("v*")
            if path.is_dir() and any(child.is_file() for child in path.rglob("*"))
        ]
        if retired_snapshots:
            raise MatrixError(
                f"retired {module} version snapshots remain: "
                f"{[path.name for path in retired_snapshots]}"
            )

        live_java_roots = [source_root / "main" / "java"] + [
            source_root / overlay / "java" for overlay in expected
        ]
        locations_by_class: dict[str, list[str]] = {}
        for java_root in live_java_roots:
            if not java_root.is_dir():
                continue
            for source in java_root.rglob("*.java"):
                relative = source.relative_to(java_root).as_posix()
                locations_by_class.setdefault(relative, []).append(
                    source.relative_to(repository).as_posix()
                )
        duplicated = {
            relative: locations
            for relative, locations in locations_by_class.items()
            if len(locations) > 2
        }
        if duplicated:
            details = "; ".join(
                f"{relative}: {locations}"
                for relative, locations in sorted(duplicated.items())
            )
            raise MatrixError(
                f"{module} live Java classes exceed the two-copy overlay limit: {details}"
            )


def validate_matrix(data: dict[str, Any]) -> None:
    if data.get("schema_version") != 2:
        raise MatrixError("release matrix schema_version must be 2")

    lane_count = data.get("lane_count")
    if isinstance(lane_count, bool) or not isinstance(lane_count, int) or lane_count <= 0:
        raise MatrixError("release matrix lane_count must be a positive integer")
    artifacts = data.get("artifacts")
    runtimes = data.get("runtimes")
    if not isinstance(artifacts, list) or len(artifacts) != lane_count:
        raise MatrixError(
            f"release matrix must contain lane_count={lane_count} artifacts"
        )
    if not isinstance(runtimes, list) or len(runtimes) != lane_count:
        raise MatrixError(
            f"release matrix must contain lane_count={lane_count} runtime rows"
        )
    project = data.get("project", {})
    if not isinstance(project, dict):
        raise MatrixError("release matrix project must be an object")
    for key in ("name", "mod_id", "description", "homepage", "sources", "issues", "license"):
        if not isinstance(project.get(key), str) or not project[key].strip():
            raise MatrixError(f"project.{key} must be a non-empty string")
    if not isinstance(project.get("modrinth_id"), str) or not project["modrinth_id"].strip():
        raise MatrixError("project.modrinth_id must be a non-empty string")
    if (
        isinstance(project.get("curseforge_id"), bool)
        or not isinstance(project.get("curseforge_id"), int)
        or project["curseforge_id"] <= 0
    ):
        raise MatrixError("project.curseforge_id must be a positive integer")
    installers = data.get("installers", {})
    if not isinstance(installers, dict) or not installers:
        raise MatrixError("release matrix must lock runtime installers")
    for key, installer in installers.items():
        if not isinstance(installer, dict):
            raise MatrixError(f"installer {key} must be an object")
        if not str(installer.get("url", "")).startswith("https://"):
            raise MatrixError(f"installer {key} must use an HTTPS URL")
        if not re_full_sha256(installer.get("sha256")):
            raise MatrixError(f"installer {key} has invalid SHA-256")

    artifact_by_node: dict[str, dict[str, Any]] = {}
    loader_counts = {loader: 0 for loader in SUPPORTED_LOADERS}
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise MatrixError("every artifact row must be an object")
        required = {
            "artifact_node",
            "artifact_version",
            "loader",
            "java",
            "no_remap",
            "metadata_range",
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
        for key in (
            "artifact_node",
            "artifact_version",
            "loader",
            "metadata_range",
            "gradle_task",
            "harness_task",
            "jar",
            "harness_jar",
        ):
            if not isinstance(artifact[key], str) or not artifact[key]:
                raise MatrixError(f"artifact field {key} must be a non-empty string")
        if not isinstance(artifact["metadata"], dict):
            raise MatrixError(f"artifact {artifact['artifact_node']} metadata must be an object")
        node = artifact["artifact_node"]
        if node in artifact_by_node:
            raise MatrixError(f"duplicate artifact_node {node}")
        artifact_by_node[node] = artifact
        loader = artifact["loader"]
        if loader not in SUPPORTED_LOADERS:
            raise MatrixError(f"unsupported artifact loader {loader!r}")
        loader_counts[loader] += 1
        version = artifact["artifact_version"]
        if node != f"{loader}-{version}":
            raise MatrixError(f"artifact node {node} does not match {loader} {version}")
        task_prefix = f":{loader}:{version}:"
        no_remap = artifact["no_remap"]
        if not isinstance(no_remap, bool):
            raise MatrixError(f"artifact {node} no_remap must be a boolean")
        production_task = "shadowJar" if no_remap else "remapJar"
        harness_task = "e2eHarnessJar" if no_remap else "remapE2EHarnessJar"
        if artifact["gradle_task"] != f"{task_prefix}{production_task}":
            raise MatrixError(
                f"artifact {node} Gradle task must be {task_prefix}{production_task}"
            )
        if artifact["harness_task"] != f"{task_prefix}{harness_task}":
            raise MatrixError(
                f"artifact {node} harness task must be {task_prefix}{harness_task}"
            )
        java = artifact["java"]
        if isinstance(java, bool) or not isinstance(java, int) or java < 17:
            raise MatrixError(f"artifact {node} Java must be an integer of at least 17")
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

    missing_loaders = SUPPORTED_LOADERS - {loader for loader, count in loader_counts.items() if count}
    if missing_loaders:
        raise MatrixError(f"release inventory omits loaders {sorted(missing_loaders)}")
    unit_test_version = data.get("unit_test_version")
    artifact_versions = {artifact["artifact_version"] for artifact in artifacts}
    if not isinstance(unit_test_version, str) or unit_test_version not in artifact_versions:
        raise MatrixError("unit_test_version must select a supported release version")

    source_overlays = data.get("source_overlays")
    expected_source_modules = {"common", *SUPPORTED_LOADERS}
    if not isinstance(source_overlays, dict) or set(source_overlays) != expected_source_modules:
        raise MatrixError(
            "source_overlays must define common, fabric, and forge exactly once"
        )
    for module, routes in source_overlays.items():
        if not isinstance(routes, dict):
            raise MatrixError(f"source_overlays.{module} must be an object")
        allowed_versions = (
            artifact_versions
            if module == "common"
            else {
                artifact["artifact_version"]
                for artifact in artifacts
                if artifact["loader"] == module
            }
        )
        unknown = set(routes) - allowed_versions
        if unknown:
            raise MatrixError(
                f"source_overlays.{module} names unsupported versions {sorted(unknown)}"
            )
        values = list(routes.values())
        if not all(
            isinstance(value, str) and re.fullmatch(r"legacy[0-9A-Za-z_]+", value)
            for value in values
        ):
            raise MatrixError(f"source_overlays.{module} values must name legacy* roots")
        if len(values) != len(set(values)):
            raise MatrixError(f"source_overlays.{module} reuses an overlay root")

    version_policies: dict[str, tuple[int, bool]] = {}
    for artifact in artifacts:
        version = artifact["artifact_version"]
        policy = (artifact["java"], artifact["no_remap"])
        previous = version_policies.setdefault(version, policy)
        if previous != policy:
            raise MatrixError(
                f"release lanes disagree on Java/no_remap policy for Minecraft {version}"
            )

    seen_runtime_keys: set[tuple[str, str, str]] = set()
    runtime_nodes: set[str] = set()
    pr_anchor_loaders: set[str] = set()
    for runtime in runtimes:
        if not isinstance(runtime, dict):
            raise MatrixError("every runtime row must be an object")
        missing = REQUIRED_RUNTIME_FIELDS - runtime.keys()
        if missing:
            raise MatrixError(f"runtime row missing {sorted(missing)}: {runtime}")
        for key in ("artifact_node", "runtime_version", "loader", "loader_version", "installer"):
            if not isinstance(runtime[key], str) or not runtime[key]:
                raise MatrixError(f"runtime field {key} must be a non-empty string")
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
        if isinstance(runtime["port"], bool) or runtime["port"] != 0:
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
        if (
            not isinstance(architectury, dict)
            or architectury.get("kind") != "maven"
            or not isinstance(architectury.get("version"), str)
            or not architectury["version"]
        ):
            raise MatrixError(f"runtime {node} must use a locked Maven Architectury version")
        if runtime.get("scheduled_anchor") is not True:
            raise MatrixError(f"runtime {node} must be a scheduled native anchor")
        if not isinstance(runtime.get("pr_anchor"), bool):
            raise MatrixError(f"runtime {node} pr_anchor must be a boolean")
        if runtime["pr_anchor"]:
            pr_anchor_loaders.add(runtime["loader"])
        key = (node, runtime["runtime_version"], runtime["scenario"])
        if key in seen_runtime_keys:
            raise MatrixError(f"duplicate runtime row {key}")
        seen_runtime_keys.add(key)
        runtime_nodes.add(node)

    if runtime_nodes != set(artifact_by_node):
        raise MatrixError("every supported artifact must have exactly one exact runtime row")
    if pr_anchor_loaders != SUPPORTED_LOADERS:
        raise MatrixError(
            "PR anchors must cover Fabric and Forge; "
            f"got {sorted(pr_anchor_loaders)}"
        )
    used_installers = {row["installer"] for row in runtimes}
    if set(installers) != used_installers:
        raise MatrixError("release matrix contains an installer unused by supported runtimes")

    runtime_by_node = {row["artifact_node"]: row for row in runtimes}
    metadata_files = {
        "fabric": "fabric.mod.json",
        "forge": "META-INF/mods.toml",
    }
    for node, artifact in artifact_by_node.items():
        loader = artifact["loader"]
        version = artifact["artifact_version"]
        metadata = artifact["metadata"]
        if metadata.get("file") != metadata_files[loader]:
            raise MatrixError(f"artifact {node} has the wrong loader metadata file")
        if not isinstance(metadata.get("loader"), str) or not metadata["loader"]:
            raise MatrixError(f"artifact {node} metadata must declare its loader range")
        if loader == "fabric":
            if "loader_api" in metadata:
                raise MatrixError(f"Fabric artifact {node} must not declare FML loader_api")
            if "pack_format" in metadata or "server_data_pack_format" in metadata:
                raise MatrixError(f"Fabric artifact {node} must not declare FML pack formats")
        elif not isinstance(metadata.get("loader_api"), str) or not metadata["loader_api"]:
            raise MatrixError(f"FML artifact {node} metadata must declare loader_api")
        else:
            for key in ("pack_format", "server_data_pack_format"):
                value = metadata.get(key)
                if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                    raise MatrixError(
                        f"FML artifact {node} metadata.{key} must be a positive integer"
                    )
        if "minecraft" in metadata:
            raise MatrixError(
                f"artifact {node} must declare its Minecraft range only in metadata_range"
            )
        validate_metadata_range(node, loader, version, artifact["metadata_range"])
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
    pr_scenarios = data.get("pr_scenarios")
    if (
        not isinstance(pr_scenarios, list)
        or not pr_scenarios
        or not all(isinstance(scenario, str) for scenario in pr_scenarios)
    ):
        raise MatrixError("pr_scenarios must be a non-empty list")
    if len(pr_scenarios) != len(set(pr_scenarios)):
        raise MatrixError("pr_scenarios must not contain duplicates")
    if any(scenario not in scenarios for scenario in pr_scenarios):
        raise MatrixError("pr_scenarios must be selected from scheduled_scenarios")
    if "full" not in pr_scenarios or not any(
        scenario.startswith("propagation") for scenario in pr_scenarios
    ):
        raise MatrixError("PR coverage must include full and a multiplayer propagation scenario")


def re_full_sha256(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def validate_metadata_range(node: str, loader: str, version: str, value: Any) -> None:
    """Validate explicit loader metadata without deriving era-specific upper bounds."""
    if not isinstance(value, str) or not value:
        raise MatrixError(f"artifact {node} metadata_range must be a non-empty string")
    if loader == "fabric":
        if value != f"={version}":
            raise MatrixError(f"artifact {node} metadata_range must be ={version}")
        return

    if not value.startswith("[") or not value.endswith(")") or value.count(",") != 1:
        raise MatrixError(f"artifact {node} has malformed metadata_range {value!r}")
    lower, upper = value[1:-1].split(",", 1)
    if lower != version or not upper:
        raise MatrixError(
            f"artifact {node} metadata_range must start at its exact version {version}"
        )
    try:
        lower_parts = tuple(int(part) for part in lower.split("."))
        upper_parts = tuple(int(part) for part in upper.split("."))
    except ValueError as exc:
        raise MatrixError(f"artifact {node} metadata_range must use numeric versions") from exc
    width = max(len(lower_parts), len(upper_parts))
    padded_lower = lower_parts + (0,) * (width - len(lower_parts))
    padded_upper = upper_parts + (0,) * (width - len(upper_parts))
    if padded_upper <= padded_lower:
        raise MatrixError(f"artifact {node} metadata_range upper bound must exceed {version}")
    expected_upper = (
        lower_parts + (1,)
        if len(lower_parts) == 2
        else lower_parts[:-1] + (lower_parts[-1] + 1,)
    )
    if upper_parts != expected_upper:
        expected = ".".join(str(part) for part in expected_upper)
        raise MatrixError(
            f"artifact {node} metadata_range must end at the immediate patch successor {expected}"
        )


def read_mod_version(matrix_path: Path, data: dict[str, Any]) -> str:
    properties_path = matrix_path.resolve().parents[1] / "gradle.properties"
    key = data["project"]["mod_version_property"]
    properties = read_properties(properties_path)
    if key not in properties:
        raise MatrixError(f"{key} is missing from {properties_path}")
    return properties[key]


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
                    f"{artifact['loader'].replace('fabric', 'Fabric').replace('forge', 'Forge')}] "
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
    elif kind in {"runtime", "native-anchors", "pr-anchors"}:
        rows = data["runtimes"]
        if kind == "native-anchors":
            rows = [row for row in rows if row.get("scheduled_anchor")]
        elif kind == "pr-anchors":
            rows = [row for row in rows if row.get("pr_anchor")]
        include = []
        for row in rows:
            expanded = dict(row)
            scope = {
                "runtime": "release-behavior",
                "native-anchors": "scheduled-behavior",
                "pr-anchors": "pr-behavior",
            }[kind]
            expanded["id"] = (
                f"{row['artifact_node']}--{row['runtime_version']}--{scope}"
                .replace(".", "_")
            )
            if kind in {"runtime", "native-anchors"}:
                expanded["scenarios"] = ",".join(data["scheduled_scenarios"])
            elif kind == "pr-anchors":
                expanded["scenarios"] = ",".join(data["pr_scenarios"])
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
        choices=("artifacts", "runtime", "native-anchors", "pr-anchors"),
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
