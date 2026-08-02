#!/usr/bin/env python3
"""Build the advisory AI manifest from authoritative packaged-E2E result files.

Each frame is identified by artifact/scenario/role/step. Filenames are payload metadata,
never identity, so visually similar captures from different scenarios cannot collapse.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from visual_evidence import (
    DEFAULT_CATALOG,
    REPO,
    VisualEvidenceError,
    collect_evidence,
    load_catalog,
)


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
) -> list[dict[str, str]]:
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
        }
        for frame in frames
        if (include_all or frame["review_tier"] == "key")
        and (combos is None or (frame["version"], frame["loader"]) in combos)
    ]
    if not manifest:
        raise VisualEvidenceError("visual review manifest would be empty")
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="include every catalogued capture")
    parser.add_argument(
        "--combos",
        help="comma-separated <version>/<loader> filter (for example 1.20.1/fabric)",
    )
    parser.add_argument("--e2e-root", type=Path, default=REPO / "e2e-out")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    args = parser.parse_args(argv)
    try:
        manifest = build_manifest(
            args.e2e_root,
            args.catalog,
            include_all=args.all,
            combos=parse_combos(args.combos),
        )
    except VisualEvidenceError as exc:
        parser.error(str(exc))
    json.dump(manifest, sys.stdout, indent=2)
    print(f"\n# {len(manifest)} screenshots", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
