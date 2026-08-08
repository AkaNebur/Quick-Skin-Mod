#!/usr/bin/env python3
"""Reconcile one verified JAR with one marketplace before or after publication."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from matrix import MatrixError, load_matrix


MODRINTH_API = "https://api.modrinth.com/v2"
CURSEFORGE_API = "https://api.curseforge.com/v1"
MAX_CURSEFORGE_FILES = 2_000
REQUEST_ATTEMPTS = 5
REQUEST_BACKOFF_SECONDS = (2.0, 2.5, 3.0, 4.0)


class ReconciliationError(RuntimeError):
    pass


@dataclass(frozen=True)
class ExpectedArtifact:
    node: str
    filename: str
    path: Path
    bytes: int
    sha1: str
    sha256: str
    sha512: str


@dataclass(frozen=True)
class Reconciliation:
    publish: bool
    remote_id: str | None


def load_expected(manifest_path: Path, stage: Path, node: str) -> ExpectedArtifact:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReconciliationError(f"cannot read artifact manifest: {exc}") from exc
    if manifest.get("schema_version") != 2:
        raise ReconciliationError("publication requires artifact manifest schema 2")
    records = [row for row in manifest.get("artifacts", []) if row.get("artifact_node") == node]
    if len(records) != 1:
        raise ReconciliationError(f"expected exactly one manifest record for {node}")
    record = records[0]
    relative = record.get("path")
    if not isinstance(relative, str) or not relative:
        raise ReconciliationError("artifact path is missing")
    root = stage.resolve()
    path = (root / relative).resolve()
    if root not in path.parents or not path.is_file():
        raise ReconciliationError("artifact path escapes the verified stage or is missing")
    return ExpectedArtifact(
        node=node,
        filename=str(record.get("filename", "")),
        path=path,
        bytes=int(record.get("bytes", -1)),
        sha1=str(record.get("sha1", "")),
        sha256=str(record.get("sha256", "")),
        sha512=str(record.get("sha512", "")),
    )


def classify_modrinth(
    hash_version: dict[str, Any] | None,
    project_versions: list[dict[str, Any]],
    expected: ExpectedArtifact,
    project_id: str,
    publication_id: str,
) -> Reconciliation:
    conflicting_versions = [
        version for version in project_versions
        if version.get("version_number") == publication_id
    ]
    if hash_version is None:
        if conflicting_versions:
            raise ReconciliationError(
                f"Modrinth version {publication_id} exists with different bytes"
            )
        return Reconciliation(True, None)

    files = hash_version.get("files", [])
    exact_file = any(
        file.get("filename") == expected.filename
        and file.get("size") == expected.bytes
        and file.get("hashes", {}).get("sha512") == expected.sha512
        for file in files
        if isinstance(file, dict)
    )
    if (
        hash_version.get("project_id") != project_id
        or hash_version.get("version_number") != publication_id
        or not exact_file
    ):
        raise ReconciliationError(
            "the expected Modrinth file hash is already bound to another publication identity"
        )
    if len(conflicting_versions) != 1 or conflicting_versions[0].get("id") != hash_version.get("id"):
        raise ReconciliationError("Modrinth publication identity is ambiguous")
    return Reconciliation(False, str(hash_version.get("id")))


def classify_curseforge(
    files: list[dict[str, Any]], expected: ExpectedArtifact
) -> Reconciliation:
    named = [row for row in files if row.get("fileName") == expected.filename]
    if not named:
        return Reconciliation(True, None)
    if len(named) != 1:
        raise ReconciliationError("CurseForge contains duplicate files with the expected name")
    remote = named[0]
    hashes = {
        int(item.get("algo", -1)): str(item.get("value", "")).lower()
        for item in remote.get("hashes", [])
        if isinstance(item, dict)
    }
    if hashes.get(1) != expected.sha1 or remote.get("fileLength") != expected.bytes:
        raise ReconciliationError(
            f"CurseForge file {expected.filename} exists with different bytes"
        )
    return Reconciliation(False, str(remote.get("id")))


def request_json(
    url: str,
    headers: dict[str, str],
    *,
    allow_not_found: bool = False,
    sleep: Callable[[float], None] = time.sleep,
) -> Any:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "Quick-Skin-release-reconciler/1",
            **headers,
        },
    )
    # Only transient failures (timeouts, connection errors, HTTP 5xx) earn a bounded retry.
    # A definitive marketplace rejection (any other 4xx) must fail immediately.
    last_error: Exception | None = None
    for attempt in range(REQUEST_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            if allow_not_found and exc.code == 404:
                return None
            if exc.code < 500:
                raise ReconciliationError(f"marketplace API returned HTTP {exc.code}") from exc
            last_error = exc
        except (urllib.error.URLError, TimeoutError) as exc:
            last_error = exc
        except (OSError, json.JSONDecodeError) as exc:
            raise ReconciliationError(f"marketplace API request failed: {exc}") from exc
        if attempt + 1 < REQUEST_ATTEMPTS:
            print(
                f"marketplace API attempt {attempt + 1}/{REQUEST_ATTEMPTS} failed "
                f"transiently ({last_error}); retrying",
                file=sys.stderr,
            )
            sleep(REQUEST_BACKOFF_SECONDS[min(attempt, len(REQUEST_BACKOFF_SECONDS) - 1)])
    raise ReconciliationError(
        f"marketplace API request failed after {REQUEST_ATTEMPTS} attempts: {last_error}"
    ) from last_error


def inspect_modrinth(
    expected: ExpectedArtifact,
    project_id: str,
    publication_id: str,
    token: str,
) -> Reconciliation:
    headers = {"Authorization": token} if token else {}
    encoded_hash = urllib.parse.quote(expected.sha512, safe="")
    hash_version = request_json(
        f"{MODRINTH_API}/version_file/{encoded_hash}?algorithm=sha512",
        headers,
        allow_not_found=True,
    )
    encoded_project = urllib.parse.quote(project_id, safe="")
    versions = request_json(f"{MODRINTH_API}/project/{encoded_project}/version", headers)
    if not isinstance(versions, list):
        raise ReconciliationError("unexpected Modrinth versions response")
    return classify_modrinth(hash_version, versions, expected, project_id, publication_id)


def inspect_curseforge(
    expected: ExpectedArtifact,
    project_id: int,
    token: str,
) -> Reconciliation:
    if not token:
        raise ReconciliationError("CURSEFORGE_TOKEN is required for reconciliation")
    headers = {"x-api-key": token}
    files: list[dict[str, Any]] = []
    index = 0
    while index < MAX_CURSEFORGE_FILES:
        query = urllib.parse.urlencode({"index": index, "pageSize": 50})
        response = request_json(
            f"{CURSEFORGE_API}/mods/{project_id}/files?{query}", headers
        )
        page = response.get("data", []) if isinstance(response, dict) else []
        if not isinstance(page, list):
            raise ReconciliationError("unexpected CurseForge files response")
        files.extend(row for row in page if isinstance(row, dict))
        pagination = response.get("pagination", {}) if isinstance(response, dict) else {}
        total = int(pagination.get("totalCount", len(files)))
        if not page or len(files) >= total:
            break
        index += len(page)
    if index >= MAX_CURSEFORGE_FILES:
        raise ReconciliationError("CurseForge file inventory exceeded the reconciliation bound")
    return classify_curseforge(files, expected)


def settle_publication(
    inspector: Callable[[], Reconciliation],
    *,
    verify: bool,
    attempts: int,
    delay_seconds: float,
    sleep: Callable[[float], None] = time.sleep,
) -> Reconciliation:
    # Marketplace indexing lags a successful upload, so --verify polls briefly before
    # declaring the publication missing; the non-verify path inspects exactly once.
    total = max(1, attempts if verify else 1)
    result = Reconciliation(True, None)
    for attempt in range(total):
        result = inspector()
        if not verify or not result.publish:
            return result
        if attempt + 1 < total:
            print(
                f"publication not yet observable by hash "
                f"(attempt {attempt + 1}/{total}); waiting",
                file=sys.stderr,
            )
            sleep(max(0.0, delay_seconds))
    raise ReconciliationError("published marketplace file was not observable by hash")


def write_output(path: Path, result: Reconciliation) -> None:
    with path.open("a", encoding="utf-8") as output:
        output.write(f"publish={'true' if result.publish else 'false'}\n")
        output.write(f"existing_id={result.remote_id or ''}\n")


def write_record(
    path: Path,
    marketplace: str,
    publication_id: str,
    expected: ExpectedArtifact,
    result: Reconciliation,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        "schema_version": 1,
        "marketplace": marketplace,
        "publication_id": publication_id,
        "artifact_node": expected.node,
        "filename": expected.filename,
        "sha256": expected.sha256,
        "remote_id": result.remote_id,
        "verified": not result.publish,
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--manifest", type=Path, default=Path("build/release/artifacts.json"))
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    parser.add_argument("--artifact-node", required=True)
    parser.add_argument("--marketplace", choices=("modrinth", "curseforge"), required=True)
    parser.add_argument("--publication-id", required=True)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--attempts", type=int, default=6)
    parser.add_argument("--delay-seconds", type=float, default=10.0)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--record", type=Path)
    args = parser.parse_args()

    repository = Path(__file__).resolve().parents[2]
    matrix_path = args.matrix if args.matrix.is_absolute() else repository / args.matrix
    manifest_path = args.manifest if args.manifest.is_absolute() else repository / args.manifest
    stage = args.stage if args.stage.is_absolute() else repository / args.stage
    try:
        matrix = load_matrix(matrix_path)
        expected = load_expected(manifest_path, stage, args.artifact_node)
        inspector: Callable[[], Reconciliation]
        if args.marketplace == "modrinth":
            inspector = lambda: inspect_modrinth(
                expected,
                str(matrix["project"]["modrinth_id"]),
                args.publication_id,
                os.environ.get("MODRINTH_TOKEN", ""),
            )
        else:
            inspector = lambda: inspect_curseforge(
                expected,
                int(matrix["project"]["curseforge_id"]),
                os.environ.get("CURSEFORGE_TOKEN", ""),
            )

        result = settle_publication(
            inspector,
            verify=args.verify,
            attempts=args.attempts,
            delay_seconds=args.delay_seconds,
        )
        if args.github_output:
            write_output(args.github_output, result)
        if args.record:
            write_record(
                args.record, args.marketplace, args.publication_id, expected, result
            )
        print(json.dumps({
            "artifact_node": expected.node,
            "marketplace": args.marketplace,
            "publication_id": args.publication_id,
            "publish": result.publish,
            "remote_id": result.remote_id,
            "sha256": expected.sha256,
        }, separators=(",", ":"), sort_keys=True))
    except (MatrixError, ReconciliationError, OSError, ValueError) as exc:
        print(f"publication reconciliation failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
