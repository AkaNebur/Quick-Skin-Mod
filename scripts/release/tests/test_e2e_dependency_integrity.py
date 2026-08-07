from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from dependency_integrity import DependencyIntegrityError, verified_sha256  # noqa: E402


class E2EDependencyIntegrityTest(unittest.TestCase):
    def test_checked_in_runtime_dependencies_have_exact_gradle_hashes(self) -> None:
        metadata = ROOT / "gradle" / "verification-metadata.xml"
        self.assertEqual(
            verified_sha256(
                metadata,
                group="net.fabricmc.fabric-api",
                name="fabric-api",
                version="0.92.6+1.20.1",
                artifact="fabric-api-0.92.6+1.20.1.jar",
            ),
            "0ece50476da3692111ab04b75945c5458e70d98cd069eefc044ab3e57977deeb",
        )
        self.assertEqual(
            verified_sha256(
                metadata,
                group="dev.architectury",
                name="architectury-forge",
                version="9.2.14",
                artifact="architectury-forge-9.2.14.jar",
            ),
            "47d5eca3d83aae1ac1d4a70116727715bd7ef4c077d228fee873065cbca94687",
        )

    def test_missing_duplicate_or_non_sha256_authority_fails_closed(self) -> None:
        template = """<?xml version='1.0' encoding='UTF-8'?>
<verification-metadata xmlns='https://schema.gradle.org/dependency-verification'>
  <components>
    <component group='g' name='n' version='1'>
      <artifact name='n-1.jar'>{hashes}</artifact>
    </component>
  </components>
</verification-metadata>
"""
        for hashes in (
            "",
            "<sha256 value='nope'/>",
            "<sha256 value='" + "a" * 64 + "'/><sha256 value='" + "b" * 64 + "'/>",
        ):
            with self.subTest(hashes=hashes), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "verification.xml"
                path.write_text(template.format(hashes=hashes), encoding="utf-8")
                with self.assertRaises(DependencyIntegrityError):
                    verified_sha256(
                        path, group="g", name="n", version="1", artifact="n-1.jar"
                    )

    def test_artifact_identity_cannot_escape(self) -> None:
        with self.assertRaises(DependencyIntegrityError):
            verified_sha256(
                ROOT / "gradle" / "verification-metadata.xml",
                group="dev.architectury",
                name="architectury-forge",
                version="9.2.14",
                artifact="../architectury-forge-9.2.14.jar",
            )


if __name__ == "__main__":
    unittest.main()
