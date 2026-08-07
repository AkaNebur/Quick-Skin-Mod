#!/usr/bin/env python3
"""Build the advisory AI manifest from authoritative packaged-E2E result files.

Each frame is identified by artifact/scenario/role/step. Filenames are payload metadata,
never identity, so visually similar captures from different scenarios cannot collapse.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

from visual_evidence import (
    DEFAULT_CATALOG,
    REPO,
    VisualEvidenceError,
    canonicalize_png_snapshot,
    collect_evidence,
    load_catalog,
)


MAX_REVIEW_FRAMES = 512
# Leave explicit headroom inside the 512 MiB handoff envelope for the
# manifest, proof, ZIP metadata, and directory entries.
MAX_REVIEW_IMAGE_BYTES = 480 * 1024 * 1024
MAX_REVIEW_IMAGE_PIXELS = 512 * 1024 * 1024
SAFE_DIRECTORY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
PUBLIC_MANIFEST_FIELDS = ("path", "label", "capture_id", "kind", "expectation")


def parse_combos(raw: str | None) -> set[tuple[str, str]] | None:
    if raw is None:
        return None
    combos: set[tuple[str, str]] = set()
    for item in raw.split(","):
        parts = [part.strip() for part in item.strip().split("/")]
        if len(parts) != 2 or not all(parts):
            raise VisualEvidenceError(
                f"invalid combo {item!r}; expected comma-separated <version>/<loader> values"
            )
        combos.add((parts[0], parts[1]))
    if not combos:
        raise VisualEvidenceError("combo filter must not be empty")
    return combos


def build_manifest(
    e2e_root: Path,
    catalog_path: Path,
    *,
    include_all: bool,
    combos: set[tuple[str, str]] | None,
) -> list[dict[str, object]]:
    catalog = load_catalog(catalog_path)
    _, frames, _ = collect_evidence(e2e_root, catalog)
    available = {(frame["version"], frame["loader"]) for frame in frames}
    if combos is not None:
        unknown = combos - available
        if unknown:
            formatted = ", ".join(f"{version}/{loader}" for version, loader in sorted(unknown))
            raise VisualEvidenceError(f"combo filter matched no packaged evidence: {formatted}")
    manifest = [
        {
            "path": frame["source_path"],
            "label": frame["frame_id"],
            "capture_id": frame["capture_id"],
            "kind": frame["capture_id"],
            "expectation": frame["expectation"],
            "_verified_file_sha256": frame["file_sha256"],
            "_verified_pixel_sha256": frame["pixel_validation"]["pixel_sha256"],
            "_verified_width": frame["width"],
            "_verified_height": frame["height"],
        }
        for frame in frames
        if (include_all or frame["review_tier"] == "key")
        and (combos is None or (frame["version"], frame["loader"]) in combos)
    ]
    if not manifest:
        raise VisualEvidenceError("visual review manifest would be empty")
    return manifest


def public_manifest(manifest: list[dict[str, object]]) -> list[dict[str, str]]:
    """Discard curator-only snapshot identities before exposing review instructions."""

    public: list[dict[str, str]] = []
    for index, item in enumerate(manifest):
        if any(not isinstance(item.get(field), str) for field in PUBLIC_MANIFEST_FIELDS):
            raise VisualEvidenceError(f"visual review manifest entry {index} is invalid")
        public.append({field: str(item[field]) for field in PUBLIC_MANIFEST_FIELDS})
    return public


def validate_expected_row(
    e2e_root: Path,
    catalog_path: Path,
    row: object,
) -> dict[str, object]:
    """Bind one artifact's complete evidence to one protected matrix row."""

    if not isinstance(row, dict):
        raise VisualEvidenceError("expected matrix row must be an object")
    row_id = row.get("id")
    artifact_node = row.get("artifact_node")
    runtime_version = row.get("runtime_version")
    loader = row.get("loader")
    raw_scenarios = row.get("scenarios")
    if any(
        not isinstance(value, str) or not SAFE_ID.fullmatch(value)
        for value in (row_id, artifact_node, runtime_version, loader)
    ) or loader not in {"fabric", "forge", "neoforge"}:
        raise VisualEvidenceError("expected matrix row has an invalid identity")
    if not isinstance(raw_scenarios, str):
        raise VisualEvidenceError("expected matrix row has no scenario coverage")
    scenarios = tuple(raw_scenarios.split(","))
    if (
        not scenarios
        or len(scenarios) != len(set(scenarios))
        or any(not SAFE_ID.fullmatch(scenario) for scenario in scenarios)
    ):
        raise VisualEvidenceError("expected matrix row has invalid scenario coverage")

    catalog = load_catalog(catalog_path)
    lanes, _frames, _comparisons = collect_evidence(e2e_root, catalog)
    observed = {
        (
            lane["artifact_node"],
            lane["version"],
            lane["loader"],
            lane["scenario"],
        )
        for lane in lanes
    }
    expected = {
        (artifact_node, runtime_version, loader, scenario) for scenario in scenarios
    }
    if observed != expected or len(lanes) != len(expected):
        raise VisualEvidenceError(
            f"artifact evidence disagrees with protected matrix row {row_id}: "
            f"missing={sorted(expected - observed)}, extra={sorted(observed - expected)}"
        )
    jar_digests = {lane["jar_sha256"] for lane in lanes}
    if len(jar_digests) != 1:
        raise VisualEvidenceError(
            f"artifact evidence uses multiple production JARs for matrix row {row_id}"
        )
    return {
        "schema_version": 1,
        "row_id": row_id,
        "artifact_node": artifact_node,
        "runtime_version": runtime_version,
        "loader": loader,
        "scenarios": list(scenarios),
        "lane_count": len(lanes),
        "jar_sha256": next(iter(jar_digests)),
    }


def curate_manifest(
    manifest: list[dict[str, object]], output_root: Path
) -> list[dict[str, str]]:
    """Atomically retain only reviewed PNGs and rewrite paths for a fresh runner."""

    if not manifest or len(manifest) > MAX_REVIEW_FRAMES:
        raise VisualEvidenceError(
            f"visual review frame count is outside 1..{MAX_REVIEW_FRAMES}"
        )
    destination = output_root.absolute()
    if not SAFE_DIRECTORY.fullmatch(destination.name):
        raise VisualEvidenceError("curated review output must have a portable directory name")
    if destination.exists() or destination.is_symlink():
        raise VisualEvidenceError(
            f"curated review output must not already exist: {destination}"
        )
    try:
        parent = destination.parent.resolve(strict=True)
        staging = Path(
            tempfile.mkdtemp(
                prefix=f".{destination.name}.curating-",
                dir=parent,
            )
        )
    except OSError as exc:
        raise VisualEvidenceError(f"cannot create curated review staging: {exc}") from exc

    curated: list[dict[str, str]] = []
    total_bytes = 0
    total_pixels = 0
    copied: dict[str, Path] = {}
    try:
        images = staging / "images"
        images.mkdir(mode=0o700)
        for index, item in enumerate(manifest):
            source_value = item.get("path")
            if not isinstance(source_value, str):
                raise VisualEvidenceError(f"review frame {index} has no source path")
            source = Path(source_value)
            (
                dimensions,
                source_digest,
                pixel_digest,
                digest,
                payload,
            ) = canonicalize_png_snapshot(source)
            expected_dimensions = (
                item.get("_verified_width"),
                item.get("_verified_height"),
            )
            if (
                source_digest != item.get("_verified_file_sha256")
                or pixel_digest != item.get("_verified_pixel_sha256")
                or dimensions != expected_dimensions
            ):
                raise VisualEvidenceError(
                    f"review frame changed after evidence validation: {source}"
                )
            total_pixels += dimensions[0] * dimensions[1]
            if total_pixels > MAX_REVIEW_IMAGE_PIXELS:
                raise VisualEvidenceError(
                    "curated visual review exceeds its total pixel limit"
                )
            asset = copied.get(digest)
            if asset is None:
                total_bytes += len(payload)
                if total_bytes > MAX_REVIEW_IMAGE_BYTES:
                    raise VisualEvidenceError(
                        "curated visual review exceeds its total image byte limit"
                    )
                asset = images / f"{digest}.png"
                with asset.open("xb") as output_stream:
                    output_stream.write(payload)
                    output_stream.flush()
                    os.fsync(output_stream.fileno())
                if asset.stat().st_size != len(payload):
                    raise VisualEvidenceError(
                        f"review frame changed while curating: {source}"
                    )
                os.chmod(asset, 0o644)
                copied[digest] = asset
            rewritten = public_manifest([item])[0]
            rewritten["path"] = f"{destination.name}/images/{digest}.png"
            curated.append(rewritten)

        manifest_path = staging / "visual-review-manifest.json"
        with manifest_path.open("x", encoding="utf-8") as handle:
            json.dump(curated, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(manifest_path, 0o644)
        os.replace(staging, destination)
    except (OSError, VisualEvidenceError) as exc:
        shutil.rmtree(staging, ignore_errors=True)
        if isinstance(exc, VisualEvidenceError):
            raise
        raise VisualEvidenceError(f"cannot curate visual review: {exc}") from exc
    return curated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="include every catalogued capture")
    parser.add_argument(
        "--combos",
        help="comma-separated <version>/<loader> filter (for example 1.20.1/fabric)",
    )
    parser.add_argument("--e2e-root", type=Path, default=REPO / "e2e-out")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument(
        "--curate-output",
        type=Path,
        help="atomically copy only selected frames into this fresh directory",
    )
    parser.add_argument(
        "--validate-row-json",
        help="validate this artifact against one protected matrix row and exit",
    )
    args = parser.parse_args(argv)
    try:
        if args.validate_row_json is not None:
            if args.curate_output is not None or args.all or args.combos is not None:
                raise VisualEvidenceError(
                    "--validate-row-json cannot be combined with manifest selection"
                )
            try:
                row = json.loads(args.validate_row_json)
            except json.JSONDecodeError as exc:
                raise VisualEvidenceError(f"invalid expected matrix row JSON: {exc}") from exc
            validated = validate_expected_row(args.e2e_root, args.catalog, row)
            print(json.dumps(validated, sort_keys=True, separators=(",", ":")))
            return 0
        manifest = build_manifest(
            args.e2e_root,
            args.catalog,
            include_all=args.all,
            combos=parse_combos(args.combos),
        )
        if args.curate_output is not None:
            manifest = curate_manifest(manifest, args.curate_output)
        else:
            manifest = public_manifest(manifest)
    except VisualEvidenceError as exc:
        parser.error(str(exc))
    json.dump(manifest, sys.stdout, indent=2, ensure_ascii=False)
    print()
    print(f"\n# {len(manifest)} screenshots", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
