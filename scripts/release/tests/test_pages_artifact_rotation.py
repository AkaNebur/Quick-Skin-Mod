from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "pages"))

from rotate_artifacts import (  # noqa: E402
    ApiError,
    Artifact,
    BranchGeneration,
    GitHubApi,
    RotationError,
    load_generations,
    rotate_branch,
    select_consumed_handoffs,
    select_old_caches,
)
from select_artifact import select_source  # noqa: E402


TARGET_SHA = "a" * 40
PAGES_SHA = "b" * 40
OLD_PAGES_SHA = "c" * 40
BRANCH = "forge-and-fabric-1.20.1"
REPOSITORY = "AkaNebur/Quick-Skin-Mod"


def artifact(
    artifact_id: int,
    name: str,
    created_at: str,
    *,
    run_id: int,
    head_branch: str,
    head_sha: str,
    expired: bool = False,
) -> Artifact:
    return Artifact(
        artifact_id=artifact_id,
        name=name,
        expired=expired,
        size_in_bytes=100,
        created_at=created_at,
        run_id=run_id,
        head_branch=head_branch,
        head_sha=head_sha,
    )


def run(
    run_id: int,
    *,
    workflow: str,
    event: str,
    branch: str,
    sha: str,
    conclusion: str = "success",
) -> dict[str, Any]:
    return {
        "id": run_id,
        "status": "completed",
        "conclusion": conclusion,
        "event": event,
        "path": workflow,
        "head_branch": branch,
        "head_sha": sha,
        "head_repository": {"full_name": REPOSITORY},
    }


class FakeApi:
    def __init__(
        self,
        *,
        keep: Artifact,
        inventories: dict[str, list[Artifact]],
        runs: dict[int, dict[str, Any]],
        branch_sha: str = TARGET_SHA,
        missing_on_delete: set[int] | None = None,
    ) -> None:
        self.keep = keep
        self.inventories = inventories
        self.runs = runs
        self.branch_sha = branch_sha
        self.missing_on_delete = missing_on_delete or set()
        self.deleted: list[int] = []

    def get_artifact(self, artifact_id: int) -> Artifact:
        if artifact_id != self.keep.artifact_id:
            raise AssertionError(f"unexpected artifact lookup {artifact_id}")
        return self.keep

    def list_artifacts(self, name: str) -> list[Artifact]:
        return list(self.inventories.get(name, []))

    def list_artifacts_with_prefix(self, prefix: str) -> list[Artifact]:
        by_id = {
            item.artifact_id: item
            for values in self.inventories.values()
            for item in values
            if item.name.startswith(prefix)
        }
        return list(by_id.values())

    def list_artifacts_for_run(self, run_id: int) -> list[Artifact]:
        raise AssertionError("not used by rotate_branch")

    def get_run(self, run_id: int) -> dict[str, Any]:
        return self.runs[run_id]

    def get_branch_sha(self, branch: str) -> str:
        self.assert_branch(branch)
        return self.branch_sha

    def delete_artifact(self, artifact_id: int) -> None:
        if artifact_id in self.missing_on_delete:
            raise ApiError(404, "already deleted")
        self.deleted.append(artifact_id)

    def assert_branch(self, branch: str) -> None:
        if branch != BRANCH:
            raise AssertionError(f"unexpected branch {branch}")


class PagesArtifactRotationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.keep = artifact(
            200,
            f"pages-cache-{BRANCH}--{TARGET_SHA}",
            "2026-08-03T12:00:00Z",
            run_id=900,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        self.generation = BranchGeneration(
            branch=BRANCH,
            target_sha=TARGET_SHA,
            target_run_id=800,
            keep=self.keep,
        )

    def test_cache_selector_keeps_current_newer_foreign_and_expired_artifacts(self) -> None:
        expected_name = f"pages-cache-{BRANCH}"
        values = [
            artifact(
                100,
                expected_name,
                "2026-08-03T11:00:00Z",
                run_id=700,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            artifact(
                199,
                f"{expected_name}--{OLD_PAGES_SHA}",
                self.keep.created_at,
                run_id=701,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            self.keep,
            artifact(
                300,
                f"{expected_name}--{TARGET_SHA}",
                "2026-08-03T13:00:00Z",
                run_id=901,
                head_branch="master",
                head_sha=PAGES_SHA,
            ),
            artifact(
                50,
                expected_name,
                "2026-08-03T10:00:00Z",
                run_id=600,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
                expired=True,
            ),
            artifact(
                40,
                "pages-cache-another-branch",
                "2026-08-03T10:00:00Z",
                run_id=500,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            artifact(
                41,
                f"{expected_name}--collision--{OLD_PAGES_SHA}",
                "2026-08-03T10:00:00Z",
                run_id=501,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
            artifact(
                42,
                f"{expected_name}--not-a-sha",
                "2026-08-03T10:00:00Z",
                run_id=502,
                head_branch="master",
                head_sha=OLD_PAGES_SHA,
            ),
        ]
        selected = select_old_caches(values, branch=BRANCH, keep=self.keep)
        self.assertEqual([item.artifact_id for item in selected], [100, 199])

    def test_generation_requires_cache_name_bound_to_manifest_target_sha(self) -> None:
        manifest = {
            "provenance": {"target": {"sha": TARGET_SHA, "run_id": 800}}
        }
        with tempfile.TemporaryDirectory() as temporary:
            evidence_root = Path(temporary)
            (evidence_root / BRANCH).mkdir()
            with patch("rotate_artifacts.validate_bundle", return_value=manifest):
                generations = load_generations(
                    evidence_root=evidence_root,
                    repository=REPOSITORY,
                    pages_run_id=900,
                    pages_run_sha=PAGES_SHA,
                    trigger_artifacts=[self.keep],
                )
                self.assertEqual(generations, [self.generation])

                mismatched = artifact(
                    201,
                    f"pages-cache-{BRANCH}--{OLD_PAGES_SHA}",
                    self.keep.created_at,
                    run_id=900,
                    head_branch="master",
                    head_sha=PAGES_SHA,
                )
                with self.assertRaises(RotationError):
                    load_generations(
                        evidence_root=evidence_root,
                        repository=REPOSITORY,
                        pages_run_id=900,
                        pages_run_sha=PAGES_SHA,
                        trigger_artifacts=[mismatched],
                    )

                (evidence_root / "unexpected.txt").write_text("not evidence", encoding="utf-8")
                with self.assertRaises(RotationError):
                    load_generations(
                        evidence_root=evidence_root,
                        repository=REPOSITORY,
                        pages_run_id=900,
                        pages_run_sha=PAGES_SHA,
                        trigger_artifacts=[self.keep],
                    )

    def test_handoff_selector_deletes_only_the_consumed_exact_run(self) -> None:
        expected_name = f"pages-e2e-{BRANCH}"
        consumed = artifact(
            110,
            expected_name,
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        newer_run = artifact(
            120,
            expected_name,
            "2026-08-03T11:45:00Z",
            run_id=801,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        selected = select_consumed_handoffs(
            [consumed, newer_run],
            branch=BRANCH,
            target_run_id=800,
            target_sha=TARGET_SHA,
            keep=self.keep,
        )
        self.assertEqual(selected, [consumed])

    def test_source_selection_prefers_cache_newer_than_consumed_handoff(self) -> None:
        handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        legacy = artifact(
            300,
            f"pages-cache-{BRANCH}",
            "2026-08-03T13:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                handoff.name: [handoff],
                self.keep.name: [self.keep],
                legacy.name: [legacy],
            },
            runs={
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, self.keep)
        self.assertNotIn(700, api.runs)

    def test_source_selection_prefers_a_newer_same_sha_handoff(self) -> None:
        handoff = artifact(
            300,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T13:00:00Z",
            run_id=801,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={handoff.name: [handoff], self.keep.name: [self.keep]},
            runs={
                801: run(
                    801,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, handoff)

    def test_source_selection_excludes_a_later_cache_for_an_old_sha(self) -> None:
        handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        stale = artifact(
            400,
            f"pages-cache-{BRANCH}--{OLD_PAGES_SHA}",
            "2026-08-03T14:00:00Z",
            run_id=901,
            head_branch="master",
            head_sha=PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={handoff.name: [handoff], stale.name: [stale]},
            runs={
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                )
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, handoff)
        self.assertNotIn(901, api.runs)

    def test_source_selection_skips_invalid_owner_and_supports_legacy_fallback(self) -> None:
        legacy_name = f"pages-cache-{BRANCH}"
        invalid = artifact(
            300,
            legacy_name,
            "2026-08-03T13:00:00Z",
            run_id=701,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        valid = artifact(
            200,
            legacy_name,
            "2026-08-03T12:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={legacy_name: [invalid, valid]},
            runs={
                701: run(
                    701,
                    workflow=".github/workflows/build-gate.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
            },
        )
        selected = select_source(
            api,
            repository=REPOSITORY,
            branch=BRANCH,
            current_sha=TARGET_SHA,
        )
        self.assertEqual(selected, valid)

    def test_rotation_preserves_a_fallback_until_successful_keep_is_verified(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        handoff = artifact(
            110,
            f"pages-e2e-{BRANCH}",
            "2026-08-03T11:30:00Z",
            run_id=800,
            head_branch=BRANCH,
            head_sha=TARGET_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                handoff.name: [handoff],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_run",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                800: run(
                    800,
                    workflow=".github/workflows/on-demand-e2e.yml",
                    event="workflow_dispatch",
                    branch=BRANCH,
                    sha=TARGET_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
        )
        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [100, 110])
        self.assertEqual(api.deleted, [100, 110])

    def test_rotation_is_a_noop_if_the_release_head_changed(self) -> None:
        api = FakeApi(
            keep=self.keep,
            inventories={},
            runs={},
            branch_sha="d" * 40,
        )
        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [])
        self.assertEqual(api.deleted, [])

    def test_delete_404_is_idempotent(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                f"pages-e2e-{BRANCH}": [],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/pages.yml",
                    event="schedule",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                ),
                900: run(
                    900,
                    workflow=".github/workflows/pages.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=PAGES_SHA,
                ),
            },
            missing_on_delete={100},
        )
        deleted = rotate_branch(
            api,
            self.generation,
            repository=REPOSITORY,
            pages_run_id=900,
            pages_run_sha=PAGES_SHA,
            delete_delay_seconds=0,
        )
        self.assertEqual(deleted, [100])

    def test_wrong_owner_workflow_fails_closed_before_any_delete(self) -> None:
        old_cache = artifact(
            100,
            f"pages-cache-{BRANCH}",
            "2026-08-03T11:00:00Z",
            run_id=700,
            head_branch="master",
            head_sha=OLD_PAGES_SHA,
        )
        api = FakeApi(
            keep=self.keep,
            inventories={
                old_cache.name: [old_cache, self.keep],
                f"pages-e2e-{BRANCH}": [],
            },
            runs={
                700: run(
                    700,
                    workflow=".github/workflows/build-gate.yml",
                    event="workflow_dispatch",
                    branch="master",
                    sha=OLD_PAGES_SHA,
                )
            },
        )
        with self.assertRaises(RotationError):
            rotate_branch(
                api,
                self.generation,
                repository=REPOSITORY,
                pages_run_id=900,
                pages_run_sha=PAGES_SHA,
                delete_delay_seconds=0,
            )
        self.assertEqual(api.deleted, [])

    def test_artifact_listing_paginates_past_one_hundred(self) -> None:
        class StubApi(GitHubApi):
            def __init__(self) -> None:
                super().__init__(repository=REPOSITORY, token="token", api_url="https://api")
                self.pages: list[int] = []

            def _request(self, method: str, path: str) -> Any:
                self.assert_request(method, path)
                page = int(path.rsplit("page=", 1)[1])
                self.pages.append(page)
                count = 100 if page == 1 else 1
                return {
                    "artifacts": [
                        {
                            "id": page * 1000 + index + 1,
                            "name": "pages-cache-test",
                            "expired": False,
                            "size_in_bytes": 1,
                            "created_at": "2026-08-03T10:00:00Z",
                            "workflow_run": {
                                "id": 1,
                                "head_branch": "master",
                                "head_sha": PAGES_SHA,
                            },
                        }
                        for index in range(count)
                    ]
                }

            def assert_request(self, method: str, path: str) -> None:
                if method != "GET" or "per_page=100" not in path:
                    raise AssertionError(f"unexpected request: {method} {path}")

        api = StubApi()
        values = api.list_artifacts("pages-cache-test")
        self.assertEqual(len(values), 101)
        self.assertEqual(api.pages, [1, 2])


if __name__ == "__main__":
    unittest.main()
