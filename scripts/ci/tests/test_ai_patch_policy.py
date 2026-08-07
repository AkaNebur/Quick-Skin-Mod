from __future__ import annotations

import sys
import tempfile
import unittest
import unicodedata
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
            ".claude/settings.json",
            ".codex/config.toml",
            ".mcp.json",
            "CLAUDE.local.md",
        )
        for path in protected:
            with self.subTest(path=path), self.assertRaisesRegex(
                ai_patch_policy.PolicyError, "protected"
            ):
                ai_patch_policy.validate_paths([path], "repair")

    def test_repair_is_positive_allowlisted_to_production_src_main(self) -> None:
        for path in (
            "README.md",
            "package.json",
            "common/README.md",
            "fabric/src/generated/Backdoor.java",
            "neoforge/build.gradle.kts",
        ):
            with self.subTest(path=path), self.assertRaises(ai_patch_policy.PolicyError):
                ai_patch_policy.validate_paths([path], "repair")

        self.assertEqual(
            ai_patch_policy.validate_paths(
                ["neoforge/src/main/java/com/quickskin/mod/Fix.java"], "repair"
            ),
            ("neoforge/src/main/java/com/quickskin/mod/Fix.java",),
        )

    def test_conflict_resolution_is_limited_to_original_conflicts(self) -> None:
        allowed = {"common/src/main/java/com/quickskin/mod/Allowed.java"}
        with self.assertRaisesRegex(ai_patch_policy.PolicyError, "outside"):
            ai_patch_policy.validate_paths(
                ["common/src/main/java/com/quickskin/mod/Surprise.java"],
                "conflict",
                allowed,
            )

    def test_conflict_rejects_protected_paths_even_when_originally_conflicted(self) -> None:
        path = "e2e/README.md"
        with self.assertRaisesRegex(ai_patch_policy.PolicyError, "protected"):
            ai_patch_policy.validate_paths([path], "conflict", {path})

    def test_e2e_readme_stays_protected_outside_conflict_resolution(self) -> None:
        with self.assertRaisesRegex(ai_patch_policy.PolicyError, "protected"):
            ai_patch_policy.validate_paths(["e2e/README.md"], "repair")

    def test_conflict_protection_covers_the_complete_e2e_boundary(self) -> None:
        protected = (
            "e2e/README.md",
            "e2e/packaged_runtime.py",
            "e2e/scenario-contract.json",
            "e2e/README.md/appendix",
            "e2e/readme.md",
        )
        for path in protected:
            with self.subTest(path=path), self.assertRaisesRegex(
                ai_patch_policy.PolicyError, "protected"
            ):
                ai_patch_policy.validate_paths([path], "conflict", {path})

    def test_rejects_traversal_and_control_characters(self) -> None:
        for path in (
            "../escape",
            "/absolute",
            "bad\\path",
            "line\nbreak",
            "delete\x7fkey",
            "bad:name",
            "e2e//README.md",
            "e2e/./README.md",
            "e2e/README.md/",
        ):
            with self.subTest(path=path), self.assertRaisesRegex(
                ai_patch_policy.PolicyError, "unsafe"
            ):
                ai_patch_policy.validate_paths([path], "port")

        decomposed = unicodedata.normalize("NFD", "café.java")
        with self.assertRaisesRegex(ai_patch_policy.PolicyError, "unsafe"):
            ai_patch_policy.validate_paths([decomposed], "port")
        with self.assertRaisesRegex(ai_patch_policy.PolicyError, "case-colliding"):
            ai_patch_policy.validate_paths(["A.java", "a.java"], "port")

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

    def test_conflict_resolution_must_remain_a_regular_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            previous = Path.cwd()
            try:
                import os

                os.chdir(root)
                readme = Path("resolved.md")
                ai_patch_policy.validate_conflict_contents([readme.as_posix()])
                readme.mkdir()
                with self.assertRaisesRegex(ai_patch_policy.PolicyError, "regular"):
                    ai_patch_policy.validate_conflict_contents([readme.as_posix()])
                readme.rmdir()

                target = Path("target.md")
                target.write_text("outside\n", encoding="utf-8")
                readme.symlink_to(target.resolve())
                with self.assertRaisesRegex(ai_patch_policy.PolicyError, "regular"):
                    ai_patch_policy.validate_conflict_contents([readme.as_posix()])

                readme.unlink()
                readme.write_text("resolved documentation\n", encoding="utf-8")
                ai_patch_policy.validate_conflict_contents([readme.as_posix()])

                oversized = Path("oversized.md")
                oversized.write_bytes(
                    b"x" * (ai_patch_policy.MAX_PATCH_BYTES["conflict"] + 1)
                )
                with self.assertRaisesRegex(
                    ai_patch_policy.PolicyError, "aggregate"
                ):
                    ai_patch_policy.validate_conflict_contents(
                        [readme.as_posix(), oversized.as_posix()]
                    )
            finally:
                os.chdir(previous)

    def test_patch_output_is_exclusive_and_never_follows_a_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "repair.patch"
            ai_patch_policy.write_exclusive(output, b"patch")
            self.assertEqual(output.read_bytes(), b"patch")
            with self.assertRaisesRegex(ai_patch_policy.PolicyError, "cannot create"):
                ai_patch_policy.write_exclusive(output, b"replacement")

            target = root / "target"
            target.write_bytes(b"preserve")
            linked = root / "linked.patch"
            linked.symlink_to(target)
            with self.assertRaisesRegex(ai_patch_policy.PolicyError, "cannot create"):
                ai_patch_policy.write_exclusive(linked, b"overwrite")
            self.assertEqual(target.read_bytes(), b"preserve")

    def test_staged_policy_rejects_a_symbolic_link_in_production_source(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            previous = Path.cwd()
            try:
                import os
                import subprocess

                os.chdir(root)
                subprocess.run(("git", "init", "--quiet"), check=True)
                subprocess.run(
                    ("git", "config", "user.name", "Policy test"), check=True
                )
                subprocess.run(
                    ("git", "config", "user.email", "policy@example.invalid"),
                    check=True,
                )
                source = Path("common/src/main/java/com/quickskin/mod/Fix.java")
                source.parent.mkdir(parents=True)
                source.write_text("class Fix {}\n", encoding="utf-8")
                subprocess.run(("git", "add", "."), check=True)
                subprocess.run(("git", "commit", "--quiet", "-m", "base"), check=True)
                source.unlink()
                try:
                    source.symlink_to("/etc/passwd")
                except (OSError, NotImplementedError):
                    self.skipTest("symbolic links are unavailable")
                subprocess.run(("git", "add", "-A"), check=True)

                with self.assertRaisesRegex(
                    ai_patch_policy.PolicyError, "non-regular Git entry"
                ):
                    ai_patch_policy.validate_staged("repair", None, None)
            finally:
                os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
