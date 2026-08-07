#!/usr/bin/env python3
"""Validate the narrow patch boundary used by AI-assisted CI jobs.

The model runs in a read-only GitHub job.  This script is the deterministic
boundary between its disposable checkout and the credentialed writer job.
"""

from __future__ import annotations

import argparse
import os
import stat
import subprocess
import sys
import unicodedata
from pathlib import Path, PurePosixPath
from typing import Iterable


MAX_PATCH_BYTES = {
    "conflict": 2 * 1024 * 1024,
    "repair": 2 * 1024 * 1024,
    "port": 16 * 1024 * 1024,
}
MAX_PATHS = {"conflict": 24, "repair": 24, "port": 512}

AI_PROTECTED_EXACT = {
    ".gitattributes",
    ".gitignore",
    ".mcp.json",
    "AGENTS.md",
    "CLAUDE.md",
    "CLAUDE.local.md",
    "CONTRIBUTING.md",
    "gradle.properties",
    "settings.gradle.kts",
}
AI_PROTECTED_PREFIXES = (
    ".claude/",
    ".codex/",
    ".github/",
    "build-logic/",
    "docs/ai/",
    "e2e/",
    "gradle/",
    "release/",
    "scripts/",
)
REPAIR_ALLOWED_PREFIXES = (
    "common/src/main/",
    "fabric/src/main/",
    "forge/src/main/",
    "neoforge/src/main/",
)
AI_PROTECTED_PARTS = ("/src/test/", "/src/e2e/")
AI_PROTECTED_SUFFIXES = (".gradle", ".gradle.kts")
AI_PROTECTED_SOURCE_NAMES = (
    "Bounded",
    "RateLimiter",
    "SafeImageReader",
    "Security",
    "TransferLimits",
)
REGULAR_GIT_MODES = {"100644", "100755"}


class PolicyError(ValueError):
    pass


def normalize_path(raw: str) -> str:
    if (
        not raw
        or "\\" in raw
        or any(ord(char) < 32 or ord(char) == 127 for char in raw)
        or unicodedata.normalize("NFC", raw) != raw
    ):
        raise PolicyError(f"unsafe repository path {raw!r}")
    path = PurePosixPath(raw)
    normalized = path.as_posix()
    if (
        path.is_absolute()
        or any(
            part in {"", ".", ".."}
            or ":" in part
            or part.endswith((" ", "."))
            for part in path.parts
        )
        or raw != normalized
    ):
        raise PolicyError(f"unsafe repository path {raw!r}")
    return normalized


def validate_paths(
    paths: Iterable[str], mode: str, allowed_paths: set[str] | None = None
) -> tuple[str, ...]:
    portable: dict[str, str] = {}
    for raw_path in paths:
        path = normalize_path(raw_path)
        collision_key = path.casefold()
        previous = portable.setdefault(collision_key, path)
        if previous != path:
            raise PolicyError(
                f"AI patch contains case-colliding paths: {previous!r}, {path!r}"
            )
    normalized = tuple(sorted(portable.values()))
    if not normalized:
        raise PolicyError("AI patch must change at least one tracked path")
    if len(normalized) > MAX_PATHS[mode]:
        raise PolicyError(
            f"AI patch changes {len(normalized)} paths; limit is {MAX_PATHS[mode]}"
        )
    if allowed_paths is not None:
        unexpected = set(normalized) - allowed_paths
        if unexpected:
            raise PolicyError(
                f"AI changed paths outside the approved conflict set: {sorted(unexpected)}"
            )
    if mode != "port":
        protected = [
            path
            for path in normalized
            if is_ai_protected(path)
        ]
        if protected:
            raise PolicyError(f"AI patch touches protected paths: {protected}")
    if mode == "repair":
        outside_production = [
            path for path in normalized if not path.startswith(REPAIR_ALLOWED_PREFIXES)
        ]
        if outside_production:
            raise PolicyError(
                "AI repair touches paths outside production src/main: "
                f"{outside_production}"
            )
    return normalized


def is_ai_protected(path: str) -> bool:
    name = PurePosixPath(path).name
    return (
        path in AI_PROTECTED_EXACT
        or path.startswith(AI_PROTECTED_PREFIXES)
        or any(part in f"/{path}" for part in AI_PROTECTED_PARTS)
        or path.endswith(AI_PROTECTED_SUFFIXES)
        or path.endswith(".mixins.json")
        or (name.endswith(".java") and any(token in name for token in AI_PROTECTED_SOURCE_NAMES))
    )


def parse_numstat(payload: bytes) -> tuple[tuple[str, ...], bool]:
    """Parse ``git diff/apply --numstat -z`` output, including renames."""
    chunks = payload.split(b"\0")
    paths: list[str] = []
    binary = False
    index = 0
    while index < len(chunks):
        chunk = chunks[index]
        index += 1
        if not chunk:
            continue
        try:
            added, deleted, path = chunk.decode("utf-8").split("\t", 2)
        except (UnicodeDecodeError, ValueError) as exc:
            raise PolicyError("malformed or non-UTF-8 patch numstat") from exc
        binary = binary or added == "-" or deleted == "-"
        if path:
            paths.append(path)
            continue
        if index + 1 >= len(chunks):
            raise PolicyError("malformed rename in patch numstat")
        try:
            paths.extend((chunks[index].decode("utf-8"), chunks[index + 1].decode("utf-8")))
        except UnicodeDecodeError as exc:
            raise PolicyError("non-UTF-8 rename path in patch") from exc
        index += 2
    return tuple(paths), binary


def run_git(*args: str) -> bytes:
    try:
        return subprocess.check_output(("git", *args), stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode("utf-8", errors="replace").strip()
        raise PolicyError(detail or f"git {' '.join(args)} failed") from exc


def read_allowed_paths(path: Path | None) -> set[str] | None:
    if path is None:
        return None
    try:
        values = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise PolicyError(f"cannot read allowed paths: {exc}") from exc
    return {normalize_path(value) for value in values if value}


def validate_patch(path: Path, mode: str, allowed: set[str] | None) -> None:
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise PolicyError(f"cannot read AI patch: {exc}") from exc
    if not stat.S_ISREG(metadata.st_mode):
        raise PolicyError("AI patch input must be a regular file")
    size = metadata.st_size
    if size <= 0 or size > MAX_PATCH_BYTES[mode]:
        raise PolicyError(
            f"AI patch size {size} is outside 1..{MAX_PATCH_BYTES[mode]} bytes"
        )
    paths, binary = parse_numstat(run_git("apply", "--numstat", "-z", str(path)))
    if binary and mode != "port":
        raise PolicyError("AI-authored patches must not contain binary changes")
    validate_paths(paths, mode, allowed)


def validate_staged(mode: str, allowed: set[str] | None, output: Path | None) -> None:
    numstat = run_git("diff", "--cached", "--numstat", "-z", "HEAD")
    paths, binary = parse_numstat(numstat)
    if binary and mode != "port":
        raise PolicyError("AI-authored patches must not contain binary changes")
    normalized = validate_paths(paths, mode, allowed)
    validate_index_modes(normalized)
    try:
        subprocess.run(("git", "diff", "--cached", "--check", "HEAD"), check=True)
    except subprocess.CalledProcessError as exc:
        raise PolicyError("AI patch fails git diff --check") from exc
    if output is not None:
        patch = run_git("diff", "--cached", "--binary", "--full-index", "HEAD")
        if not patch or len(patch) > MAX_PATCH_BYTES[mode]:
            raise PolicyError(
                f"AI patch size {len(patch)} is outside 1..{MAX_PATCH_BYTES[mode]} bytes"
            )
        write_exclusive(output, patch)


def validate_index_modes(paths: Iterable[str]) -> None:
    """Reject links, gitlinks, and unmerged entries from the candidate index."""

    for path in paths:
        payload = run_git(
            "ls-files", "--stage", "-z", "--", f":(top,literal){path}"
        )
        records = [record for record in payload.split(b"\0") if record]
        if not records:
            # A changed path with no stage-0 entry is a deletion.
            continue
        if len(records) != 1:
            raise PolicyError(f"AI patch has ambiguous index entries for {path!r}")
        try:
            metadata, raw_observed = records[0].split(b"\t", 1)
            mode, _object_id, stage_number = metadata.decode("ascii").split(" ")
            observed = raw_observed.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise PolicyError(f"malformed index entry for {path!r}") from exc
        if observed != path or stage_number != "0":
            raise PolicyError(f"AI patch has an unresolved index entry for {path!r}")
        if mode not in REGULAR_GIT_MODES:
            raise PolicyError(
                f"AI patch creates a non-regular Git entry {mode} at {path!r}"
            )


def write_exclusive(path: Path, payload: bytes) -> None:
    """Create one policy artifact without following a candidate-controlled link."""

    try:
        parent = path.parent
        parent_metadata = parent.lstat()
        if not stat.S_ISDIR(parent_metadata.st_mode):
            raise PolicyError("AI patch output parent must be a real directory")
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        descriptor = os.open(path, flags, 0o600)
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    except PolicyError:
        raise
    except OSError as exc:
        raise PolicyError(f"cannot create isolated AI patch output: {exc}") from exc


def validate_worktree(mode: str, allowed: set[str] | None) -> tuple[str, ...]:
    tracked = run_git("diff", "--name-only", "-z").split(b"\0")
    untracked = run_git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    try:
        paths = [item.decode("utf-8") for item in (*tracked, *untracked) if item]
    except UnicodeDecodeError as exc:
        raise PolicyError("AI changed a non-UTF-8 repository path") from exc
    normalized = validate_paths(paths, mode, allowed)
    if mode == "conflict":
        validate_conflict_contents(normalized)
    return normalized


def validate_conflict_contents(paths: Iterable[str]) -> None:
    markers = (b"<<<<<<< ", b"||||||| ", b">>>>>>> ")
    remaining_bytes = MAX_PATCH_BYTES["conflict"]
    for raw in paths:
        normalized = normalize_path(raw)
        path = Path(normalized)
        try:
            before = path.lstat()
        except FileNotFoundError:
            # Resolving a conflict by deleting the original file is valid.
            continue
        except OSError as exc:
            raise PolicyError(f"cannot inspect resolved conflict {path}: {exc}") from exc
        if not stat.S_ISREG(before.st_mode):
            raise PolicyError(
                f"conflict resolution must remain a regular file or be deleted: {path}"
            )
        flags = os.O_RDONLY
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            descriptor = os.open(path, flags)
            with os.fdopen(descriptor, "rb", closefd=True) as handle:
                observed = os.fstat(handle.fileno())
                if (
                    not stat.S_ISREG(observed.st_mode)
                    or (observed.st_dev, observed.st_ino)
                    != (before.st_dev, before.st_ino)
                ):
                    raise PolicyError(
                        f"conflict resolution changed while being inspected: {path}"
                    )
                if observed.st_size > remaining_bytes:
                    raise PolicyError(
                        "AI conflict resolution exceeds the aggregate "
                        f"{MAX_PATCH_BYTES['conflict']}-byte limit"
                    )
                content = handle.read(remaining_bytes + 1)
        except PolicyError:
            raise
        except OSError as exc:
            raise PolicyError(f"cannot inspect resolved conflict {path}: {exc}") from exc
        if len(content) > remaining_bytes:
            raise PolicyError(
                "AI conflict resolution exceeds the aggregate "
                f"{MAX_PATCH_BYTES['conflict']}-byte limit"
            )
        remaining_bytes -= len(content)
        if b"\0" in content:
            raise PolicyError(f"AI conflict resolution must be text-only: {path}")
        if any(line.startswith(markers) for line in content.splitlines()):
            raise PolicyError(f"unresolved conflict marker remains in {path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("patch", "paths", "staged", "worktree"))
    parser.add_argument("--mode", choices=("conflict", "repair", "port"), required=True)
    parser.add_argument("--patch", type=Path)
    parser.add_argument("--allowed-paths", type=Path)
    parser.add_argument("--paths-file", type=Path)
    parser.add_argument("--write-patch", type=Path)
    args = parser.parse_args()

    try:
        allowed = read_allowed_paths(args.allowed_paths)
        if args.kind == "patch":
            if args.patch is None or args.write_patch is not None or args.paths_file is not None:
                raise PolicyError("patch mode requires --patch only")
            validate_patch(args.patch, args.mode, allowed)
        elif args.kind == "paths":
            if args.paths_file is None or args.patch is not None or args.write_patch is not None:
                raise PolicyError("paths mode requires --paths-file only")
            paths = read_allowed_paths(args.paths_file)
            normalized = validate_paths(paths or (), args.mode, allowed)
            if args.mode == "conflict":
                validate_conflict_contents(normalized)
        elif args.kind == "staged":
            if args.patch is not None or args.paths_file is not None:
                raise PolicyError("staged mode does not accept --patch")
            validate_staged(args.mode, allowed, args.write_patch)
        else:
            if args.patch is not None or args.write_patch is not None or args.paths_file is not None:
                raise PolicyError("worktree mode does not accept patch output arguments")
            validate_worktree(args.mode, allowed)
    except PolicyError as exc:
        print(f"AI patch policy error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
