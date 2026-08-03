#!/usr/bin/env python3
"""Decide whether Build gate must use the Gradle cache read-only.

The policy is intentionally fail-closed.  A cache writer is allowed only for a
trusted Build gate event on ``master`` or on the canonical release branch read
from the repository's release matrix.  Every other input produces ``true``
(read-only), while malformed required input is rejected.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence


WRITER_EVENTS = frozenset({"push", "workflow_dispatch"})
READ_ONLY_REF_PREFIXES = (
    "automation/",
    "codex/",
    "dependabot/",
    "refs/pull/",
    "refs/tags/",
)


class PolicyError(ValueError):
    """Raised when required policy input cannot be trusted."""


def _require_non_empty(value: str, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PolicyError(f"{label} must be a non-empty string")
    return value


def load_release_branch(matrix_path: Path) -> str:
    """Read and validate ``project.release_branch`` from a release matrix."""
    try:
        payload = json.loads(matrix_path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise PolicyError(f"cannot read release matrix: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise PolicyError(f"invalid release matrix JSON: {exc}") from exc

    if not isinstance(payload, dict):
        raise PolicyError("release matrix root must be a JSON object")
    project = payload.get("project")
    if not isinstance(project, dict):
        raise PolicyError("release matrix project must be a JSON object")
    release_branch = project.get("release_branch")
    _require_non_empty(release_branch, "project.release_branch")
    if release_branch != release_branch.strip():
        raise PolicyError("project.release_branch must not contain surrounding whitespace")
    return release_branch


def is_read_only(
    *,
    event_name: str,
    ref_name: str,
    ref_type: str,
    ref_protected: bool,
    release_branch: str,
) -> bool:
    """Return ``True`` unless the exact input is an approved cache writer."""
    _require_non_empty(event_name, "event name")
    _require_non_empty(ref_name, "ref name")
    _require_non_empty(ref_type, "ref type")
    _require_non_empty(release_branch, "release branch")
    if not isinstance(ref_protected, bool):
        raise PolicyError("ref protected must be a boolean")

    if (
        event_name not in WRITER_EVENTS
        or ref_type != "branch"
        or not ref_protected
    ):
        return True
    if ref_name.startswith(READ_ONLY_REF_PREFIXES):
        return True
    return ref_name not in {"master", release_branch}


def evaluate(
    matrix_path: Path,
    event_name: str,
    ref_name: str,
    ref_type: str,
    ref_protected: bool,
) -> bool:
    """Load the authoritative branch identity and evaluate the cache policy."""
    release_branch = load_release_branch(matrix_path)
    return is_read_only(
        event_name=event_name,
        ref_name=ref_name,
        ref_type=ref_type,
        ref_protected=ref_protected,
        release_branch=release_branch,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--ref-name", required=True)
    parser.add_argument("--ref-type", required=True)
    parser.add_argument("--ref-protected", choices=("true", "false"), required=True)
    args = parser.parse_args(argv)

    try:
        read_only = evaluate(
            args.matrix,
            args.event_name,
            args.ref_name,
            args.ref_type,
            args.ref_protected == "true",
        )
    except PolicyError as exc:
        print(f"Gradle cache policy error: {exc}", file=sys.stderr)
        return 2

    print("true" if read_only else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
