#!/usr/bin/env python3
"""Validate and expose the checked-in Quick Skin release matrix.

The output for ``--kind`` is deliberately a compact, single-line GitHub Actions
matrix.  CI can safely assign it to a step output without maintaining a second
copy of the supported versions.
"""

from __future__ import annotations

import argparse
import json
import os
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
    if not isinstance(runtimes, list) or len(runtimes) != 14:
        raise MatrixError("release matrix must contain exactly 14 runtime rows")
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
        if artifact["java"] not in (17, 21, 25):
            raise MatrixError(f"artifact {node} has unsupported Java {artifact['java']}")
        for key in ("jar", "harness_jar"):
            value = artifact[key].replace("\\", "/")
            if value.startswith("/") or ".." in Path(value).parts:
                raise MatrixError(f"artifact {node} has unsafe {key}: {value}")
            if "/src/v" in f"/{value}" or ".migration-archive" in value:
                raise MatrixError(f"artifact {node} points into excluded source history")
        versions = artifact["game_versions"]
        if not versions or len(versions) != len(set(versions)):
            raise MatrixError(f"artifact {node} has empty or duplicate game_versions")

    if loader_counts != expected_loaders:
        raise MatrixError(f"artifact loader counts {loader_counts}, expected {expected_loaders}")

    seen_runtime_keys: set[tuple[str, str, str]] = set()
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
        if runtime["runtime_version"] not in artifact["game_versions"]:
            raise MatrixError(
                f"runtime {runtime['runtime_version']} is not advertised by artifact {node}"
            )
        if runtime["jar_sha256"] != "from:artifact-manifest":
            raise MatrixError(f"runtime {node}/{runtime['runtime_version']} must bind the build hash")
        if runtime["port"] != 0:
            raise MatrixError("checked-in runtime ports must be 0 (allocated per isolated profile)")
        if runtime.get("installer") not in installers:
            raise MatrixError(f"runtime {node}/{runtime['runtime_version']} has no locked installer")
        architectury = runtime.get("architectury", {})
        if architectury.get("kind") == "external-maintained-compat":
            for field in ("url_env", "sha256_env", "modrinth_id_env", "curseforge_id_env", "blocker"):
                if not architectury.get(field):
                    raise MatrixError(
                        f"external Architectury row {node}/{runtime['runtime_version']} missing {field}"
                    )
        key = (node, runtime["runtime_version"], runtime["scenario"])
        if key in seen_runtime_keys:
            raise MatrixError(f"duplicate runtime row {key}")
        seen_runtime_keys.add(key)

    pooled = {
        (row["artifact_node"], row["runtime_version"])
        for row in runtimes
        if row["artifact_node"] in {"fabric-26.1", "neoforge-26.1"}
    }
    expected_pooled = {
        (f"{loader}-26.1", version)
        for loader in ("fabric", "neoforge")
        for version in ("26.1", "26.1.1", "26.1.2")
    }
    if pooled != expected_pooled:
        raise MatrixError("26.1 pooled artifacts must cover all six loader/runtime pairs")
    pooled_neoforge = [
        row for row in runtimes if row["artifact_node"] == "neoforge-26.1"
    ]
    if any(
        row.get("architectury", {}).get("kind") != "external-maintained-compat"
        for row in pooled_neoforge
    ):
        raise MatrixError(
            "all pooled NeoForge 26.1 rows must test the exact published compatibility dependency"
        )
    contract_fields = (
        "url_env",
        "sha256_env",
        "modrinth_id_env",
        "curseforge_id_env",
    )
    contracts = {
        tuple(row["architectury"].get(field) for field in contract_fields)
        for row in pooled_neoforge
    }
    if len(contracts) != 1:
        raise MatrixError("pooled NeoForge 26.1 rows must share one compatibility contract")

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
            requires_compat = artifact["artifact_node"] == "neoforge-26.1"
            if requires_compat:
                compat_rows = [
                    row
                    for row in data["runtimes"]
                    if row["artifact_node"] == "neoforge-26.1"
                    and row["architectury"].get("kind") == "external-maintained-compat"
                ]
                if not compat_rows:
                    raise MatrixError("neoforge-26.1 has no maintained compatibility dependency contract")
                compat = compat_rows[0]["architectury"]
                values = {field: os.environ.get(compat[field], "").strip() for field in (
                    "url_env", "sha256_env", "modrinth_id_env", "curseforge_id_env"
                )}
                missing = [compat[field] for field, value in values.items() if not value]
                if missing:
                    raise MatrixError(
                        "neoforge-26.1 publishing is blocked until maintained Architectury "
                        f"compatibility inputs are configured: {', '.join(missing)}"
                    )
                if not values["url_env"].startswith("https://") or not re_full_sha256(values["sha256_env"]):
                    raise MatrixError("NeoForge 26.1 compatibility URL/SHA256 inputs are invalid")
                dependencies[0] = (
                    "architectury-26.1-compat(required)"
                    f"{{modrinth:{values['modrinth_id_env']}}}"
                    f"{{curseforge:{values['curseforge_id_env']}}}"
                )
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
                "requires_neoforge_26_1_compat": requires_compat,
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
