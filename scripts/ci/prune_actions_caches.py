#!/usr/bin/env python3
"""Prune GitHub Actions caches owned by branches that no longer exist.

The command is intentionally conservative.  It never removes caches for a live
branch, a branch with an active workflow run, a tag, or a pull-request ref.
Dry-run is the default; callers must pass ``--apply`` to delete anything.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Protocol


REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
BRANCH_REF_PREFIX = "refs/heads/"
ACTIVE_RUN_STATUSES = ("requested", "pending", "queued", "in_progress", "waiting")
PAGE_SIZE = 100
MAX_PAGES = 10_000
DEFAULT_MAX_DELETE_COUNT = 75
DEFAULT_MAX_DELETE_BYTES = 10 * 1024 * 1024 * 1024


class PruneError(RuntimeError):
    """Raised when a safe pruning decision cannot be made."""


class ApiError(PruneError):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class CacheEntry:
    cache_id: int
    ref: str
    key: str
    version: str
    size_in_bytes: int
    created_at: str
    last_accessed_at: str

    @classmethod
    def parse(cls, value: Any) -> "CacheEntry":
        if not isinstance(value, dict):
            raise PruneError("cache entry must be an object")
        return cls(
            cache_id=_positive_int(value.get("id"), "cache.id"),
            ref=_text(value.get("ref"), "cache.ref"),
            key=_text(value.get("key"), "cache.key"),
            version=_text(value.get("version"), "cache.version"),
            size_in_bytes=_non_negative_int(
                value.get("size_in_bytes"), "cache.size_in_bytes"
            ),
            created_at=_timestamp(value.get("created_at"), "cache.created_at"),
            last_accessed_at=_timestamp(
                value.get("last_accessed_at"), "cache.last_accessed_at"
            ),
        )

    @property
    def branch(self) -> str | None:
        if not self.ref.startswith(BRANCH_REF_PREFIX):
            return None
        branch = self.ref[len(BRANCH_REF_PREFIX) :]
        return branch or None

    def report(self) -> dict[str, Any]:
        return {
            "id": self.cache_id,
            "ref": self.ref,
            "key": self.key,
            "version": self.version,
            "size_in_bytes": self.size_in_bytes,
            "created_at": self.created_at,
            "last_accessed_at": self.last_accessed_at,
        }


class CacheApi(Protocol):
    def get_default_branch(self) -> str: ...

    def list_branches(self) -> set[str]: ...

    def list_active_run_branches(self) -> set[str]: ...

    def branch_has_active_run(self, branch: str) -> bool: ...

    def list_caches(self) -> list[CacheEntry]: ...

    def branch_exists(self, branch: str) -> bool: ...

    def get_cache(self, expected: CacheEntry) -> CacheEntry | None: ...

    def delete_cache(self, cache_id: int) -> bool: ...


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise PruneError(f"{label} must be a non-empty string")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise PruneError(f"{label} must be a positive integer")
    return value


def _non_negative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise PruneError(f"{label} must be a non-negative integer")
    return value


def _timestamp(value: Any, label: str) -> str:
    text = _text(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PruneError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise PruneError(f"{label} must include a timezone")
    return text


class GitHubApi:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")

    def _repo_path(self, suffix: str) -> str:
        return f"/repos/{self.repository}{suffix}"

    def _request(self, method: str, path: str) -> Any:
        request = urllib.request.Request(
            f"{self.api_url}{path}",
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "Quick-Skin-Actions-cache-pruner/1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise ApiError(
                exc.code, f"GitHub API {method} {path} failed: {detail}"
            ) from exc
        except urllib.error.URLError as exc:
            raise PruneError(f"GitHub API {method} {path} failed: {exc}") from exc
        if not body:
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            raise PruneError(
                f"GitHub API {method} {path} returned invalid JSON"
            ) from exc

    def _paginate(
        self,
        path: str,
        *,
        field: str | None,
        query: dict[str, str] | None = None,
        max_search_results: int | None = None,
    ) -> list[Any]:
        values: list[Any] = []
        for page in range(1, MAX_PAGES + 1):
            parameters = {
                **(query or {}),
                "per_page": str(PAGE_SIZE),
                "page": str(page),
            }
            encoded = urllib.parse.urlencode(parameters)
            payload = self._request("GET", f"{path}?{encoded}")
            if max_search_results is not None:
                if not isinstance(payload, dict):
                    raise PruneError(f"paginated response for {path} is invalid")
                total_count = _non_negative_int(
                    payload.get("total_count"),
                    f"paginated response for {path}.total_count",
                )
                if total_count > max_search_results:
                    raise PruneError(
                        f"paginated search for {path} has {total_count} results; "
                        f"API limit is {max_search_results}"
                    )
            if field is None:
                batch = payload
            elif isinstance(payload, dict):
                batch = payload.get(field)
            else:
                batch = None
            if not isinstance(batch, list):
                raise PruneError(f"paginated response for {path} is invalid")
            values.extend(batch)
            if len(batch) < PAGE_SIZE:
                return values
        raise PruneError(f"paginated response for {path} exceeded {MAX_PAGES} pages")

    def get_default_branch(self) -> str:
        payload = self._request("GET", self._repo_path(""))
        if not isinstance(payload, dict):
            raise PruneError("repository response is invalid")
        return _text(payload.get("default_branch"), "repository.default_branch")

    def list_branches(self) -> set[str]:
        payload = self._paginate(self._repo_path("/branches"), field=None)
        branches: set[str] = set()
        for item in payload:
            if not isinstance(item, dict):
                raise PruneError("branch inventory item must be an object")
            branches.add(_text(item.get("name"), "branch.name"))
        return branches

    def _active_run_branches(self, *, branch: str | None = None) -> set[str]:
        branches: set[str] = set()
        path = self._repo_path("/actions/runs")
        for status in ACTIVE_RUN_STATUSES:
            query = {"status": status}
            if branch is not None:
                query["branch"] = branch
            payload = self._paginate(
                path,
                field="workflow_runs",
                query=query,
                max_search_results=1_000,
            )
            for item in payload:
                if not isinstance(item, dict):
                    raise PruneError("workflow run inventory item must be an object")
                if item.get("status") != status:
                    raise PruneError(
                        f"workflow run status disagrees with {status!r} inventory"
                    )
                head_branch = item.get("head_branch")
                if head_branch is None:
                    continue
                parsed_branch = _text(head_branch, "workflow_run.head_branch")
                if branch is not None and parsed_branch != branch:
                    raise PruneError(
                        f"workflow run branch filter returned {parsed_branch!r}, "
                        f"expected {branch!r}"
                    )
                branches.add(parsed_branch)
        return branches

    def list_active_run_branches(self) -> set[str]:
        return self._active_run_branches()

    def branch_has_active_run(self, branch: str) -> bool:
        return bool(self._active_run_branches(branch=branch))

    def _list_caches(self, *, query: dict[str, str] | None = None) -> list[CacheEntry]:
        payload = self._paginate(
            self._repo_path("/actions/caches"),
            field="actions_caches",
            query=query,
        )
        return [CacheEntry.parse(item) for item in payload]

    def list_caches(self) -> list[CacheEntry]:
        return self._list_caches()

    def branch_exists(self, branch: str) -> bool:
        encoded = urllib.parse.quote(branch, safe="")
        try:
            payload = self._request(
                "GET", self._repo_path(f"/branches/{encoded}")
            )
        except ApiError as exc:
            if exc.status == 404:
                return False
            raise
        if not isinstance(payload, dict):
            raise PruneError("branch response is invalid")
        actual = _text(payload.get("name"), "branch.name")
        if actual != branch:
            raise PruneError(
                f"branch lookup for {branch!r} returned unexpected branch {actual!r}"
            )
        return True

    def get_cache(self, expected: CacheEntry) -> CacheEntry | None:
        matching = [
            cache
            for cache in self._list_caches(
                query={"ref": expected.ref, "key": expected.key}
            )
            if cache.cache_id == expected.cache_id
        ]
        if len(matching) > 1:
            raise PruneError(f"cache ID {expected.cache_id} appeared more than once")
        return matching[0] if matching else None

    def delete_cache(self, cache_id: int) -> bool:
        try:
            self._request(
                "DELETE", self._repo_path(f"/actions/caches/{cache_id}")
            )
        except ApiError as exc:
            if exc.status == 404:
                return False
            raise
        return True


def select_candidates(
    caches: list[CacheEntry],
    *,
    existing_branches: set[str],
    active_run_branches: set[str],
) -> list[CacheEntry]:
    candidates = [
        cache
        for cache in caches
        if cache.branch is not None
        and cache.branch not in existing_branches
        and cache.branch not in active_run_branches
    ]
    return sorted(
        candidates,
        key=lambda cache: (cache.ref, cache.created_at, cache.cache_id),
    )


def _bounded_batch(
    candidates: list[CacheEntry],
    *,
    max_delete_count: int,
    max_delete_bytes: int,
) -> tuple[list[CacheEntry], list[CacheEntry]]:
    if max_delete_count <= 0:
        raise PruneError("max_delete_count must be positive")
    if max_delete_bytes <= 0:
        raise PruneError("max_delete_bytes must be positive")
    selected: list[CacheEntry] = []
    deferred: list[CacheEntry] = []
    selected_bytes = 0
    for cache in candidates:
        if (
            len(selected) >= max_delete_count
            or selected_bytes + cache.size_in_bytes > max_delete_bytes
        ):
            deferred.append(cache)
            continue
        selected.append(cache)
        selected_bytes += cache.size_in_bytes
    return selected, deferred


def prune(
    api: CacheApi,
    *,
    apply: bool,
    expected_default_branch: str = "master",
    max_delete_count: int = DEFAULT_MAX_DELETE_COUNT,
    max_delete_bytes: int = DEFAULT_MAX_DELETE_BYTES,
    delete_delay_seconds: float = 0.0,
) -> dict[str, Any]:
    if delete_delay_seconds < 0 or delete_delay_seconds > 10:
        raise PruneError("delete_delay_seconds must be between 0 and 10")

    default_branch = api.get_default_branch()
    if default_branch != expected_default_branch:
        raise PruneError(
            f"expected default branch {expected_default_branch!r}, got {default_branch!r}"
        )

    existing_branches = api.list_branches()
    if default_branch not in existing_branches:
        raise PruneError("default branch is missing from the complete branch inventory")

    active_run_branches = api.list_active_run_branches()
    candidates = select_candidates(
        api.list_caches(),
        existing_branches=existing_branches,
        active_run_branches=active_run_branches,
    )

    skipped: list[dict[str, Any]] = []
    if apply:
        # Close most of the inventory-to-delete gap before checking the global limits.  A run
        # that started while the initial inventory was being read protects its branch here.
        refreshed_active_branches = api.list_active_run_branches()
        still_safe: list[CacheEntry] = []
        for cache in candidates:
            if cache.branch in refreshed_active_branches:
                skipped.append({"id": cache.cache_id, "reason": "active-run"})
            else:
                still_safe.append(cache)
        candidates = still_safe

    discovered_candidates = list(candidates)
    candidates, deferred = _bounded_batch(
        candidates,
        max_delete_count=max_delete_count,
        max_delete_bytes=max_delete_bytes,
    )
    skipped.extend(
        {"id": cache.cache_id, "reason": "batch-limit"} for cache in deferred
    )

    deleted_ids: list[int] = []
    if apply:
        for cache in candidates:
            branch = cache.branch
            if branch is None:  # Defensive: select_candidates already excludes this case.
                raise PruneError(f"cache {cache.cache_id} lost its branch identity")

            current = api.get_cache(cache)
            if current is None:
                skipped.append({"id": cache.cache_id, "reason": "already-absent"})
                continue
            if current != cache:
                skipped.append({"id": cache.cache_id, "reason": "cache-changed"})
                continue

            # Recheck active work per candidate so a rerun that starts during a large batch does
            # not lose the branch-scoped cache it may still restore.
            if api.branch_has_active_run(branch):
                skipped.append({"id": cache.cache_id, "reason": "active-run-late"})
                continue

            # This is deliberately the final read before DELETE. A branch recreated after the
            # initial inventory keeps every cache that was formerly scoped to its name.
            if api.branch_exists(branch):
                skipped.append({"id": cache.cache_id, "reason": "branch-recreated"})
                continue

            if not api.delete_cache(cache.cache_id):
                skipped.append({"id": cache.cache_id, "reason": "already-absent"})
                continue
            deleted_ids.append(cache.cache_id)
            if delete_delay_seconds:
                time.sleep(delete_delay_seconds)

    return {
        "mode": "apply" if apply else "dry-run",
        "repository_default_branch": default_branch,
        "existing_branch_count": len(existing_branches),
        "active_run_branches": sorted(active_run_branches),
        "discovered_candidate_count": len(discovered_candidates),
        "discovered_candidate_bytes": sum(
            cache.size_in_bytes for cache in discovered_candidates
        ),
        "candidate_count": len(candidates),
        "candidate_bytes": sum(cache.size_in_bytes for cache in candidates),
        "candidates": [cache.report() for cache in candidates],
        "deleted_ids": deleted_ids,
        "skipped": skipped,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", ""),
        help="GitHub repository in owner/name form (defaults to GITHUB_REPOSITORY)",
    )
    parser.add_argument(
        "--expected-default-branch",
        default="master",
        help="fail closed unless the repository uses this default branch",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="delete the planned cache IDs; omission performs a dry-run",
    )
    parser.add_argument(
        "--max-delete-count",
        type=int,
        default=DEFAULT_MAX_DELETE_COUNT,
    )
    parser.add_argument(
        "--max-delete-bytes",
        type=int,
        default=DEFAULT_MAX_DELETE_BYTES,
    )
    parser.add_argument(
        "--delete-delay-seconds",
        type=float,
        default=1.0,
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = _text(args.repository, "repository")
        if not REPOSITORY.fullmatch(repository):
            raise PruneError("repository must use the owner/name form")
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if not token:
            raise PruneError("GH_TOKEN is required")
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        result = prune(
            api,
            apply=args.apply,
            expected_default_branch=args.expected_default_branch,
            max_delete_count=args.max_delete_count,
            max_delete_bytes=args.max_delete_bytes,
            delete_delay_seconds=args.delete_delay_seconds,
        )
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except PruneError as exc:
        print(f"Actions cache pruning error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
