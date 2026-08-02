from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import github_governance  # noqa: E402


class GitHubGovernanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = github_governance.load_config(
            ROOT / "release" / "github-governance.json"
        )

    def test_branch_rules_have_no_bypass_and_require_stable_strict_checks(self) -> None:
        default, releases, tags = github_governance.desired_rulesets(self.config)
        for ruleset in (default, releases, tags):
            self.assertEqual(ruleset["enforcement"], "active")
            self.assertEqual(ruleset["bypass_actors"], [])
            self.assertIn({"type": "deletion"}, ruleset["rules"])
            self.assertIn({"type": "non_fast_forward"}, ruleset["rules"])

        for ruleset in (default, releases):
            checks = next(
                rule for rule in ruleset["rules"]
                if rule["type"] == "required_status_checks"
            )["parameters"]
            self.assertTrue(checks["strict_required_status_checks_policy"])
            self.assertEqual(
                [item["context"] for item in checks["required_status_checks"]],
                ["Build and verify", "Packaged E2E gate"],
            )
            pull_request = next(
                rule for rule in ruleset["rules"]
                if rule["type"] == "pull_request"
            )
            self.assertEqual(
                pull_request["parameters"]["required_approving_review_count"], 0
            )
            self.assertTrue(
                pull_request["parameters"]["required_review_thread_resolution"]
            )

        self.assertEqual(
            tags["conditions"]["ref_name"]["include"], ["refs/tags/mc*-v*"]
        )

    def test_release_environment_has_human_review_and_narrow_sources(self) -> None:
        environment = github_governance.desired_environment(self.config)
        self.assertEqual(
            environment["reviewers"], [{"type": "User", "id": 105746531}]
        )
        self.assertFalse(environment["prevent_self_review"])
        self.assertEqual(
            {
                github_governance.policy_identity(item)
                for item in self.config["release_environment"]["deployment_policies"]
            },
            {("*-and-*-*", "branch"), ("mc*-v*", "tag")},
        )

    def test_semantic_comparison_ignores_api_metadata_but_rejects_drift(self) -> None:
        expected = {"name": "managed", "rules": [{"type": "deletion"}]}
        actual = {
            "id": 42,
            "name": "managed",
            "rules": [{"type": "deletion", "unexpected_metadata": "safe"}],
        }
        self.assertTrue(github_governance.subset_matches(actual, expected))
        actual["rules"] = [{"type": "non_fast_forward"}]
        self.assertFalse(github_governance.subset_matches(actual, expected))

    def test_readiness_tokens_fail_closed(self) -> None:
        self.assertEqual(
            github_governance.require_tokens(
                "pull_request:\n  job:\n    name: Build and verify\n",
                ("pull_request:", "name: Build and verify"),
                "master:build-gate",
            ),
            [],
        )
        self.assertEqual(
            github_governance.require_tokens(
                None, ("required",), "release:workflow"
            ),
            ["release:workflow: file is missing"],
        )
        self.assertEqual(
            github_governance.require_tokens(
                "pull_request:\n", ("Packaged E2E gate",), "release:e2e"
            ),
            ["release:e2e: missing 'Packaged E2E gate'"],
        )


if __name__ == "__main__":
    unittest.main()
