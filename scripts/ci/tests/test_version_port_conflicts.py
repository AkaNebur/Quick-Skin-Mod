from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import version_port_conflicts  # noqa: E402


class VersionPortConflictsTest(unittest.TestCase):
    def write_matrix(self, root: Path, loaders: tuple[str, ...]) -> Path:
        matrix = root / "release-matrix.json"
        matrix.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "artifacts": [
                        {"artifact_node": f"{loader}-test", "loader": loader}
                        for loader in loaders
                    ],
                }
            ),
            encoding="utf-8",
        )
        return matrix

    def test_classifies_all_mechanical_policies_and_only_unprotected_ai_paths(
        self,
    ) -> None:
        result = version_port_conflicts.classify_conflicts(
            (
                "README.md",
                "e2e/packaged_runtime.py",
                "release/release-matrix.json",
                "forge/build.gradle.kts",
                "docs/ai/WORKFLOW.md",
                "common/src/main/java/com/quickskin/mod/Screen.java",
                "e2e/README.md",
            ),
            {"fabric", "neoforge"},
        )
        self.assertEqual(
            result.to_payload(),
            {
                "schema_version": 1,
                "source_paths": [
                    "docs/ai/WORKFLOW.md",
                    "e2e/README.md",
                    "e2e/packaged_runtime.py",
                ],
                "target_paths": ["release/release-matrix.json"],
                "delete_paths": ["forge/build.gradle.kts"],
                "ai_paths": [
                    "README.md",
                    "common/src/main/java/com/quickskin/mod/Screen.java",
                ],
            },
        )

    def test_rejects_build_conflicts_for_every_active_loader(self) -> None:
        for loader in sorted(version_port_conflicts.KNOWN_LOADERS):
            with self.subTest(loader=loader), self.assertRaisesRegex(
                version_port_conflicts.ConflictClassificationError,
                "active-loader",
            ):
                version_port_conflicts.classify_conflicts(
                    [f"{loader}/build.gradle.kts"], {loader}
                )

    def test_rejects_unknown_protected_conflicts(self) -> None:
        for path in (
            ".github/workflows/build-gate.yml",
            "docs/ai/PROJECT.md",
            "e2e/scenario-contract.json",
            "scripts/ci/ai_patch_policy.py",
            "quilt/build.gradle.kts",
            "build.gradle.kts",
        ):
            with self.subTest(path=path), self.assertRaisesRegex(
                version_port_conflicts.ConflictClassificationError,
                "unknown protected",
            ):
                version_port_conflicts.classify_conflicts([path], {"fabric"})

    def test_rejects_an_unbounded_ai_conflict_set(self) -> None:
        paths = [
            f"common/src/main/example/Conflict{index}.java"
            for index in range(25)
        ]
        with self.assertRaisesRegex(
            version_port_conflicts.ConflictClassificationError,
            "25 paths; limit is 24",
        ):
            version_port_conflicts.classify_conflicts(
                paths,
                {"fabric", "neoforge"},
            )

    def test_rejects_duplicate_case_colliding_and_unsafe_paths(self) -> None:
        cases = (
            (["README.md", "README.md"], "duplicate"),
            (["README.md", "readme.md"], "case-colliding"),
            (["../README.md"], "unsafe"),
            (["bad\\path"], "unsafe"),
            (["bad\rpath"], "unsafe"),
        )
        for paths, message in cases:
            with self.subTest(paths=paths), self.assertRaisesRegex(
                version_port_conflicts.ConflictClassificationError, message
            ):
                version_port_conflicts.classify_conflicts(paths, {"fabric"})

    def test_paths_file_is_strict_and_sorted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = root / "conflicts.txt"
            paths.write_text("z.txt\na.txt\n", encoding="utf-8")
            self.assertEqual(
                version_port_conflicts.read_conflict_paths(paths),
                ("a.txt", "z.txt"),
            )

            paths.write_text("README.md\nREADME.md\n", encoding="utf-8")
            with self.assertRaisesRegex(
                version_port_conflicts.ConflictClassificationError, "duplicate"
            ):
                version_port_conflicts.read_conflict_paths(paths)

            paths.write_text("README.md\n\n", encoding="utf-8")
            with self.assertRaisesRegex(
                version_port_conflicts.ConflictClassificationError, "unsafe"
            ):
                version_port_conflicts.read_conflict_paths(paths)

    def test_matrix_loader_reader_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(
                version_port_conflicts.read_active_loaders(
                    self.write_matrix(root, ("fabric", "neoforge"))
                ),
                frozenset({"fabric", "neoforge"}),
            )

            invalid_cases = (
                '{"schema_version":2,"artifacts":[]}',
                '{"schema_version":1,"artifacts":[{"loader":"fabric"}]}',
                '{"schema_version":2,"artifacts":[{"loader":"quilt"}]}',
                '{"schema_version":2,"schema_version":2,"artifacts":[{"loader":"fabric"}]}',
                '{"schema_version":2,"artifacts":[{"loader":"fabric"}],"number":NaN}',
            )
            matrix = root / "release-matrix.json"
            for payload in invalid_cases:
                with self.subTest(payload=payload):
                    matrix.write_text(payload, encoding="utf-8")
                    with self.assertRaises(
                        version_port_conflicts.ConflictClassificationError
                    ):
                        version_port_conflicts.read_active_loaders(matrix)

    def test_cli_emits_exact_compact_deterministic_schema(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = root / "conflicts.txt"
            paths.write_text(
                "README.md\nforge/build.gradle.kts\ne2e/README.md\n",
                encoding="utf-8",
            )
            matrix = self.write_matrix(root, ("fabric", "neoforge"))
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                result = version_port_conflicts.main(
                    ["--paths-file", str(paths), "--matrix", str(matrix)]
                )
            self.assertEqual(result, 0)
            self.assertEqual(
                stdout.getvalue(),
                '{"schema_version":1,"source_paths":["e2e/README.md"],'
                '"target_paths":[],"delete_paths":["forge/build.gradle.kts"],'
                '"ai_paths":["README.md"]}\n',
            )


if __name__ == "__main__":
    unittest.main()
