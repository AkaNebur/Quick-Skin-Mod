#!/usr/bin/env python3
"""Validate and summarize the advisory AI visual-review report."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any


VERDICT_KEYS = {"label", "matches", "visible", "anomalies", "defect"}
MANIFEST_KEYS = {"path", "label", "capture_id", "kind", "expectation"}


class ReviewError(ValueError):
    pass


def emit(summary: str) -> None:
    """Append to the Actions summary when available and always echo to stdout."""
    print(summary)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(summary + "\n")


def load(path: Path, what: str) -> Any:
    if not path.is_file():
        raise ReviewError(f"the {what} is missing at {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReviewError(f"the {what} at {path} is not valid JSON: {exc}") from exc


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ReviewError(f"{label} must be a non-empty string")
    return value.strip()


def validate(manifest: Any, report: Any) -> list[dict[str, Any]]:
    if not isinstance(manifest, list) or not manifest:
        raise ReviewError("review manifest must be a non-empty JSON array")
    labels: list[str] = []
    for index, item in enumerate(manifest):
        if not isinstance(item, dict) or set(item) != MANIFEST_KEYS:
            raise ReviewError(
                f"manifest entry {index} must contain exactly {sorted(MANIFEST_KEYS)}"
            )
        labels.append(_text(item.get("label"), f"manifest entry {index}.label"))
        _text(item.get("path"), f"manifest entry {index}.path")
        capture_id = _text(
            item.get("capture_id"), f"manifest entry {index}.capture_id"
        )
        if _text(item.get("kind"), f"manifest entry {index}.kind") != capture_id:
            raise ReviewError(f"manifest entry {index}.kind must equal capture_id")
        _text(item.get("expectation"), f"manifest entry {index}.expectation")
    if len(set(labels)) != len(labels):
        raise ReviewError("review manifest contains duplicate labels")

    if not isinstance(report, list) or not report:
        raise ReviewError("review report must be a non-empty JSON array")
    verdicts: dict[str, dict[str, Any]] = {}
    for index, verdict in enumerate(report):
        if not isinstance(verdict, dict) or set(verdict) != VERDICT_KEYS:
            raise ReviewError(
                f"report verdict {index} must contain exactly {sorted(VERDICT_KEYS)}"
            )
        label = _text(verdict["label"], f"report verdict {index}.label")
        _text(verdict["visible"], f"report verdict {index}.visible")
        if not isinstance(verdict["matches"], bool) or not isinstance(verdict["defect"], bool):
            raise ReviewError(f"report verdict {index} matches/defect must be booleans")
        anomalies = verdict["anomalies"]
        if not isinstance(anomalies, list) or any(
            not isinstance(item, str) or not item.strip() for item in anomalies
        ):
            raise ReviewError(f"report verdict {index}.anomalies must be an array of strings")
        if verdict["matches"] == verdict["defect"]:
            raise ReviewError(
                f"report verdict {index} must set exactly one of matches or defect"
            )
        if verdict["defect"] and not anomalies:
            raise ReviewError(f"defect verdict {index} must describe at least one anomaly")
        if label in verdicts:
            raise ReviewError(f"review report contains duplicate label {label!r}")
        verdicts[label] = verdict

    expected = set(labels)
    reviewed = set(verdicts)
    missing = sorted(expected - reviewed)
    extra = sorted(reviewed - expected)
    if missing or extra:
        raise ReviewError(f"review label mismatch: missing={missing}, extra={extra}")
    return [verdicts[label] for label in labels]


def markdown_text(value: Any) -> str:
    text = " ".join(str(value).split())
    for character in ("\\", "`", "*", "_", "[", "]", "<", ">"):
        text = text.replace(character, "\\" + character)
    return text


def render(verdicts: list[dict[str, Any]]) -> tuple[str, bool]:
    defects = [verdict for verdict in verdicts if verdict["defect"]]
    lines = [
        "## Advisory visual review: defects reported" if defects else "## Advisory visual review: passed",
        "",
        f"Reviewed {len(verdicts)} of {len(verdicts)} frames · "
        f"{len(verdicts) - len(defects)} clean · {len(defects)} defect(s)",
    ]
    if defects:
        lines.extend(("", "### Reported defects"))
        for verdict in defects:
            lines.extend(
                (
                    "",
                    f"**{markdown_text(verdict['label'])}**",
                    f"- Seen: {markdown_text(verdict['visible'])}",
                )
            )
            lines.extend(f"- {markdown_text(item)}" for item in verdict["anomalies"])
    else:
        lines.extend(("", "Every reviewed frame matched its catalogued expectation."))
    lines.extend(
        (
            "",
            "This AI review is advisory. The packaged runtime and pixel invariants remain the required gate.",
        )
    )
    return "\n".join(lines), bool(defects)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default="visual-review-report.json")
    parser.add_argument("--manifest", default="visual-review-manifest.json")
    args = parser.parse_args(argv)
    try:
        verdicts = validate(
            load(Path(args.manifest), "review manifest"),
            load(Path(args.report), "review report"),
        )
    except ReviewError as exc:
        emit(f"## Advisory visual review: invalid\n\n{markdown_text(exc)}")
        return 1
    summary, has_defects = render(verdicts)
    emit(summary)
    return 1 if has_defects else 0


if __name__ == "__main__":
    raise SystemExit(main())
