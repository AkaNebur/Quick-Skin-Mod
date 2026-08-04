from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
UPLOAD_ARTIFACT_USE = re.compile(
    r"^\s+(?:-\s+)?uses:\s+actions/upload-artifact@\S+"
)


def workflow_paths() -> list[Path]:
    return sorted((*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")))


def job_block(workflow: str, job: str) -> str:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)", text
    )
    if match is None:
        raise AssertionError(f"missing job {job} in {workflow}")
    return match.group(0)


def upload_artifact_steps() -> list[tuple[str, str, str]]:
    """Return every named workflow step that uploads an Actions artifact."""

    uploads: list[tuple[str, str, str]] = []
    for workflow in workflow_paths():
        lines = workflow.read_text(encoding="utf-8").splitlines()
        for uses_index, line in enumerate(lines):
            if UPLOAD_ARTIFACT_USE.match(line) is None:
                continue
            uses_indent = len(line) - len(line.lstrip())
            step_index: int | None = None
            step_name: str | None = None
            for candidate_index in range(uses_index, -1, -1):
                candidate = lines[candidate_index]
                candidate_indent = len(candidate) - len(candidate.lstrip())
                if candidate_indent == uses_indent - 2 and candidate.lstrip().startswith(
                    "- name:"
                ):
                    step_index = candidate_index
                    step_name = candidate.lstrip()[len("- name:") :].strip()
                    break
                if candidate_indent < uses_indent - 2 and candidate.strip():
                    break
            if step_index is None or not step_name:
                raise AssertionError(
                    f"upload-artifact step at {workflow.name}:{uses_index + 1} "
                    "must have a non-empty name"
                )

            step_indent = uses_indent - 2
            end_index = len(lines)
            for candidate_index in range(uses_index + 1, len(lines)):
                candidate = lines[candidate_index]
                candidate_indent = len(candidate) - len(candidate.lstrip())
                if candidate_indent == step_indent and candidate.lstrip().startswith("- "):
                    end_index = candidate_index
                    break
                if candidate.strip() and candidate_indent < step_indent:
                    end_index = candidate_index
                    break
            uploads.append(
                (workflow.name, step_name, "\n".join(lines[step_index:end_index]))
            )
    return uploads


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

    def test_upload_artifact_retention_is_bounded_with_two_named_exceptions(
        self,
    ) -> None:
        uploads = upload_artifact_steps()
        raw_upload_count = sum(
            sum(
                UPLOAD_ARTIFACT_USE.match(line) is not None
                for line in workflow.read_text(encoding="utf-8").splitlines()
            )
            for workflow in workflow_paths()
        )
        self.assertEqual(len(uploads), raw_upload_count)

        long_lived = {
            (
                "pages.yml",
                "Roll the protected evidence cache forward",
                "${{ steps.cache.outputs.name }}",
            ),
            (
                "release.yml",
                "Upload immutable release bundle",
                "release-${{ steps.release.outputs.release_id }}",
            ),
        }
        observed_long_lived: set[tuple[str, str, str]] = set()
        for workflow, step_name, block in uploads:
            uses_line = next(
                line
                for line in block.splitlines()
                if "actions/upload-artifact@" in line
            )
            input_indent = " " * (len(uses_line) - len(uses_line.lstrip()) + 2)
            artifact_names = re.findall(
                rf"(?m)^{re.escape(input_indent)}name:[ \t]*(.+?)[ \t]*$", block
            )
            retention_values = re.findall(
                rf"(?m)^{re.escape(input_indent)}retention-days:[ \t]*(.+?)[ \t]*$",
                block,
            )
            with self.subTest(workflow=workflow, step=step_name):
                self.assertEqual(len(artifact_names), 1)
                self.assertEqual(len(retention_values), 1)
                identity = (workflow, step_name, artifact_names[0])
                expected_retention = "90" if identity in long_lived else "1"
                self.assertEqual(retention_values[0], expected_retention)
                if retention_values[0] == "90":
                    observed_long_lived.add(identity)
        self.assertEqual(observed_long_lived, long_lived)

    def test_gradle_cache_writes_are_limited_to_protected_master_builds(self) -> None:
        build = job_block("build-gate.yml", "build")
        e2e = job_block("on-demand-e2e.yml", "build")
        release = job_block("release.yml", "build")
        policy = (ROOT / "scripts" / "ci" / "gradle_cache_policy.py").read_text(
            encoding="utf-8"
        )

        setup_count = sum(
            workflow.read_text(encoding="utf-8").count("gradle/actions/setup-gradle@")
            for workflow in WORKFLOWS.glob("*.yml")
        )
        self.assertEqual(setup_count, 3)
        self.assertIn("scripts/ci/gradle_cache_policy.py", build)
        self.assertIn("--matrix release/release-matrix.json", build)
        self.assertIn('--event-name "$GITHUB_EVENT_NAME"', build)
        self.assertIn('--ref-name "$GITHUB_REF_NAME"', build)
        self.assertIn("REF_PROTECTED: ${{ github.ref_protected }}", build)
        self.assertIn("REF_TYPE: ${{ github.ref_type }}", build)
        self.assertIn('--ref-type "$REF_TYPE"', build)
        self.assertIn('--ref-protected "$REF_PROTECTED"', build)
        self.assertIn(
            "cache-read-only: ${{ steps.gradle-cache.outputs.read_only }}", build
        )
        self.assertIn("cache-cleanup: on-success", build)
        for workflow, block in (("on-demand-e2e.yml", e2e), ("release.yml", release)):
            with self.subTest(workflow=workflow):
                self.assertEqual(block.count("gradle/actions/setup-gradle@"), 1)
                self.assertEqual(block.count("cache-read-only: true"), 1)
                self.assertNotIn("gradle_cache_policy.py", block)

        self.assertIn('WRITER_EVENTS = frozenset({"push", "workflow_dispatch"})', policy)
        self.assertIn("event_name not in WRITER_EVENTS", policy)
        self.assertIn('or ref_type != "branch"', policy)
        self.assertIn("or not ref_protected", policy)
        self.assertIn('return ref_name != "master"', policy)

    def test_visual_review_is_advisory_and_not_a_port_gate(self) -> None:
        visual = job_block("on-demand-e2e.yml", "visual-review")
        pages = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        notify = job_block("on-demand-e2e.yml", "notify-version-port")
        self.assertIn("continue-on-error: true", visual)
        self.assertIn("continue-on-error: true", pages)
        self.assertIn("- visual-review", notify)
        self.assertNotIn("VISUAL_RESULT", notify)
        self.assertNotIn("prepare-pages-evidence", notify)
        self.assertIn("visual-review-manifest.sha256", visual)
        self.assertIn("visual-review-checker.py", visual)
        self.assertIn("sha256sum --check", visual)
        self.assertIn("git diff --exit-code", visual)
        self.assertIn('python3 "$RUNNER_TEMP/visual-review-checker.py"', visual)

    def test_pages_fan_in_uses_protected_code_and_exact_release_heads(self) -> None:
        workflow = (WORKFLOWS / "pages.yml").read_text(encoding="utf-8")
        discover = job_block("pages.yml", "discover")
        collect = job_block("pages.yml", "collect")
        build = job_block("pages.yml", "build")
        deploy = job_block("pages.yml", "deploy")
        refresh = job_block("pages.yml", "refresh-cache")
        selector = (ROOT / "scripts" / "pages" / "select_artifact.py").read_text(
            encoding="utf-8"
        )

        self.assertNotIn("pull_request_target", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("ref: master", discover)
        self.assertIn("implementation_sha:", discover)
        self.assertIn("git rev-parse HEAD", discover)
        for block in (collect, build):
            self.assertIn(
                "ref: ${{ needs.discover.outputs.implementation_sha }}",
                block,
            )
        for block in (discover, collect, build):
            self.assertIn("persist-credentials: false", block)
            self.assertNotIn("id-token: write", block)
            self.assertNotIn("pages: write", block)
        self.assertIn("artifact.head_sha == current_sha", selector)
        self.assertIn('artifact.head_branch == "master"', selector)
        self.assertIn("TRIGGER_RUN_ID", discover)
        self.assertIn('.event == "workflow_dispatch"', discover)
        self.assertIn('.path == ".github/workflows/on-demand-e2e.yml"', discover)
        self.assertIn("github.ref == 'refs/heads/master'", discover)
        self.assertIn("scripts/pages/evidence.py validate", collect)
        self.assertIn("--only-branch", collect)
        self.assertIn("source_run_id", collect)
        self.assertIn("target_run_id", collect)
        self.assertIn("digest-mismatch: error", collect)
        self.assertIn("needs:\n      - discover\n      - collect", build)
        self.assertIn("needs.discover.outputs.branches", build)
        self.assertIn("--expected-branches-json", build)
        self.assertIn("Recheck every branch immediately before rendering", build)
        self.assertIn("name: github-pages", deploy)
        self.assertIn("pages: write", deploy)
        self.assertIn("id-token: write", deploy)
        self.assertNotIn("actions/checkout@", deploy)
        self.assertNotRegex(deploy, r"(?m)^\s+run:")
        self.assertIn('cron: "43 4 1 * *"', workflow)
        self.assertIn("- deploy", refresh)
        self.assertIn("scripts/pages/select_artifact.py", collect)
        self.assertIn('cache_name = f"pages-cache-{branch}--{current_sha}"', selector)
        self.assertIn('legacy_name = f"pages-cache-{branch}"', selector)
        self.assertIn("max(exact, key=lambda item: item.order)", selector)
        self.assertIn("if exact:", selector)
        self.assertIn("^[0-9a-f]{40}$", refresh)
        self.assertIn("name=pages-cache-%s--%s", refresh)
        self.assertIn("name: ${{ steps.cache.outputs.name }}", refresh)
        self.assertIn("actions/checkout@", refresh)
        self.assertIn(
            "ref: ${{ needs.discover.outputs.implementation_sha }}", refresh
        )
        self.assertIn("persist-credentials: false", refresh)
        self.assertIn("scripts/pages/evidence.py validate", refresh)
        self.assertIn("--kind compact", refresh)
        self.assertNotIn("id-token: write", refresh)
        self.assertNotIn("pages: write", refresh)
        self.assertIn("api.list_artifacts(handoff_name)", selector)
        self.assertIn("api.list_artifacts(cache_name)", selector)
        self.assertIn("--require-hashes", build)
        self.assertIn("scripts/pages/requirements.txt", build)

    def test_pages_evidence_rotation_is_post_success_bounded_and_exact(self) -> None:
        workflow = (WORKFLOWS / "rotate-pages-evidence.yml").read_text(encoding="utf-8")
        rotate = job_block("rotate-pages-evidence.yml", "rotate")
        handoff = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        rotator = (ROOT / "scripts" / "pages" / "rotate_artifacts.py").read_text(
            encoding="utf-8"
        )

        self.assertIn("permissions: {}", workflow)
        self.assertIn("workflows:\n      - Project site", workflow)
        self.assertIn("types:\n      - completed", workflow)
        self.assertIn("branches:\n      - master", workflow)
        self.assertIn("quick-skin-pages-evidence-rotation", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", rotate)
        self.assertIn("actions: write", rotate)
        self.assertIn("contents: read", rotate)
        self.assertNotIn("pages: write", rotate)
        self.assertNotIn("id-token: write", rotate)
        self.assertNotIn("continue-on-error", rotate)
        authenticate = rotate.index("Authenticate the successful protected Pages run")
        checkout = rotate.index("Check out the exact protected rotation implementation")
        self.assertLess(authenticate, checkout)
        self.assertIn('.path == ".github/workflows/pages.yml"', rotate)
        self.assertIn('.head_branch == "master"', rotate)
        self.assertIn(".head_repository.full_name == $repository", rotate)
        self.assertIn(".workflow_id == $workflow_id", rotate)
        self.assertIn('"repos/$GITHUB_REPOSITORY/branches/master" --jq .commit.sha', rotate)
        self.assertIn("ref: ${{ steps.trigger.outputs.rotation_sha }}", rotate)
        self.assertIn("persist-credentials: false", rotate)
        self.assertIn("pattern: pages-cache-*", rotate)
        self.assertIn("run-id: ${{ steps.trigger.outputs.run_id }}", rotate)
        self.assertIn("digest-mismatch: error", rotate)
        self.assertIn("scripts/pages/rotate_artifacts.py", rotate)
        self.assertIn("--pages-run-id", rotate)
        self.assertIn("--pages-run-sha", rotate)
        self.assertIn("steps.trigger.outputs.pages_sha", rotate)

        self.assertIn("actions: read", handoff)
        self.assertNotIn("actions: write", handoff)
        self.assertIn("pages-e2e-${{ github.ref_name }}", handoff)
        self.assertIn("retention-days: 1", handoff)
        self.assertIn('expected_names = {"github-pages"}', rotator)
        self.assertIn(
            'f"collected-pages-{generation.branch}" for generation in generations',
            rotator,
        )
        self.assertIn("for artifact in (*old_caches, *handoffs):", rotator)
        self.assertIn("retire_pages_run_transients(", rotator)
        self.assertIn("api.get_artifact(artifact.artifact_id)", rotator)
        self.assertIn("api.delete_artifact(artifact.artifact_id)", rotator)

    def test_bounded_actions_caches_are_pruned_by_exact_id_from_protected_code(
        self,
    ) -> None:
        workflow = (WORKFLOWS / "prune-actions-caches.yml").read_text(
            encoding="utf-8"
        )
        prune = job_block("prune-actions-caches.yml", "prune")
        implementation = (
            ROOT / "scripts" / "ci" / "prune_actions_caches.py"
        ).read_text(encoding="utf-8")

        self.assertIn("permissions: {}", workflow)
        self.assertIn("schedule:", workflow)
        self.assertRegex(workflow, r'cron: "\d+ \d+ \* \* \*"')
        self.assertIn("github.event_name == 'schedule'", prune)
        self.assertIn("actions: write", prune)
        self.assertIn("contents: read", prune)
        self.assertIn("[[ \"$GITHUB_REF\" == refs/heads/master ]]", prune)
        self.assertIn(".default_branch == \"master\"", prune)
        self.assertIn('"repos/$GITHUB_REPOSITORY/branches/master" --jq .commit.sha', prune)
        self.assertIn("ref: ${{ steps.trusted.outputs.implementation_sha }}", prune)
        self.assertIn("persist-credentials: false", prune)
        self.assertIn("scripts/ci/prune_actions_caches.py", prune)
        self.assertIn("--expected-default-branch master", prune)
        self.assertIn("--apply", prune)
        self.assertNotIn("release-matrix", prune)

        self.assertIn('BRANCH_REF_PREFIX = "refs/heads/"', implementation)
        self.assertIn("cache.branch not in existing_branches", implementation)
        self.assertIn("cache.branch not in active_run_branches", implementation)
        self.assertIn("candidates, deferred = _bounded_batch(", implementation)
        self.assertIn("current = api.get_cache(cache)", implementation)
        self.assertIn("if api.has_any_active_run():", implementation)
        self.assertIn("if api.branch_exists(branch):", implementation)
        self.assertIn("api.has_successful_build(branch, sha)", implementation)
        self.assertIn("replacement_current = api.get_cache(replacement)", implementation)
        self.assertIn('"superseded-gradle-home"', implementation)
        self.assertIn('"protected_generation_ids"', implementation)
        self.assertIn("api.delete_cache(cache.cache_id)", implementation)
        self.assertIn('f"/actions/caches/{cache_id}"', implementation)

    def test_pages_actions_use_reviewed_immutable_versions(self) -> None:
        workflow = (WORKFLOWS / "pages.yml").read_text(encoding="utf-8")
        self.assertIn(
            "actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9",
            workflow,
        )
        self.assertIn(
            "actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128",
            workflow,
        )

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
        self.assertIn("scripts/release/branch_readme.py", workflow)
        self.assertIn("--profile-branch master", workflow)
        self.assertNotIn("git push origin HEAD:master", workflow)

    def test_release_test_jobs_install_locked_pages_dependency(self) -> None:
        for workflow, job in (
            ("build-gate.yml", "build"),
            ("refresh-release-status.yml", "refresh"),
            ("sync-version-branches.yml", "publish"),
            ("handle-version-port-result.yml", "apply-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                install = block.index("scripts/pages/requirements.txt")
                tests = block.index("scripts/release/tests")
                self.assertIn("--only-binary=:all:", block)
                self.assertIn("--require-hashes", block)
                self.assertLess(install, tests)
        sync_publish = job_block("sync-version-branches.yml", "publish")
        self.assertIn(
            "--requirement controller/scripts/pages/requirements.txt",
            sync_publish,
        )

    def test_build_gate_checks_the_actual_branch_readme_profile(self) -> None:
        build = job_block("build-gate.yml", "build")

        self.assertIn("Validate branch-specific README profile", build)
        self.assertIn("BASE_REF: ${{ github.base_ref }}", build)
        self.assertIn("REF_NAME: ${{ github.ref_name }}", build)
        self.assertIn("scripts/release/branch_readme.py", build)
        self.assertIn('--profile-branch "$profile_branch"', build)
        self.assertIn("--check", build)
        self.assertIn("node --check site/assets/site.js", build)
        self.assertIn("node --check site/assets/gallery.js", build)

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

    def test_version_sync_renders_and_revalidates_target_readme(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        publish = job_block("sync-version-branches.yml", "publish")

        self.assertIn("scripts/release/branch_readme.py", propose)
        self.assertIn('--profile-branch "$TARGET_BRANCH"', propose)
        self.assertIn("--bootstrap", propose)
        self.assertIn("git add -- README.md", propose)
        self.assertIn("scripts/release/branch_readme.py", publish)
        self.assertIn('--profile-branch "$TARGET_BRANCH"', publish)
        self.assertLess(
            publish.index("git apply --index ../proposal/version-port.patch"),
            publish.index("scripts/release/branch_readme.py"),
        )

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

    def test_version_port_merge_bridges_verified_runs_to_required_statuses(self) -> None:
        merge = job_block("handle-version-port-result.yml", "merge")
        governance = json.loads(
            (ROOT / "release" / "github-governance.json").read_text(encoding="utf-8")
        )

        self.assertIn("statuses: write", merge)
        self.assertIn('repos/$GITHUB_REPOSITORY/statuses/$head_sha', merge)
        self.assertIn("$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$run_id", merge)
        for context in governance["required_checks"]:
            with self.subTest(context=context):
                self.assertIn(f'"{context}"', merge)

        revalidation = merge.index('git merge-base --is-ancestor "$base_sha" "$head_sha"')
        publish = merge.index("publish_required_status()")
        merge_pr = merge.index('gh pr merge "$pr_number"')
        self.assertLess(revalidation, publish)
        self.assertLess(publish, merge_pr)

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
