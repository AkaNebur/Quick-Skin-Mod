from __future__ import annotations

import sys
import unittest
import urllib.parse
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from prune_actions_caches import (  # noqa: E402
    ACTIVE_RUN_STATUSES,
    ApiError,
    CacheEntry,
    GitHubApi,
    PruneError,
    parse_args,
    prune,
    select_candidates,
)


def cache(
    cache_id: int,
    ref: str,
    *,
    size: int = 10,
    key: str | None = None,
    last_accessed_at: str = "2026-08-01T00:00:00Z",
) -> CacheEntry:
    return CacheEntry(
        cache_id=cache_id,
        ref=ref,
        key=key or f"gradle-{cache_id}",
        version=f"version-{cache_id}",
        size_in_bytes=size,
        created_at="2026-08-01T00:00:00Z",
        last_accessed_at=last_accessed_at,
    )


class FakeApi:
    def __init__(self, caches: list[CacheEntry]) -> None:
        self.default_branch = "master"
        self.branches = {"master"}
        self.active_snapshots: list[set[str]] = [set()]
        self.caches = list(caches)
        self.revalidated = {item.cache_id: item for item in caches}
        self.recreated: set[str] = set()
        self.late_active: set[str] = set()
        self.delete_404: set[int] = set()
        self.deleted: list[int] = []
        self.branch_checks: list[str] = []
        self.cache_checks: list[int] = []
        self.active_calls = 0
        self.late_active_checks: list[str] = []

    def get_default_branch(self) -> str:
        return self.default_branch

    def list_branches(self) -> set[str]:
        return set(self.branches)

    def list_active_run_branches(self) -> set[str]:
        index = min(self.active_calls, len(self.active_snapshots) - 1)
        self.active_calls += 1
        return set(self.active_snapshots[index])

    def list_caches(self) -> list[CacheEntry]:
        return list(self.caches)

    def branch_has_active_run(self, branch: str) -> bool:
        self.late_active_checks.append(branch)
        return branch in self.late_active

    def branch_exists(self, branch: str) -> bool:
        self.branch_checks.append(branch)
        return branch in self.recreated

    def get_cache(self, expected: CacheEntry) -> CacheEntry | None:
        self.cache_checks.append(expected.cache_id)
        return self.revalidated.get(expected.cache_id)

    def delete_cache(self, cache_id: int) -> bool:
        if cache_id in self.delete_404:
            return False
        self.deleted.append(cache_id)
        return True


class CandidateSelectionTest(unittest.TestCase):
    def test_only_missing_branch_refs_are_candidates(self) -> None:
        values = [
            cache(1, "refs/heads/master"),
            cache(2, "refs/heads/deleted"),
            cache(3, "refs/heads/running"),
            cache(4, "refs/pull/12/merge"),
            cache(5, "refs/tags/mc1.20.1-v3.0.0"),
        ]
        selected = select_candidates(
            values,
            existing_branches={"master"},
            active_run_branches={"running"},
        )
        self.assertEqual([item.cache_id for item in selected], [2])

    def test_empty_branch_ref_is_not_a_candidate(self) -> None:
        selected = select_candidates(
            [cache(1, "refs/heads/")],
            existing_branches={"master"},
            active_run_branches=set(),
        )
        self.assertEqual(selected, [])


class PruneTest(unittest.TestCase):
    def test_cli_requires_apply_for_mutation(self) -> None:
        self.assertFalse(parse_args([]).apply)
        self.assertTrue(parse_args(["--apply"]).apply)

    def test_dry_run_is_the_default_behavior_and_never_revalidates_or_deletes(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted", size=25)])

        result = prune(api, apply=False)

        self.assertEqual(result["mode"], "dry-run")
        self.assertEqual(result["candidate_count"], 1)
        self.assertEqual(result["candidate_bytes"], 25)
        self.assertEqual(api.cache_checks, [])
        self.assertEqual(api.branch_checks, [])
        self.assertEqual(api.deleted, [])

    def test_apply_rechecks_active_runs_and_protects_a_newly_active_branch(self) -> None:
        api = FakeApi(
            [
                cache(1, "refs/heads/deleted-a"),
                cache(2, "refs/heads/deleted-b"),
            ]
        )
        api.active_snapshots = [set(), {"deleted-b"}]

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [1])
        self.assertEqual(result["deleted_ids"], [1])
        self.assertIn({"id": 2, "reason": "active-run"}, result["skipped"])

    def test_apply_deletes_serially_by_exact_id_after_revalidation(self) -> None:
        api = FakeApi(
            [
                cache(3, "refs/heads/z-deleted"),
                cache(2, "refs/heads/a-deleted"),
                cache(1, "refs/heads/a-deleted"),
            ]
        )

        result = prune(api, apply=True)

        self.assertEqual(api.cache_checks, [1, 2, 3])
        self.assertEqual(api.branch_checks, ["a-deleted", "a-deleted", "z-deleted"])
        self.assertEqual(api.deleted, [1, 2, 3])
        self.assertEqual(result["deleted_ids"], [1, 2, 3])

    def test_recreated_branch_is_preserved(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.recreated.add("deleted")

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertIn({"id": 1, "reason": "branch-recreated"}, result["skipped"])

    def test_run_starting_during_batch_is_preserved(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.late_active.add("deleted")

        result = prune(api, apply=True)

        self.assertEqual(api.deleted, [])
        self.assertEqual(api.branch_checks, [])
        self.assertIn({"id": 1, "reason": "active-run-late"}, result["skipped"])

    def test_changed_cache_is_preserved(self) -> None:
        original = cache(1, "refs/heads/deleted")
        api = FakeApi([original])
        api.revalidated[1] = cache(
            1,
            "refs/heads/deleted",
            last_accessed_at="2026-08-02T00:00:00Z",
        )

        result = prune(api, apply=True)

        self.assertEqual(api.branch_checks, [])
        self.assertEqual(api.deleted, [])
        self.assertIn({"id": 1, "reason": "cache-changed"}, result["skipped"])

    def test_delete_404_is_idempotent(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.delete_404.add(1)

        result = prune(api, apply=True)

        self.assertEqual(result["deleted_ids"], [])
        self.assertIn({"id": 1, "reason": "already-absent"}, result["skipped"])

    def test_count_limit_processes_a_deterministic_bounded_batch(self) -> None:
        api = FakeApi(
            [cache(1, "refs/heads/deleted"), cache(2, "refs/heads/deleted")]
        )

        result = prune(api, apply=True, max_delete_count=1)

        self.assertEqual(api.deleted, [1])
        self.assertEqual(result["discovered_candidate_count"], 2)
        self.assertEqual(result["candidate_count"], 1)
        self.assertIn({"id": 2, "reason": "batch-limit"}, result["skipped"])

    def test_oversized_cache_is_deferred_without_blocking_the_job(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted", size=101)])

        result = prune(api, apply=True, max_delete_bytes=100)

        self.assertEqual(api.cache_checks, [])
        self.assertEqual(api.branch_checks, [])
        self.assertEqual(api.deleted, [])
        self.assertEqual(result["candidate_count"], 0)
        self.assertIn({"id": 1, "reason": "batch-limit"}, result["skipped"])

    def test_missing_default_branch_fails_closed(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.branches.clear()

        with self.assertRaisesRegex(PruneError, "default branch is missing"):
            prune(api, apply=True)

        self.assertEqual(api.deleted, [])

    def test_unexpected_default_branch_fails_closed(self) -> None:
        api = FakeApi([cache(1, "refs/heads/deleted")])
        api.default_branch = "main"
        api.branches = {"main"}

        with self.assertRaisesRegex(PruneError, "expected default branch"):
            prune(api, apply=True)

        self.assertEqual(api.deleted, [])


class PagingApi(GitHubApi):
    def __init__(self) -> None:
        super().__init__(repository="owner/repository", token="token", api_url="https://api")
        self.requests: list[tuple[str, str]] = []
        self.queued_total_count = 101

    def _request(self, method: str, path: str) -> Any:
        self.requests.append((method, path))
        parsed = urllib.parse.urlparse(path)
        query = urllib.parse.parse_qs(parsed.query)
        page = int(query.get("page", ["1"])[0])

        if parsed.path.endswith("/branches"):
            if page == 1:
                return [{"name": f"branch-{index}"} for index in range(100)]
            if page == 2:
                return [{"name": "branch-100"}]
        if parsed.path.endswith("/actions/caches"):
            if page == 1:
                return {
                    "actions_caches": [
                        self._cache_payload(index + 1) for index in range(100)
                    ]
                }
            if page == 2:
                return {"actions_caches": [self._cache_payload(101)]}
        if parsed.path.endswith("/actions/runs"):
            status = query["status"][0]
            if status == "queued" and page == 1:
                return {
                    "total_count": self.queued_total_count,
                    "workflow_runs": [
                        {"status": "queued", "head_branch": f"active-{index}"}
                        for index in range(100)
                    ]
                }
            if status == "queued" and page == 2:
                return {
                    "total_count": self.queued_total_count,
                    "workflow_runs": [
                        {"status": "queued", "head_branch": "active-100"}
                    ]
                }
            return {"total_count": 0, "workflow_runs": []}
        raise AssertionError(f"unexpected request: {method} {path}")

    @staticmethod
    def _cache_payload(cache_id: int) -> dict[str, Any]:
        return {
            "id": cache_id,
            "ref": f"refs/heads/deleted-{cache_id}",
            "key": f"key-{cache_id}",
            "version": f"version-{cache_id}",
            "size_in_bytes": cache_id,
            "created_at": "2026-08-01T00:00:00Z",
            "last_accessed_at": "2026-08-01T00:00:00Z",
        }


class DeleteApi(GitHubApi):
    def __init__(self, *, missing: bool) -> None:
        super().__init__(repository="owner/repository", token="token", api_url="https://api")
        self.missing = missing

    def _request(self, method: str, path: str) -> Any:
        self.assert_delete_request(method, path)
        if self.missing:
            raise ApiError(404, "already gone")
        return None

    @staticmethod
    def assert_delete_request(method: str, path: str) -> None:
        if method != "DELETE" or not path.endswith("/actions/caches/42"):
            raise AssertionError(f"unexpected request: {method} {path}")


class PaginationTest(unittest.TestCase):
    def test_branch_and_cache_inventory_follow_every_page(self) -> None:
        api = PagingApi()

        branches = api.list_branches()
        caches = api.list_caches()

        self.assertEqual(len(branches), 101)
        self.assertIn("branch-100", branches)
        self.assertEqual(len(caches), 101)
        self.assertEqual(caches[-1].cache_id, 101)

    def test_every_active_status_is_queried_and_paginated(self) -> None:
        api = PagingApi()

        branches = api.list_active_run_branches()

        self.assertEqual(len(branches), 101)
        for status in ACTIVE_RUN_STATUSES:
            self.assertTrue(
                any(
                    f"status={status}" in path
                    for method, path in api.requests
                    if method == "GET"
                ),
                status,
            )
        queued_pages = [
            path
            for method, path in api.requests
            if method == "GET" and "status=queued" in path
        ]
        self.assertEqual(len(queued_pages), 2)

    def test_active_run_search_fails_closed_above_githubs_result_cap(self) -> None:
        api = PagingApi()
        api.queued_total_count = 1_001

        with self.assertRaisesRegex(PruneError, "API limit is 1000"):
            api.list_active_run_branches()

    def test_api_delete_treats_404_as_successful_idempotence(self) -> None:
        self.assertFalse(DeleteApi(missing=True).delete_cache(42))
        self.assertTrue(DeleteApi(missing=False).delete_cache(42))


class WorkflowContractTest(unittest.TestCase):
    def test_pruner_runs_only_from_trusted_automatic_events(self) -> None:
        workflow = (
            ROOT / ".github" / "workflows" / "prune-actions-caches.yml"
        ).read_text(encoding="utf-8")

        self.assertIn('cron: "29 9 * * *"', workflow)
        self.assertRegex(workflow, r"(?m)^  delete:\s*$")
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertNotIn("workflow_run:", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("actions: write", workflow)
        self.assertIn("contents: read", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertIn("github.event.ref_type == 'branch'", workflow)
        self.assertIn("quick-skin-actions-cache-pruning", workflow)
        self.assertIn("cancel-in-progress: false", workflow)

        authenticate = workflow.index("Authenticate protected cleanup implementation")
        checkout = workflow.index("Check out the exact protected cleanup implementation")
        self.assertLess(authenticate, checkout)
        self.assertIn('.default_branch == "master"', workflow)
        self.assertIn('.path == ".github/workflows/prune-actions-caches.yml"', workflow)
        self.assertIn('.head_branch == "master"', workflow)
        self.assertIn("branches/master", workflow)
        self.assertIn("ref: ${{ steps.trusted.outputs.implementation_sha }}", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("prune_actions_caches.py", workflow)
        self.assertIn("--apply", workflow)
        self.assertIn("--max-delete-count 75", workflow)
        self.assertIn("--max-delete-bytes 10737418240", workflow)


if __name__ == "__main__":
    unittest.main()
