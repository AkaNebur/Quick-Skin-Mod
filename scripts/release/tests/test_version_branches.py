from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import version_branches  # noqa: E402


class VersionBranchDiscoveryTest(unittest.TestCase):
    def test_parses_loader_pair_and_numeric_version_key(self) -> None:
        parsed = version_branches.parse_version_branch(
            "fabric-and-neoforge-1.21.1"
        )
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(parsed.loaders, ("fabric", "neoforge"))
        self.assertEqual(parsed.version, "1.21.1")
        self.assertEqual(parsed.version_key, (1, 21, 1))

    def test_recognizes_loader_and_exact_minecraft_suffix(self) -> None:
        self.assertTrue(
            version_branches.is_version_branch("forge-and-fabric-1.20.1")
        )
        self.assertTrue(
            version_branches.is_version_branch("fabric-and-neoforge-1.21.1")
        )
        self.assertTrue(version_branches.is_version_branch("fabric-26.2"))

    def test_rejects_shared_feature_and_automation_branches(self) -> None:
        for name in (
            "master",
            "all-in-one-migration",
            "feature/1.21.1",
            "automation/sync/fabric-1.21.1",
            "fabric-and-neoforge-latest",
        ):
            with self.subTest(name=name):
                self.assertFalse(version_branches.is_version_branch(name))

    def test_discovery_is_sorted_deduplicated_and_excludable(self) -> None:
        names = [
            "fabric-and-neoforge-1.21.1\n",
            "master\n",
            "forge-and-fabric-1.20.1\n",
            "fabric-and-neoforge-1.21.1\n",
        ]
        self.assertEqual(
            version_branches.discover_version_branches(
                names, exclude={"forge-and-fabric-1.20.1"}
            ),
            ["fabric-and-neoforge-1.21.1"],
        )


if __name__ == "__main__":
    unittest.main()
