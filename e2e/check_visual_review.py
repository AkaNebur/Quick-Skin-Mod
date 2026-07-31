#!/usr/bin/env python3
"""
Turn the AI visual-review report into a gate result.

Reads the JSON the reviewer wrote, renders it into the GitHub step summary, and exits
non-zero when any frame is reported as a genuine rendering defect. A malformed or missing
report is itself a failure: a review that did not run must not read as a pass.

Usage:
  python3 e2e/check_visual_review.py --report visual-review-report.json --manifest visual-review-manifest.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

VERDICT_KEYS = {"label", "matches", "visible", "anomalies", "defect"}


def emit(summary: str) -> None:
    """Append to the GitHub step summary when running in Actions; always echo to stdout."""
    print(summary)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(summary + "\n")


def load(path: Path, what: str) -> object:
    if not path.is_file():
        emit(f"## Visual review: FAILED\n\nThe {what} is missing at `{path}`. "
             "The review did not produce a result, so the gate cannot pass.")
        sys.exit(1)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        emit(f"## Visual review: FAILED\n\nThe {what} at `{path}` is not valid JSON ({exc}).")
        sys.exit(1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", default="visual-review-report.json")
    ap.add_argument("--manifest", default="visual-review-manifest.json")
    args = ap.parse_args()

    manifest = load(Path(args.manifest), "review manifest")
    report = load(Path(args.report), "review report")

    if not isinstance(report, list):
        emit("## Visual review: FAILED\n\nThe report is not a JSON array of verdicts.")
        return 1

    expected = {item["label"] for item in manifest}
    reviewed = {v.get("label") for v in report if isinstance(v, dict)}
    missing = sorted(expected - reviewed)

    defects = [v for v in report if isinstance(v, dict) and v.get("defect") is True]
    clean = [v for v in report if isinstance(v, dict) and v.get("defect") is not True]

    lines = []
    if defects or missing:
        lines.append("## Visual review: FAILED")
    else:
        lines.append("## Visual review: passed")
    lines.append("")
    lines.append(
        f"Reviewed {len(reviewed)} of {len(expected)} frames · "
        f"{len(clean)} clean · {len(defects)} defect(s)"
    )

    if defects:
        lines.append("")
        lines.append("### Defects")
        for d in defects:
            lines.append("")
            lines.append(f"**{d.get('label', '(unlabelled)')}**")
            if d.get("visible"):
                lines.append(f"- Seen: {d['visible']}")
            for anomaly in d.get("anomalies") or []:
                lines.append(f"- {anomaly}")

    if missing:
        lines.append("")
        lines.append("### Not reviewed")
        lines.append("These frames were in the manifest but absent from the report:")
        for label in missing:
            lines.append(f"- `{label}`")

    if clean and not defects and not missing:
        lines.append("")
        lines.append("Every reviewed frame matched what it should show.")

    emit("\n".join(lines))
    return 1 if (defects or missing) else 0


if __name__ == "__main__":
    sys.exit(main())
