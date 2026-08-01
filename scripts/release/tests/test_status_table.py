from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import status_table  # noqa: E402


def matrix_for(version: str, loaders: tuple[str, ...], java: int) -> dict[str, object]:
    return {
        "artifacts": [
            {
                "artifact_version": version,
                "loader": loader,
                "java": java,
            }
            for loader in loaders
        ]
    }


class ReleaseStatusTableTest(unittest.TestCase):
    def test_renders_newest_first_with_branch_specific_badges(self) -> None:
        section = status_table.render_status_section(
            {
                "forge-and-fabric-1.20.1": matrix_for(
                    "1.20.1", ("fabric", "forge"), 17
                ),
                "fabric-and-neoforge-1.21.1": matrix_for(
                    "1.21.1", ("fabric", "neoforge"), 21
                ),
            },
            repository="AkaNebur/Quick-Skin-Mod",
        )

        self.assertLess(section.index("| 1.21.1 |"), section.index("| 1.20.1 |"))
        self.assertIn("Fabric + NeoForge", section)
        self.assertIn("build-gate.yml/badge.svg?branch=fabric-and-neoforge-1.21.1", section)
        self.assertIn("on-demand-e2e.yml/badge.svg?branch=forge-and-fabric-1.20.1", section)
        self.assertIn("?query=branch%3Afabric-and-neoforge-1.21.1", section)

    def test_rejects_branch_and_matrix_disagreement(self) -> None:
        with self.assertRaisesRegex(status_table.StatusTableError, "loaders"):
            status_table.release_metadata(
                "forge-and-fabric-1.20.1",
                matrix_for("1.20.1", ("fabric", "neoforge"), 17),
            )

    def test_rejects_two_branches_for_one_minecraft_version(self) -> None:
        with self.assertRaisesRegex(status_table.StatusTableError, "multiple"):
            status_table.render_status_section(
                {
                    "fabric-1.20.1": matrix_for("1.20.1", ("fabric",), 17),
                    "forge-1.20.1": matrix_for("1.20.1", ("forge",), 17),
                },
                repository="AkaNebur/Quick-Skin-Mod",
            )

    def test_replaces_only_the_marked_readme_section(self) -> None:
        original = (
            "before\n"
            f"{status_table.START_MARKER}\nold\n{status_table.END_MARKER}\n"
            "after\n"
        )
        section = (
            f"{status_table.START_MARKER}\nnew\n{status_table.END_MARKER}"
        )
        self.assertEqual(
            status_table.replace_status_section(original, section),
            f"before\n{section}\nafter\n",
        )

    def test_requires_one_marker_pair(self) -> None:
        with self.assertRaisesRegex(status_table.StatusTableError, "exactly one"):
            status_table.replace_status_section("no markers\n", "unused")

    def test_rejects_reversed_markers(self) -> None:
        malformed = f"{status_table.END_MARKER}\n{status_table.START_MARKER}\n"
        with self.assertRaisesRegex(status_table.StatusTableError, "out of order"):
            status_table.replace_status_section(malformed, "unused")


if __name__ == "__main__":
    unittest.main()
