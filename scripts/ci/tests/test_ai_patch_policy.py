from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import ai_patch_policy  # noqa: E402


class AiPatchPolicyTest(unittest.TestCase):
    def test_repair_accepts_narrow_production_source_changes(self) -> None:
        self.assertEqual(
            ai_patch_policy.validate_paths(
                ["common/src/main/java/com/quickskin/mod/Example.java"], "repair"
            ),
            ("common/src/main/java/com/quickskin/mod/Example.java",),
        )

    def test_repair_rejects_ci_tests_matrix_and_build_configuration(self) -> None:
        protected = (
            ".github/workflows/build-gate.yml",
            "common/src/test/java/ExampleTest.java",
            "fabric/src/e2e/java/ExampleHarness.java",
            "release/release-matrix.json",
            "scripts/ci/ai_patch_policy.py",
            "build.gradle.kts",
            "common/src/main/java/com/quickskin/mod/networking/TextureTransferLimits.java",
            "common/src/main/resources/quickskin.mixins.json",
        )
        for path in protected:
            with self.subTest(path=path), self.assertRaisesRegex(
                ai_patch_policy.PolicyError, "protected"
            ):
                ai_patch_policy.validate_paths([path], "repair")

    def test_conflict_resolution_is_limited_to_original_conflicts(self) -> None:
        allowed = {"common/src/main/java/com/quickskin/mod/Allowed.java"}
        with self.assertRaisesRegex(ai_patch_policy.PolicyError, "outside"):
            ai_patch_policy.validate_paths(
                ["common/src/main/java/com/quickskin/mod/Surprise.java"],
                "conflict",
                allowed,
            )

    def test_rejects_traversal_and_control_characters(self) -> None:
        for path in ("../escape", "/absolute", "bad\\path", "line\nbreak"):
            with self.subTest(path=path), self.assertRaisesRegex(
                ai_patch_policy.PolicyError, "unsafe"
            ):
                ai_patch_policy.validate_paths([path], "port")

    def test_numstat_parser_handles_edits_and_renames(self) -> None:
        paths, binary = ai_patch_policy.parse_numstat(
            b"3\t2\tcommon/src/main/A.java\0"
            b"0\t0\t\0common/src/main/Old.java\0common/src/main/New.java\0"
        )
        self.assertEqual(
            paths,
            (
                "common/src/main/A.java",
                "common/src/main/Old.java",
                "common/src/main/New.java",
            ),
        )
        self.assertFalse(binary)

    def test_numstat_parser_marks_binary_patches(self) -> None:
        paths, binary = ai_patch_policy.parse_numstat(b"-\t-\tasset.png\0")
        self.assertEqual(paths, ("asset.png",))
        self.assertTrue(binary)

    def test_conflict_content_rejects_markers_and_binary_data(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            previous = Path.cwd()
            try:
                import os

                os.chdir(root)
                Path("resolved.java").write_text("<<<<<<< ours\n", encoding="utf-8")
                with self.assertRaisesRegex(ai_patch_policy.PolicyError, "marker"):
                    ai_patch_policy.validate_conflict_contents(["resolved.java"])
                Path("resolved.java").write_bytes(b"class Example {}\0")
                with self.assertRaisesRegex(ai_patch_policy.PolicyError, "text-only"):
                    ai_patch_policy.validate_conflict_contents(["resolved.java"])
            finally:
                os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
