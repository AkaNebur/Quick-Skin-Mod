from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import matrix as release_matrix  # noqa: E402
import release_identity  # noqa: E402


class ReleaseIdentityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.matrix_path = ROOT / "release" / "release-matrix.json"
        cls.identity = release_identity.derive(cls.matrix_path)

    def test_identity_names_minecraft_era_and_logical_mod_version(self) -> None:
        self.assertEqual(self.identity.release_id, "mc1.21.3-v3.0.0")
        self.assertEqual(self.identity.tag, self.identity.release_id)
        self.assertEqual(self.identity.branch, "fabric-and-neoforge-1.21.3")

    def test_publication_matrix_is_artifact_times_marketplace(self) -> None:
        data = release_matrix.load_matrix(self.matrix_path)
        matrix = release_matrix.gha_matrix(data, "publications", "3.0.0")
        rows = matrix["include"]
        self.assertEqual(len(rows), data["lane_count"] * 2)
        self.assertEqual(
            {(row["artifact_node"], row["marketplace"]) for row in rows},
            {
                (artifact["artifact_node"], marketplace)
                for artifact in data["artifacts"]
                for marketplace in ("modrinth", "curseforge")
            },
        )
        self.assertTrue(all(row["publication_id"].startswith(self.identity.release_id) for row in rows))

    def test_tag_and_manual_events_bind_exact_branch_head(self) -> None:
        commit = "a" * 40
        release_identity.validate_ci_event(
            self.identity,
            event_name="push",
            ref_type="tag",
            ref_name=self.identity.tag,
            event_commit=commit,
            checkout_commit=commit,
            release_branch_head=commit,
        )
        release_identity.validate_ci_event(
            self.identity,
            event_name="workflow_dispatch",
            ref_type="branch",
            ref_name=self.identity.branch,
            event_commit=commit,
            checkout_commit=commit,
            release_branch_head=commit,
        )

    def test_rejects_ambiguous_or_stale_release_sources(self) -> None:
        commit = "a" * 40
        cases = (
            {"event_name": "push", "ref_type": "tag", "ref_name": "v3.0.0"},
            {
                "event_name": "workflow_dispatch",
                "ref_type": "branch",
                "ref_name": "master",
            },
        )
        for overrides in cases:
            with self.subTest(overrides=overrides), self.assertRaises(
                release_identity.ReleaseIdentityError
            ):
                release_identity.validate_ci_event(
                    self.identity,
                    event_name=overrides["event_name"],
                    ref_type=overrides["ref_type"],
                    ref_name=overrides["ref_name"],
                    event_commit=commit,
                    checkout_commit=commit,
                    release_branch_head=commit,
                )
        with self.assertRaises(release_identity.ReleaseIdentityError):
            release_identity.validate_ci_event(
                self.identity,
                event_name="push",
                ref_type="tag",
                ref_name=self.identity.tag,
                event_commit=commit,
                checkout_commit=commit,
                release_branch_head="b" * 40,
            )

    def test_matrix_rejects_a_release_branch_for_other_loaders(self) -> None:
        data = release_matrix.load_matrix(self.matrix_path)
        data["project"] = dict(data["project"])
        data["project"]["release_branch"] = "forge-and-neoforge-1.21.1"
        with self.assertRaisesRegex(
            release_matrix.MatrixError,
            "loaders disagree",
        ):
            release_matrix.validate_matrix(data)

    def test_changelog_must_match_and_tag_publication_must_be_dated(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            changelog = Path(temporary) / "CHANGELOG.md"
            changelog.write_text(
                "# Changelog\n\n## 3.0.0 (unreleased)\n\n### Added\n\n- Feature.\n",
                encoding="utf-8",
            )
            self.assertEqual(
                release_identity.validate_changelog(changelog, "3.0.0"),
                "unreleased",
            )
            with self.assertRaisesRegex(
                release_identity.ReleaseIdentityError, "requires an ISO date"
            ):
                release_identity.validate_changelog(
                    changelog, "3.0.0", publication=True
                )
            changelog.write_text(
                "# Changelog\n\n## 3.0.0 (2026-08-02)\n\n### Added\n\n- Feature.\n",
                encoding="utf-8",
            )
            self.assertEqual(
                release_identity.validate_changelog(
                    changelog, "3.0.0", publication=True
                ),
                "2026-08-02",
            )

    def test_changelog_rejects_wrong_or_empty_latest_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            changelog = Path(temporary) / "CHANGELOG.md"
            for text, message in (
                ("# Changelog\n\n## 2.9.0\n\n### Fixed\n\n- Old.\n", "does not equal"),
                ("# Changelog\n\n## 3.0.0 (unreleased)\n", "section is empty"),
            ):
                changelog.write_text(text, encoding="utf-8")
                with self.subTest(message=message), self.assertRaisesRegex(
                    release_identity.ReleaseIdentityError, message
                ):
                    release_identity.validate_changelog(changelog, "3.0.0")


if __name__ == "__main__":
    unittest.main()
