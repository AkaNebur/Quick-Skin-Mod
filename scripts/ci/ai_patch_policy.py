#!/usr/bin/env python3
"""Validate the narrow patch boundary used by AI-assisted CI jobs.

The model runs in a read-only GitHub job.  This script is the deterministic
boundary between its disposable checkout and the credentialed writer job.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
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
    "AGENTS.md",
    "CLAUDE.md",
    "CONTRIBUTING.md",
    "gradle.properties",
    "settings.gradle.kts",
}
AI_PROTECTED_PREFIXES = (
    ".github/",
    "build-logic/",
    "docs/ai/",
    "e2e/",
    "gradle/",
    "release/",
    "scripts/",
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


class PolicyError(ValueError):
    pass


def normalize_path(raw: str) -> str:
    if not raw or "\\" in raw or any(ord(char) < 32 for char in raw):
        raise PolicyError(f"unsafe repository path {raw!r}")
    path = PurePosixPath(raw)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise PolicyError(f"unsafe repository path {raw!r}")
    return path.as_posix()


def validate_paths(
    paths: Iterable[str], mode: str, allowed_paths: set[str] | None = None
) -> tuple[str, ...]:
    normalized = tuple(sorted({normalize_path(path) for path in paths}))
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
        protected = [path for path in normalized if is_ai_protected(path)]
        if protected:
            raise PolicyError(f"AI patch touches protected paths: {protected}")
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
        size = path.stat().st_size
    except OSError as exc:
        raise PolicyError(f"cannot read AI patch: {exc}") from exc
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
    validate_paths(paths, mode, allowed)
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
        output.write_bytes(patch)


def validate_worktree(mode: str, allowed: set[str] | None) -> None:
    tracked = run_git("diff", "--name-only", "-z").split(b"\0")
    untracked = run_git("ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    try:
        paths = [item.decode("utf-8") for item in (*tracked, *untracked) if item]
    except UnicodeDecodeError as exc:
        raise PolicyError("AI changed a non-UTF-8 repository path") from exc
    validate_paths(paths, mode, allowed)


def validate_conflict_contents(paths: Iterable[str]) -> None:
    markers = (b"<<<<<<< ", b"||||||| ", b">>>>>>> ")
    for raw in paths:
        path = Path(normalize_path(raw))
        if not path.exists():
            continue
        try:
            content = path.read_bytes()
        except OSError as exc:
            raise PolicyError(f"cannot inspect resolved conflict {path}: {exc}") from exc
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
