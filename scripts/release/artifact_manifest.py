#!/usr/bin/env python3
"""Authoritative fail-closed contract for Quick Skin artifact manifest schema 2."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 2
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
SBOM_FILENAME = "quick-skin.cdx.json"
SBOM_PATH = f"sbom/{SBOM_FILENAME}"
SHA1 = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
SHA512 = re.compile(r"[0-9a-f]{128}")
GIT_COMMIT = re.compile(r"[0-9a-f]{40}(?:[0-9a-f]{24})?")


class ArtifactManifestError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ArtifactManifestError(message)


def file_digest(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_sha256(path: Path) -> str:
    return file_digest(path, "sha256")


def current_git_commit(repository: Path) -> str:
    try:
        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=repository,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ArtifactManifestError("cannot resolve the checked-out commit") from exc
    require(GIT_COMMIT.fullmatch(commit) is not None, "checked-out commit is invalid")
    return commit


def validate_manifest_location(manifest_path: Path, stage: Path) -> Path:
    """Require the manifest to be a direct child of the stage that owns its paths."""

    stage_root = stage.resolve()
    require(
        manifest_path.parent.resolve() == stage_root,
        "artifact manifest must be stored directly in the release stage",
    )
    if manifest_path.exists():
        require(
            manifest_path.is_file() and not manifest_path.is_symlink(),
            "artifact manifest must be a regular non-symlink file",
        )
        require(
            manifest_path.resolve().parent == stage_root,
            "artifact manifest escapes the release stage",
        )
    return stage_root


def _read_manifest(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), f"artifact manifest is missing: {path}")
    require(path.stat().st_size <= MAX_MANIFEST_BYTES, "artifact manifest is too large")
    try:
        raw = path.read_bytes()
        require(len(raw) <= MAX_MANIFEST_BYTES, "artifact manifest is too large")
        value = json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ArtifactManifestError(f"cannot read artifact manifest {path}: {exc}") from exc
    require(isinstance(value, dict), "artifact manifest root must be an object")
    return value


def _non_empty_string(value: Any, label: str) -> str:
    require(isinstance(value, str) and bool(value), f"{label} must be a non-empty string")
    return value


def _positive_integer(value: Any, label: str) -> int:
    require(type(value) is int and value > 0, f"{label} must be a positive integer")
    return value


def _hash(value: Any, pattern: re.Pattern[str], label: str) -> str:
    require(
        isinstance(value, str) and pattern.fullmatch(value) is not None,
        f"{label} is invalid",
    )
    return value


def _version_key(value: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in value.split("."))
    except ValueError as exc:
        raise ArtifactManifestError(f"invalid Minecraft version in manifest contract: {value!r}") from exc


def _expected_name(template: Any, mod_version: str, label: str) -> str:
    template_text = _non_empty_string(template, label)
    return Path(template_text.replace("{mod_version}", mod_version)).name


def _stage_file(stage: Path, relative: str, label: str) -> Path:
    stage_root = stage.resolve()
    candidate = stage_root / relative
    resolved = candidate.resolve()
    require(stage_root in resolved.parents, f"{label} escapes the release stage: {relative}")
    require(
        candidate.is_file() and not candidate.is_symlink(),
        f"{label} is missing or is not a regular file: {candidate}",
    )
    return candidate


def _verify_file(
    stage: Path,
    relative: str,
    label: str,
    byte_count: int,
    hashes: tuple[tuple[str, str], ...],
) -> None:
    path = _stage_file(stage, relative, label)
    require(path.stat().st_size == byte_count, f"{label} byte count mismatch")
    for algorithm, expected in hashes:
        require(file_digest(path, algorithm) == expected, f"{label} {algorithm.upper()} mismatch")


def validate_artifact_manifest(
    manifest: dict[str, Any],
    *,
    repository: Path,
    matrix_path: Path,
    matrix: dict[str, Any],
    stage: Path,
    expected_mod_version: str | None = None,
    expected_commit: str | None = None,
    expected_release: dict[str, Any] | None = None,
    require_sbom: bool = True,
    verify_files: bool = True,
) -> dict[str, dict[str, Any]]:
    """Validate all schema fields and return records indexed by artifact node."""

    require(isinstance(manifest, dict), "artifact manifest root must be an object")
    require(
        type(manifest.get("schema_version")) is int
        and manifest["schema_version"] == SCHEMA_VERSION,
        f"artifact manifest schema_version must be {SCHEMA_VERSION}",
    )

    repository_root = repository.resolve()
    resolved_matrix = matrix_path.resolve()
    try:
        expected_matrix_path = resolved_matrix.relative_to(repository_root).as_posix()
    except ValueError as exc:
        raise ArtifactManifestError("release matrix escapes the repository") from exc
    require(resolved_matrix.is_file(), f"release matrix is missing: {resolved_matrix}")
    require(manifest.get("matrix") == expected_matrix_path, "artifact manifest matrix path mismatch")
    matrix_hash = _hash(manifest.get("matrix_sha256"), SHA256, "artifact manifest matrix SHA-256")
    require(matrix_hash == file_sha256(resolved_matrix), "artifact manifest matrix SHA-256 mismatch")

    matrix_lane_count = matrix.get("lane_count")
    require(
        type(matrix_lane_count) is int and matrix_lane_count > 0,
        "release matrix lane_count must be a positive integer",
    )
    lane_count = _positive_integer(manifest.get("lane_count"), "artifact manifest lane_count")
    require(lane_count == matrix_lane_count, "artifact manifest lane count mismatch")

    mod_version = _non_empty_string(manifest.get("mod_version"), "artifact manifest mod_version")
    if expected_mod_version is not None:
        require(mod_version == expected_mod_version, "artifact manifest mod version mismatch")
    commit = _hash(manifest.get("git_commit"), GIT_COMMIT, "artifact manifest git commit")
    if expected_commit is not None:
        require(commit == expected_commit, "artifact manifest commit mismatch")

    release = manifest.get("release")
    require(isinstance(release, dict), "artifact manifest release identity must be an object")
    release_id = _non_empty_string(release.get("release_id"), "release.release_id")
    require(release.get("tag") == release_id, "artifact manifest release tag mismatch")
    project = matrix.get("project")
    require(isinstance(project, dict), "release matrix project must be an object")
    require(
        release.get("branch") == project.get("release_branch"),
        "artifact manifest release branch mismatch",
    )
    require(release.get("mod_version") == mod_version, "artifact manifest release version mismatch")
    matrix_artifacts = matrix.get("artifacts")
    require(isinstance(matrix_artifacts, list), "release matrix artifacts must be a list")
    require(
        all(isinstance(row, dict) for row in matrix_artifacts),
        "release matrix contains a non-object artifact",
    )
    expected_versions = sorted(
        {_non_empty_string(row.get("artifact_version"), "matrix artifact version") for row in matrix_artifacts},
        key=_version_key,
    )
    require(
        release.get("minecraft_versions") == expected_versions,
        "artifact manifest release Minecraft versions mismatch",
    )
    if expected_release is not None:
        require(release == expected_release, "artifact manifest release identity mismatch")

    expected_by_node: dict[str, dict[str, Any]] = {}
    for artifact in matrix_artifacts:
        require(isinstance(artifact, dict), "release matrix contains a non-object artifact")
        node = _non_empty_string(artifact.get("artifact_node"), "matrix artifact node")
        require(node not in expected_by_node, f"release matrix contains duplicate artifact node {node}")
        expected_by_node[node] = artifact
    require(len(expected_by_node) == lane_count, "release matrix artifact inventory mismatch")

    records = manifest.get("artifacts")
    require(
        isinstance(records, list) and len(records) == lane_count,
        f"artifact manifest must contain lane_count={lane_count} records",
    )
    record_by_node: dict[str, dict[str, Any]] = {}
    production_hashes: set[str] = set()
    production_paths: set[str] = set()
    harness_paths: set[str] = set()
    for record in records:
        require(isinstance(record, dict), "artifact manifest contains a non-object record")
        node = _non_empty_string(record.get("artifact_node"), "artifact record node")
        require(node in expected_by_node, f"artifact manifest contains unknown node {node}")
        require(node not in record_by_node, f"artifact manifest contains duplicate node {node}")
        artifact = expected_by_node[node]
        require(
            record.get("artifact_version") == artifact.get("artifact_version"),
            f"artifact version mismatch for {node}",
        )
        require(record.get("loader") == artifact.get("loader"), f"artifact loader mismatch for {node}")
        require(
            record.get("game_versions") == artifact.get("game_versions"),
            f"artifact game versions mismatch for {node}",
        )

        expected_filename = _expected_name(artifact.get("jar"), mod_version, f"matrix jar for {node}")
        expected_path = f"files/{expected_filename}"
        require(record.get("filename") == expected_filename, f"artifact filename mismatch for {node}")
        require(record.get("path") == expected_path, f"artifact path mismatch for {node}")
        byte_count = _positive_integer(record.get("bytes"), f"artifact byte count for {node}")
        sha1 = _hash(record.get("sha1"), SHA1, f"artifact SHA-1 for {node}")
        sha256 = _hash(record.get("sha256"), SHA256, f"artifact SHA-256 for {node}")
        sha512 = _hash(record.get("sha512"), SHA512, f"artifact SHA-512 for {node}")

        harness = record.get("harness")
        require(isinstance(harness, dict), f"artifact harness record is missing for {node}")
        expected_harness_filename = _expected_name(
            artifact.get("harness_jar"), mod_version, f"matrix harness jar for {node}"
        )
        expected_harness_path = f"harness/{expected_harness_filename}"
        require(
            harness.get("filename") == expected_harness_filename,
            f"artifact harness filename mismatch for {node}",
        )
        require(harness.get("path") == expected_harness_path, f"artifact harness path mismatch for {node}")
        harness_bytes = _positive_integer(
            harness.get("bytes"), f"artifact harness byte count for {node}"
        )
        harness_sha256 = _hash(
            harness.get("sha256"), SHA256, f"artifact harness SHA-256 for {node}"
        )

        require(expected_path not in production_paths, f"duplicate staged artifact path {expected_path}")
        require(expected_harness_path not in harness_paths, f"duplicate staged harness path {expected_harness_path}")
        require(sha256 not in production_hashes, "production jar hashes are not unique")
        production_paths.add(expected_path)
        harness_paths.add(expected_harness_path)
        production_hashes.add(sha256)
        record_by_node[node] = record

        if verify_files:
            _verify_file(
                stage,
                expected_path,
                f"staged artifact {node}",
                byte_count,
                (("sha1", sha1), ("sha256", sha256), ("sha512", sha512)),
            )
            _verify_file(
                stage,
                expected_harness_path,
                f"staged harness {node}",
                harness_bytes,
                (("sha256", harness_sha256),),
            )
    require(set(record_by_node) == set(expected_by_node), "artifact manifest node inventory mismatch")

    if require_sbom:
        sbom = manifest.get("sbom")
        require(isinstance(sbom, dict), "artifact manifest SBOM record is missing")
        require(sbom.get("format") == "CycloneDX", "artifact manifest SBOM format mismatch")
        require(sbom.get("spec_version") == "1.6", "artifact manifest SBOM version mismatch")
        require(sbom.get("filename") == SBOM_FILENAME, "artifact manifest SBOM filename mismatch")
        require(sbom.get("path") == SBOM_PATH, "artifact manifest SBOM path mismatch")
        sbom_bytes = _positive_integer(sbom.get("bytes"), "artifact manifest SBOM byte count")
        sbom_sha256 = _hash(sbom.get("sha256"), SHA256, "artifact manifest SBOM SHA-256")
        if verify_files:
            _verify_file(
                stage,
                SBOM_PATH,
                "staged SBOM",
                sbom_bytes,
                (("sha256", sbom_sha256),),
            )

    return record_by_node


def load_artifact_manifest(
    manifest_path: Path,
    *,
    repository: Path,
    matrix_path: Path,
    matrix: dict[str, Any],
    stage: Path,
    expected_mod_version: str | None = None,
    expected_commit: str | None = None,
    expected_release: dict[str, Any] | None = None,
    require_sbom: bool = True,
    verify_files: bool = True,
) -> dict[str, Any]:
    validate_manifest_location(manifest_path, stage)
    manifest = _read_manifest(manifest_path)
    validate_artifact_manifest(
        manifest,
        repository=repository,
        matrix_path=matrix_path,
        matrix=matrix,
        stage=stage,
        expected_mod_version=expected_mod_version,
        expected_commit=expected_commit,
        expected_release=expected_release,
        require_sbom=require_sbom,
        verify_files=verify_files,
    )
    return manifest
