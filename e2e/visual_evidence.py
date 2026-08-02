#!/usr/bin/env python3
"""Read validated packaged-E2E frames without inferring identity from filenames."""

from __future__ import annotations

import hashlib
import json
import math
import re
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parent.parent
DEFAULT_CATALOG = REPO / "e2e" / "visual-catalog.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SCREENSHOT_METRIC_FIELDS = frozenset(
    {
        "width",
        "height",
        "file_sha256",
        "pixel_sha256",
        "luma_entropy",
        "meaningful_colors",
        "dark_fraction",
        "light_fraction",
    }
)
COMPARISON_METRIC_FIELDS = frozenset(
    {"changed_fraction", "rms_difference", "required_changed_fraction", "region"}
)


class VisualEvidenceError(ValueError):
    """Raised when public visual evidence cannot be proven from packaged results."""


@dataclass(frozen=True)
class Catalog:
    captures: tuple[dict[str, str], ...]
    by_id: dict[str, dict[str, str]]
    by_key: dict[tuple[str, str, str], dict[str, str]]


def _read_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise VisualEvidenceError(f"cannot read {label} {path}: {exc}") from exc


def _nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise VisualEvidenceError(f"{label} must be a non-empty string")
    return value.strip()


def load_catalog(path: Path = DEFAULT_CATALOG) -> Catalog:
    data = _read_json(path, "visual catalog")
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        raise VisualEvidenceError("visual catalog schema_version must be 1")
    raw_captures = data.get("captures")
    if not isinstance(raw_captures, list) or not raw_captures:
        raise VisualEvidenceError("visual catalog captures must be a non-empty array")

    required = {
        "capture_id",
        "scenario",
        "role",
        "step",
        "title",
        "review_tier",
        "expectation",
    }
    captures: list[dict[str, str]] = []
    by_id: dict[str, dict[str, str]] = {}
    by_key: dict[tuple[str, str, str], dict[str, str]] = {}
    for index, raw in enumerate(raw_captures):
        if not isinstance(raw, dict) or set(raw) != required:
            raise VisualEvidenceError(
                f"visual catalog capture {index} must contain exactly {sorted(required)}"
            )
        capture = {key: _nonempty_string(raw[key], f"capture {index}.{key}") for key in required}
        scenario = capture["scenario"]
        role = capture["role"]
        step = capture["step"]
        capture_id = capture["capture_id"]
        for value, label in ((scenario, "scenario"), (role, "role"), (step, "step")):
            if not SAFE_ID.fullmatch(value):
                raise VisualEvidenceError(f"capture {index} has unsafe {label} {value!r}")
        if role not in {"client_a", "client_b"}:
            raise VisualEvidenceError(f"capture {index} has unsupported role {role!r}")
        if capture["review_tier"] not in {"all", "key"}:
            raise VisualEvidenceError(
                f"capture {index} has unsupported review_tier {capture['review_tier']!r}"
            )
        expected_id = f"{scenario}.{role}.{step}"
        if capture_id != expected_id:
            raise VisualEvidenceError(
                f"capture_id {capture_id!r} must equal semantic identity {expected_id!r}"
            )
        key = (scenario, role, step)
        if capture_id in by_id or key in by_key:
            raise VisualEvidenceError(f"duplicate visual catalog capture {capture_id!r}")
        captures.append(capture)
        by_id[capture_id] = capture
        by_key[key] = capture
    return Catalog(tuple(captures), by_id, by_key)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as exc:
        raise VisualEvidenceError(f"cannot hash screenshot {path}: {exc}") from exc
    return digest.hexdigest()


def png_dimensions(path: Path) -> tuple[int, int]:
    try:
        with path.open("rb") as handle:
            signature = handle.read(8)
            length = handle.read(4)
            chunk = handle.read(4)
            dimensions = handle.read(8)
    except OSError as exc:
        raise VisualEvidenceError(f"cannot read screenshot {path}: {exc}") from exc
    if signature != PNG_SIGNATURE or len(length) != 4 or chunk != b"IHDR":
        raise VisualEvidenceError(f"screenshot is not a PNG with an IHDR header: {path}")
    if struct.unpack(">I", length)[0] != 13 or len(dimensions) != 8:
        raise VisualEvidenceError(f"screenshot has an invalid PNG IHDR header: {path}")
    width, height = struct.unpack(">II", dimensions)
    if width <= 0 or height <= 0:
        raise VisualEvidenceError(f"screenshot has invalid dimensions {width}x{height}: {path}")
    return width, height


def reject_symlinks(path: Path, boundary: Path, label: str) -> None:
    """Reject a symlink in any existing path component below a lexical boundary."""

    raw_boundary = boundary.absolute()
    raw_path = path.absolute()
    try:
        relative = raw_path.relative_to(raw_boundary)
    except ValueError as exc:
        raise VisualEvidenceError(f"{label} escapes {raw_boundary}: {raw_path}") from exc
    current = raw_boundary
    if current.is_symlink():
        raise VisualEvidenceError(f"{label} boundary is a symlink: {current}")
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise VisualEvidenceError(f"{label} contains a symlink: {current}")


def _safe_screenshot(profile: Path, role: str, filename: Any) -> Path:
    name = _nonempty_string(filename, "report screenshot")
    if Path(name).name != name or not name.lower().endswith(".png"):
        raise VisualEvidenceError(f"unsafe screenshot filename {name!r}")
    raw_screenshots = profile / role / "screenshots"
    raw_screenshot = raw_screenshots / name
    reject_symlinks(raw_screenshot, profile, "screenshot path")
    screenshots = raw_screenshots.resolve()
    screenshot = raw_screenshot.resolve()
    if screenshot.parent != screenshots or not screenshot.is_file():
        raise VisualEvidenceError(f"missing or escaping screenshot {screenshot}")
    return screenshot


def _copy_json_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise VisualEvidenceError(f"{label} must be an object")
    try:
        copied = json.loads(json.dumps(value, allow_nan=False))
    except (TypeError, ValueError) as exc:
        raise VisualEvidenceError(f"{label} is not finite JSON data: {exc}") from exc
    if not isinstance(copied, dict):  # pragma: no cover - guarded above
        raise VisualEvidenceError(f"{label} must be an object")
    return copied


def _finite_number(value: Any, label: str, *, minimum: float, maximum: float) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < minimum
        or value > maximum
    ):
        raise VisualEvidenceError(f"{label} must be a finite number in [{minimum}, {maximum}]")
    return value


def validate_screenshot_metrics(value: Any, label: str) -> dict[str, Any]:
    """Return the exact public screenshot metric schema, rejecting arbitrary payloads."""

    metrics = _copy_json_object(value, label)
    if set(metrics) != SCREENSHOT_METRIC_FIELDS:
        raise VisualEvidenceError(
            f"{label} must contain exactly {sorted(SCREENSHOT_METRIC_FIELDS)}"
        )
    width = metrics["width"]
    height = metrics["height"]
    meaningful_colors = metrics["meaningful_colors"]
    if isinstance(width, bool) or not isinstance(width, int) or width <= 0:
        raise VisualEvidenceError(f"{label}.width must be a positive integer")
    if isinstance(height, bool) or not isinstance(height, int) or height <= 0:
        raise VisualEvidenceError(f"{label}.height must be a positive integer")
    if (
        isinstance(meaningful_colors, bool)
        or not isinstance(meaningful_colors, int)
        or not 0 <= meaningful_colors <= 32
    ):
        raise VisualEvidenceError(f"{label}.meaningful_colors must be an integer in [0, 32]")
    for field in ("file_sha256", "pixel_sha256"):
        if not isinstance(metrics[field], str) or not SHA256.fullmatch(metrics[field]):
            raise VisualEvidenceError(f"{label}.{field} must be a lowercase SHA-256 digest")
    _finite_number(metrics["luma_entropy"], f"{label}.luma_entropy", minimum=0, maximum=8)
    _finite_number(metrics["dark_fraction"], f"{label}.dark_fraction", minimum=0, maximum=1)
    _finite_number(metrics["light_fraction"], f"{label}.light_fraction", minimum=0, maximum=1)
    return {field: metrics[field] for field in sorted(SCREENSHOT_METRIC_FIELDS)}


def validate_comparison_metrics(value: Any, label: str) -> dict[str, Any]:
    """Return the exact public comparison schema, rejecting arbitrary payloads."""

    metrics = _copy_json_object(value, label)
    fields = set(metrics)
    required = COMPARISON_METRIC_FIELDS - {"region"}
    if not required <= fields or not fields <= COMPARISON_METRIC_FIELDS:
        raise VisualEvidenceError(
            f"{label} fields must be {sorted(required)} with optional region"
        )
    changed = _finite_number(
        metrics["changed_fraction"], f"{label}.changed_fraction", minimum=0, maximum=1
    )
    required_change = _finite_number(
        metrics["required_changed_fraction"],
        f"{label}.required_changed_fraction",
        minimum=0,
        maximum=1,
    )
    _finite_number(
        metrics["rms_difference"], f"{label}.rms_difference", minimum=0, maximum=255
    )
    if changed < required_change:
        raise VisualEvidenceError(f"{label} did not meet its required changed fraction")
    if "region" in metrics:
        region = metrics["region"]
        if not isinstance(region, list) or len(region) != 4:
            raise VisualEvidenceError(f"{label}.region must contain four fractions")
        for index, coordinate in enumerate(region):
            _finite_number(
                coordinate, f"{label}.region[{index}]", minimum=0, maximum=1
            )
        if region[0] >= region[2] or region[1] >= region[3]:
            raise VisualEvidenceError(f"{label}.region must describe a non-empty rectangle")
    return {field: metrics[field] for field in sorted(fields)}


def collect_evidence(
    output_root: Path,
    catalog: Catalog,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """Return lanes, frames and directed pixel comparisons from successful result files."""

    root = output_root.resolve()
    profiles = root / "profiles"
    if not profiles.is_dir():
        raise VisualEvidenceError(f"missing packaged E2E profiles directory: {profiles}")
    result_paths = sorted(profiles.glob("*/result.json"))
    if not result_paths:
        raise VisualEvidenceError(f"no packaged E2E result.json files below {profiles}")

    lanes: list[dict[str, Any]] = []
    frames: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    capture_order = {
        capture["capture_id"]: index for index, capture in enumerate(catalog.captures)
    }
    lane_ids: set[str] = set()
    frame_ids: set[str] = set()
    comparison_ids: set[str] = set()

    for result_path in result_paths:
        result = _read_json(result_path, "packaged E2E result")
        if not isinstance(result, dict):
            raise VisualEvidenceError(f"packaged result must be an object: {result_path}")
        artifact_node = _nonempty_string(result.get("artifact_node"), "result.artifact_node")
        version = _nonempty_string(result.get("runtime_version"), "result.runtime_version")
        loader = _nonempty_string(result.get("loader"), "result.loader")
        scenario = _nonempty_string(result.get("scenario"), "result.scenario")
        for value, label in (
            (artifact_node, "artifact_node"),
            (version, "runtime_version"),
            (loader, "loader"),
            (scenario, "scenario"),
        ):
            if not SAFE_ID.fullmatch(value):
                raise VisualEvidenceError(f"result has unsafe {label} {value!r}")
        if loader not in {"fabric", "forge", "neoforge"}:
            raise VisualEvidenceError(f"result has unsupported loader {loader!r}")
        if result.get("status") != "pass":
            raise VisualEvidenceError(f"public evidence cannot include non-pass result {result_path}")
        jar_sha256 = _nonempty_string(result.get("jar_sha256"), "result.jar_sha256")
        if not SHA256.fullmatch(jar_sha256):
            raise VisualEvidenceError(f"result has invalid jar_sha256 in {result_path}")

        raw_profile = result_path.parent
        reject_symlinks(result_path, profiles, "packaged result path")
        profile = raw_profile.resolve()
        expected_profile = profile.relative_to(root).as_posix()
        if result.get("profile") != expected_profile:
            raise VisualEvidenceError(
                f"result profile identity mismatch: {result.get('profile')!r} != {expected_profile!r}"
            )
        lane_id = f"{artifact_node}/{scenario}"
        if lane_id in lane_ids:
            raise VisualEvidenceError(f"duplicate packaged result lane {lane_id!r}")
        lane_ids.add(lane_id)

        reports = result.get("reports")
        if not isinstance(reports, dict) or not reports:
            raise VisualEvidenceError(f"result reports must be a non-empty object: {result_path}")
        expected_roles = {
            capture["role"]
            for capture in catalog.captures
            if capture["scenario"] == scenario
        }
        if set(reports) != expected_roles:
            raise VisualEvidenceError(
                f"catalog/report role coverage mismatch for {lane_id}: "
                f"missing={sorted(expected_roles - set(reports))}, "
                f"extra={sorted(set(reports) - expected_roles)}"
            )
        lane_frame_ids: dict[tuple[str, str], str] = {}
        for role in sorted(reports):
            if role not in {"client_a", "client_b"}:
                raise VisualEvidenceError(f"unsupported report role {role!r} in {result_path}")
            report = reports[role]
            if not isinstance(report, dict):
                raise VisualEvidenceError(f"report {role} must be an object in {result_path}")
            if (
                report.get("version") != version
                or report.get("scenario") != scenario
                or report.get("role") != role
                or report.get("status") != "pass"
            ):
                raise VisualEvidenceError(f"report identity/status mismatch for {lane_id}/{role}")
            steps = report.get("steps")
            if not isinstance(steps, list) or not steps:
                raise VisualEvidenceError(f"report steps must be non-empty for {lane_id}/{role}")
            pixel_validation = report.get("pixel_validation")
            if not isinstance(pixel_validation, dict):
                raise VisualEvidenceError(f"missing pixel validation for {lane_id}/{role}")
            screenshot_metrics = pixel_validation.get("screenshots")
            if not isinstance(screenshot_metrics, dict):
                raise VisualEvidenceError(f"missing screenshot metrics for {lane_id}/{role}")

            reported_steps: set[str] = set()
            for step_index, step_record in enumerate(steps):
                if not isinstance(step_record, dict):
                    raise VisualEvidenceError(
                        f"report step {step_index} must be an object for {lane_id}/{role}"
                    )
                screenshot_name = step_record.get("screenshot")
                if not screenshot_name:
                    continue
                step = _nonempty_string(step_record.get("name"), "report step name")
                if step_record.get("status") != "pass":
                    raise VisualEvidenceError(f"screenshot step did not pass: {lane_id}/{role}/{step}")
                if step in reported_steps:
                    raise VisualEvidenceError(f"duplicate screenshot step: {lane_id}/{role}/{step}")
                reported_steps.add(step)
                capture = catalog.by_key.get((scenario, role, step))
                if capture is None:
                    raise VisualEvidenceError(
                        f"uncatalogued screenshot step: {scenario}/{role}/{step}"
                    )
                screenshot = _safe_screenshot(profile, role, screenshot_name)
                metrics = validate_screenshot_metrics(
                    screenshot_metrics.get(step), f"pixel metrics for {lane_id}/{role}/{step}"
                )
                width = metrics.get("width")
                height = metrics.get("height")
                file_sha256 = metrics.get("file_sha256")
                if (
                    isinstance(width, bool)
                    or not isinstance(width, int)
                    or width <= 0
                    or isinstance(height, bool)
                    or not isinstance(height, int)
                    or height <= 0
                    or not isinstance(file_sha256, str)
                    or not SHA256.fullmatch(file_sha256)
                ):
                    raise VisualEvidenceError(
                        f"invalid screenshot metrics for {lane_id}/{role}/{step}"
                    )
                actual_dimensions = png_dimensions(screenshot)
                if actual_dimensions != (width, height):
                    raise VisualEvidenceError(
                        f"screenshot dimensions disagree for {lane_id}/{role}/{step}: "
                        f"{actual_dimensions} != {(width, height)}"
                    )
                actual_sha256 = sha256_file(screenshot)
                if actual_sha256 != file_sha256:
                    raise VisualEvidenceError(
                        f"screenshot digest disagrees for {lane_id}/{role}/{step}"
                    )
                frame_id = f"{artifact_node}/{scenario}/{role}/{step}"
                if frame_id in frame_ids:
                    raise VisualEvidenceError(f"duplicate visual frame {frame_id!r}")
                frame_ids.add(frame_id)
                lane_frame_ids[(role, step)] = frame_id
                frames.append(
                    {
                        "frame_id": frame_id,
                        "capture_id": capture["capture_id"],
                        "capture_order": capture_order[capture["capture_id"]],
                        "title": capture["title"],
                        "expectation": capture["expectation"],
                        "review_tier": capture["review_tier"],
                        "artifact_node": artifact_node,
                        "version": version,
                        "loader": loader,
                        "scenario": scenario,
                        "role": role,
                        "step": step,
                        "filename": screenshot.name,
                        "source_path": str(screenshot),
                        "file_sha256": file_sha256,
                        "width": width,
                        "height": height,
                        "pixel_validation": metrics,
                    }
                )

            expected_steps = {
                capture["step"]
                for capture in catalog.captures
                if capture["scenario"] == scenario and capture["role"] == role
            }
            if reported_steps != expected_steps:
                raise VisualEvidenceError(
                    f"catalog/report coverage mismatch for {lane_id}/{role}: "
                    f"missing={sorted(expected_steps - reported_steps)}, "
                    f"extra={sorted(reported_steps - expected_steps)}"
                )

            raw_comparisons = pixel_validation.get("comparisons")
            if not isinstance(raw_comparisons, dict):
                raise VisualEvidenceError(f"missing comparisons object for {lane_id}/{role}")
            for pair, raw_metrics in sorted(raw_comparisons.items()):
                if not isinstance(pair, str) or pair.count("->") != 1:
                    raise VisualEvidenceError(f"invalid comparison key {pair!r} for {lane_id}/{role}")
                first_step, second_step = pair.split("->")
                first_id = lane_frame_ids.get((role, first_step))
                second_id = lane_frame_ids.get((role, second_step))
                if first_id is None or second_id is None or first_id == second_id:
                    raise VisualEvidenceError(
                        f"comparison endpoints are not catalogued frames: {lane_id}/{role}/{pair}"
                    )
                comparison_id = f"{artifact_node}/{scenario}/{role}/{pair}"
                if comparison_id in comparison_ids:
                    raise VisualEvidenceError(f"duplicate visual comparison {comparison_id!r}")
                comparison_ids.add(comparison_id)
                comparisons.append(
                    {
                        "comparison_id": comparison_id,
                        "artifact_node": artifact_node,
                        "version": version,
                        "loader": loader,
                        "scenario": scenario,
                        "role": role,
                        "first_frame_id": first_id,
                        "second_frame_id": second_id,
                        "pixel_validation": validate_comparison_metrics(
                            raw_metrics, f"comparison metrics for {comparison_id}"
                        ),
                    }
                )

        elapsed = result.get("elapsed_s")
        if isinstance(elapsed, bool) or not isinstance(elapsed, (int, float)) or elapsed < 0:
            raise VisualEvidenceError(f"result elapsed_s is invalid for {lane_id}")
        lanes.append(
            {
                "lane_id": lane_id,
                "artifact_node": artifact_node,
                "version": version,
                "loader": loader,
                "scenario": scenario,
                "jar_sha256": jar_sha256,
                "status": "pass",
                "roles": sorted(reports),
                "elapsed_s": elapsed,
            }
        )

    lanes.sort(key=lambda item: (item["version"], item["loader"], item["scenario"]))
    frames.sort(
        key=lambda item: (
            item["version"],
            item["loader"],
            item["capture_order"],
        )
    )
    comparisons.sort(key=lambda item: item["comparison_id"])
    return lanes, frames, comparisons
