#!/usr/bin/env python3
"""Run each exact Quick Skin release artifact in its isolated production runtime."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts" / "release"))

from matrix import MatrixError, load_matrix  # noqa: E402
from packaged_runtime import run_packaged_row  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument(
        "--artifacts-manifest", type=Path, default=Path("build/release/artifacts.json")
    )
    parser.add_argument(
        "--row-json",
        help="one GitHub matrix row as JSON; only its locked identity fields are trusted",
    )
    parser.add_argument("--artifact-node", help="restrict to one artifact node")
    parser.add_argument("--runtime-version", help="restrict to one runtime version")
    parser.add_argument("--loader", choices=("fabric", "forge", "neoforge"))
    parser.add_argument(
        "--scenarios",
        help="comma-separated scenario override (scheduled jobs pass all four here)",
    )
    parser.add_argument("--output-root", type=Path, default=Path("e2e-out"))
    parser.add_argument("--packaged", action="store_true", help="required acknowledgement")
    parser.add_argument("--list", action="store_true", help="print resolved logical rows and exit")
    return parser.parse_args()


def absolute(path: Path) -> Path:
    return path.resolve() if path.is_absolute() else (REPO / path).resolve()


def select_rows(data: dict[str, Any], args: argparse.Namespace) -> list[dict[str, Any]]:
    rows = list(data["runtimes"])
    if args.row_json:
        try:
            requested = json.loads(args.row_json)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid --row-json: {exc}") from exc
        if not isinstance(requested, dict):
            raise ValueError("--row-json must contain one JSON object")
        identity = (
            requested.get("artifact_node"),
            requested.get("runtime_version"),
            requested.get("loader"),
        )
        rows = [
            row
            for row in rows
            if (row["artifact_node"], row["runtime_version"], row["loader"]) == identity
        ]
        if len(rows) != 1:
            raise ValueError(f"--row-json does not identify exactly one locked runtime row: {identity}")
    if args.artifact_node:
        rows = [row for row in rows if row["artifact_node"] == args.artifact_node]
    if args.runtime_version:
        rows = [row for row in rows if row["runtime_version"] == args.runtime_version]
    if args.loader:
        rows = [row for row in rows if row["loader"] == args.loader]
    if not rows:
        raise ValueError("runtime selection is empty")
    return rows


def scenarios_for(data: dict[str, Any], row: dict[str, Any], args: argparse.Namespace) -> list[str]:
    scenarios = (
        [value.strip() for value in args.scenarios.split(",") if value.strip()]
        if args.scenarios
        else [row["scenario"]]
    )
    known = set(data["scheduled_scenarios"])
    unknown = [scenario for scenario in scenarios if scenario not in known]
    if unknown:
        raise ValueError(f"unknown E2E scenarios: {unknown}; known: {sorted(known)}")
    return scenarios


def read_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read artifact manifest {path}: {exc}") from exc
    if manifest.get("schema_version") != 1 or len(manifest.get("artifacts", [])) != 10:
        raise ValueError("artifact manifest must be schema 1 with exactly 10 production records")
    return manifest


def manifest_hash(manifest: dict[str, Any] | None, node: str) -> str:
    if manifest is None:
        return "from:artifact-manifest"
    records = [record for record in manifest["artifacts"] if record.get("artifact_node") == node]
    if len(records) != 1:
        raise ValueError(f"artifact manifest has {len(records)} records for {node}")
    return records[0]["sha256"]


def print_rows(
    data: dict[str, Any], rows: list[dict[str, Any]], args: argparse.Namespace, manifest: dict[str, Any] | None
) -> None:
    resolved: list[dict[str, Any]] = []
    for row in rows:
        for scenario in scenarios_for(data, row, args):
            resolved.append(
                {
                    "artifact_node": row["artifact_node"],
                    "runtime_version": row["runtime_version"],
                    "loader": row["loader"],
                    "scenario": scenario,
                    "jar_sha256": manifest_hash(manifest, row["artifact_node"]),
                    "port": 0,
                    "architectury_kind": row["architectury"]["kind"],
                }
            )
    print(json.dumps({"include": resolved}, indent=2))


def main() -> int:
    args = parse_args()
    matrix_path = absolute(args.matrix)
    manifest_path = absolute(args.artifacts_manifest)
    output_root = absolute(args.output_root)
    try:
        data = load_matrix(matrix_path)
        rows = select_rows(data, args)
        manifest = read_manifest(manifest_path) if manifest_path.exists() else None
        if args.list:
            print_rows(data, rows, args, manifest)
            return 0
        if not args.packaged:
            raise ValueError(
                "the development-run launcher was retired; pass --packaged to run fan-in jars"
            )
        if manifest is None:
            raise ValueError(f"packaged execution requires {manifest_path}")

        results: list[dict[str, Any]] = []
        for row in rows:
            for scenario in scenarios_for(data, row, args):
                print(
                    f">>> {row['artifact_node']} artifact on {row['runtime_version']} "
                    f"{row['loader']} / {scenario}",
                    flush=True,
                )
                result = run_packaged_row(
                    REPO,
                    data,
                    row,
                    scenario,
                    manifest,
                    manifest_path,
                    output_root,
                )
                results.append(result)
                print(
                    f"<<< {result['status'].upper()} ({result['elapsed_s']}s)"
                    + (f": {result['error']}" if result.get("error") else ""),
                    flush=True,
                )

        output_root.mkdir(parents=True, exist_ok=True)
        resolved = [
            {
                key: result[key]
                for key in (
                    "artifact_node",
                    "runtime_version",
                    "loader",
                    "scenario",
                    "jar_sha256",
                    "port",
                )
            }
            for result in results
        ]
        (output_root / "resolved-matrix.json").write_text(
            json.dumps({"rows": resolved}, indent=2) + "\n", encoding="utf-8"
        )
        (output_root / "summary.json").write_text(
            json.dumps({"results": results}, indent=2) + "\n", encoding="utf-8"
        )
        passed = sum(result["status"] == "pass" for result in results)
        print(f"{passed}/{len(results)} packaged runtime rows passed")
        return 0 if passed == len(results) else 1
    except (MatrixError, ValueError, OSError) as exc:
        print(f"E2E configuration failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
