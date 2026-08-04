from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import gradle_cache_policy  # noqa: E402


class GradleCachePolicyTest(unittest.TestCase):
    def write_matrix(self, root: Path, release_branch: object) -> Path:
        matrix = root / "release-matrix.json"
        matrix.write_text(
            json.dumps({"project": {"release_branch": release_branch}}),
            encoding="utf-8",
        )
        return matrix

    def test_approved_events_write_only_on_master_or_canonical_release_branch(self) -> None:
        for event_name in ("push", "workflow_dispatch"):
            for ref_name in ("master", "forge-and-fabric-1.20.1"):
                with self.subTest(event_name=event_name, ref_name=ref_name):
                    self.assertFalse(
                        gradle_cache_policy.is_read_only(
                            event_name=event_name,
                            ref_name=ref_name,
                            ref_type="branch",
                            ref_protected=True,
                            release_branch="forge-and-fabric-1.20.1",
                        )
                    )

    def test_pull_requests_and_other_events_are_always_read_only(self) -> None:
        for event_name in ("pull_request", "pull_request_target", "schedule", "merge_group"):
            with self.subTest(event_name=event_name):
                self.assertTrue(
                    gradle_cache_policy.is_read_only(
                        event_name=event_name,
                        ref_name="master",
                        ref_type="branch",
                        ref_protected=True,
                        release_branch="forge-and-fabric-1.20.1",
                    )
                )

    def test_tags_unrelated_branches_and_ephemeral_namespaces_are_read_only(self) -> None:
        refs = (
            "mc1.20.1-v1.0.0",
            "refs/tags/master",
            "feature/cache-policy",
            "automation/sync/forge-and-fabric-1.20.1/run-1",
            "codex/cache-policy",
            "dependabot/github_actions/gradle-actions-7",
            "refs/pull/42/merge",
        )
        for ref_name in refs:
            with self.subTest(ref_name=ref_name):
                self.assertTrue(
                    gradle_cache_policy.is_read_only(
                        event_name="push",
                        ref_name=ref_name,
                        ref_type="branch",
                        ref_protected=True,
                        release_branch="forge-and-fabric-1.20.1",
                    )
                )

    def test_ephemeral_release_branch_in_matrix_cannot_become_a_writer(self) -> None:
        for release_branch in (
            "automation/sync/forged/run-1",
            "codex/cache-policy",
            "dependabot/github_actions/gradle-actions-7",
            "refs/tags/master",
        ):
            with self.subTest(release_branch=release_branch):
                self.assertTrue(
                    gradle_cache_policy.is_read_only(
                        event_name="workflow_dispatch",
                        ref_name=release_branch,
                        ref_type="branch",
                        ref_protected=True,
                        release_branch=release_branch,
                    )
                )

    def test_unprotected_refs_are_always_read_only(self) -> None:
        for event_name in ("push", "workflow_dispatch"):
            for ref_name in ("master", "forge-and-fabric-1.20.1"):
                with self.subTest(event_name=event_name, ref_name=ref_name):
                    self.assertTrue(
                        gradle_cache_policy.is_read_only(
                            event_name=event_name,
                            ref_name=ref_name,
                            ref_type="branch",
                            ref_protected=False,
                            release_branch="forge-and-fabric-1.20.1",
                        )
                    )

    def test_protected_tags_named_like_stable_branches_are_read_only(self) -> None:
        for ref_name in ("master", "forge-and-fabric-1.20.1"):
            with self.subTest(ref_name=ref_name):
                self.assertTrue(
                    gradle_cache_policy.is_read_only(
                        event_name="push",
                        ref_name=ref_name,
                        ref_type="tag",
                        ref_protected=True,
                        release_branch="forge-and-fabric-1.20.1",
                    )
                )

    def test_matrix_loader_returns_the_authoritative_release_branch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            matrix = self.write_matrix(
                Path(temporary), "forge-and-fabric-1.20.1"
            )
            self.assertEqual(
                gradle_cache_policy.load_release_branch(matrix),
                "forge-and-fabric-1.20.1",
            )

    def test_matrix_loader_rejects_malformed_shapes_and_empty_branch(self) -> None:
        invalid_payloads = (
            "not JSON",
            "[]",
            "{}",
            '{"project": []}',
            '{"project": {}}',
            '{"project": {"release_branch": null}}',
            '{"project": {"release_branch": ""}}',
            '{"project": {"release_branch": "   "}}',
            '{"project": {"release_branch": " branch "}}',
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, payload in enumerate(invalid_payloads):
                matrix = root / f"invalid-{index}.json"
                matrix.write_text(payload, encoding="utf-8")
                with self.subTest(payload=payload), self.assertRaises(
                    gradle_cache_policy.PolicyError
                ):
                    gradle_cache_policy.load_release_branch(matrix)

    def test_missing_matrix_and_empty_cli_identity_are_rejected(self) -> None:
        with self.assertRaisesRegex(gradle_cache_policy.PolicyError, "cannot read"):
            gradle_cache_policy.load_release_branch(Path("does-not-exist.json"))
        for event_name, ref_name in (("", "master"), ("push", ""), (" ", "master")):
            with self.subTest(event_name=event_name, ref_name=ref_name), self.assertRaises(
                gradle_cache_policy.PolicyError
            ):
                gradle_cache_policy.is_read_only(
                    event_name=event_name,
                    ref_name=ref_name,
                    ref_type="branch",
                    ref_protected=True,
                    release_branch="forge-and-fabric-1.20.1",
                )

    def test_cli_prints_lowercase_boolean_and_fails_closed_on_bad_matrix(self) -> None:
        script = ROOT / "scripts" / "ci" / "gradle_cache_policy.py"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix = self.write_matrix(root, "forge-and-fabric-1.20.1")
            writable = subprocess.run(
                (
                    sys.executable,
                    str(script),
                    "--matrix",
                    str(matrix),
                    "--event-name",
                    "push",
                    "--ref-name",
                    "master",
                    "--ref-type",
                    "branch",
                    "--ref-protected",
                    "true",
                ),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(writable.returncode, 0)
            self.assertEqual(writable.stdout, "false\n")
            self.assertEqual(writable.stderr, "")

            matrix.write_text("{}", encoding="utf-8")
            rejected = subprocess.run(
                (
                    sys.executable,
                    str(script),
                    "--matrix",
                    str(matrix),
                    "--event-name",
                    "push",
                    "--ref-name",
                    "master",
                    "--ref-type",
                    "branch",
                    "--ref-protected",
                    "true",
                ),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(rejected.returncode, 2)
            self.assertEqual(rejected.stdout, "")
            self.assertIn("Gradle cache policy error", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
