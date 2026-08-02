from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"


def job_block(workflow: str, job: str) -> str:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)", text
    )
    if match is None:
        raise AssertionError(f"missing job {job} in {workflow}")
    return match.group(0)


class WorkflowSecurityTest(unittest.TestCase):
    def test_external_actions_are_pinned_to_full_commit_shas(self) -> None:
        for workflow in WORKFLOWS.glob("*.yml"):
            for line_number, line in enumerate(
                workflow.read_text(encoding="utf-8").splitlines(), start=1
            ):
                match = re.match(r"\s*(?:-\s+)?uses:\s+(\S+)", line)
                if match is None or match.group(1).startswith("./"):
                    continue
                with self.subTest(workflow=workflow.name, line=line_number):
                    self.assertRegex(match.group(1), r"^[^@]+@[0-9a-f]{40}$")

    def test_pr_and_nightly_e2e_select_matrix_owned_coverage(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        self.assertIn('cron: "17 3 * * *"', workflow)
        self.assertIn("github.event_name == 'schedule'", workflow)
        self.assertIn("'native-anchors' || 'pr-anchors'", workflow)
        self.assertIn('--kind "$MATRIX_KIND"', workflow)

    def test_visual_review_is_advisory_and_not_a_port_gate(self) -> None:
        visual = job_block("on-demand-e2e.yml", "visual-review")
        notify = job_block("on-demand-e2e.yml", "notify-version-port")
        self.assertIn("continue-on-error: true", visual)
        self.assertIn("- visual-review", notify)
        self.assertNotIn("VISUAL_RESULT", notify)

    def test_packaged_e2e_exposes_one_stable_required_context(self) -> None:
        required = job_block("on-demand-e2e.yml", "required-gate")
        self.assertIn("name: Packaged E2E gate", required)
        self.assertIn("always()", required)
        self.assertIn("needs.build.result", required)
        self.assertIn("needs.e2e.result", required)
        self.assertIn("inputs.attest_run_id == ''", required)

    def test_release_status_refresh_uses_a_pr_instead_of_master_push(self) -> None:
        workflow = (WORKFLOWS / "refresh-release-status.yml").read_text(encoding="utf-8")
        self.assertIn("AUTOMATION_BRANCH: automation/refresh-release-status", workflow)
        self.assertIn("gh pr create", workflow)
        self.assertIn('HEAD:refs/heads/$AUTOMATION_BRANCH', workflow)
        self.assertIn('gh workflow run build-gate.yml --ref "$AUTOMATION_BRANCH"', workflow)
        self.assertIn('gh workflow run on-demand-e2e.yml --ref "$AUTOMATION_BRANCH"', workflow)
        self.assertNotIn("git push origin HEAD:master", workflow)

    def test_ai_jobs_are_read_only_patch_producers(self) -> None:
        for workflow, job in (
            ("sync-version-branches.yml", "propose"),
            ("handle-version-port-result.yml", "propose-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertIn("contents: read", block)
                self.assertNotIn("contents: write", block)
                self.assertIn("persist-credentials: false", block)
                self.assertIn("ai_patch_policy.py", block)
                self.assertIn("actions/upload-artifact@", block)

    def test_read_only_port_can_start_a_local_merge(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        identity = 'git config user.name "github-actions[bot]"'
        merge = 'git merge --no-ff --no-commit "$source_sha"'
        self.assertIn(identity, propose)
        self.assertIn(
            'git config user.email "41898282+github-actions[bot]@users.noreply.github.com"',
            propose,
        )
        self.assertLess(propose.index(identity), propose.index(merge))

    def test_port_publisher_requires_a_complete_proposal(self) -> None:
        publish = job_block("sync-version-branches.yml", "publish")
        self.assertIn("needs.propose.result == 'success'", publish)

    def test_version_sync_accepts_only_master_as_its_source(self) -> None:
        discover = job_block("sync-version-branches.yml", "discover")
        self.assertIn('[[ "$SOURCE_REF" == refs/heads/master ]]', discover)

    def test_version_port_merge_revalidates_the_exact_pr(self) -> None:
        merge = job_block("handle-version-port-result.yml", "merge")
        for required in (
            "headRefOid",
            "baseRefOid",
            "automated-version-sync",
            'git merge-base --is-ancestor "$base_sha" "$head_sha"',
            '--match-head-commit "$head_sha"',
        ):
            with self.subTest(required=required):
                self.assertIn(required, merge)

    def test_credentialed_writers_do_not_receive_claude_credentials(self) -> None:
        for workflow, job in (
            ("sync-version-branches.yml", "publish"),
            ("handle-version-port-result.yml", "apply-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertIn("contents: write", block)
                self.assertIn("ai_patch_policy.py", block)
                self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", block)
                self.assertNotIn("node_modules/.bin/claude", block)

    def test_claude_install_is_exact_and_integrity_locked(self) -> None:
        package = json.loads(
            (ROOT / ".github" / "claude" / "package.json").read_text(encoding="utf-8")
        )
        lock = json.loads(
            (ROOT / ".github" / "claude" / "package-lock.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(package["dependencies"]["@anthropic-ai/claude-code"], "2.1.220")
        locked = lock["packages"]["node_modules/@anthropic-ai/claude-code"]
        self.assertEqual(locked["version"], "2.1.220")
        self.assertTrue(locked["integrity"].startswith("sha512-"))
        for workflow in WORKFLOWS.glob("*.yml"):
            self.assertNotIn("npm install -g @anthropic-ai/claude-code", workflow.read_text())

    def test_marketplace_jobs_receive_only_the_selected_secret(self) -> None:
        workflow = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
        self.assertEqual(
            workflow.count(
                "MODRINTH_TOKEN: ${{ matrix.marketplace == 'modrinth' "
                "&& secrets.MODRINTH_TOKEN || '' }}"
            ),
            2,
        )
        self.assertEqual(
            workflow.count(
                "CURSEFORGE_TOKEN: ${{ matrix.marketplace == 'curseforge' "
                "&& secrets.CURSEFORGE_TOKEN || '' }}"
            ),
            2,
        )


if __name__ == "__main__":
    unittest.main()
