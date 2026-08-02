from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import reconcile_publication as reconciliation  # noqa: E402


class PublicationReconciliationTest(unittest.TestCase):
    def expected(self) -> reconciliation.ExpectedArtifact:
        return reconciliation.ExpectedArtifact(
            node="fabric-1.20.1",
            filename="Quick Skin - Fabric - 1.20.1-3.0.0.jar",
            path=Path("unused.jar"),
            bytes=123,
            sha1="1" * 40,
            sha256="2" * 64,
            sha512="3" * 128,
        )

    def test_modrinth_skips_only_an_exact_hash_and_identity_match(self) -> None:
        expected = self.expected()
        remote = {
            "id": "remote01",
            "project_id": "project1",
            "version_number": "mc1.20.1-v3.0.0-fabric-1.20.1",
            "files": [{
                "filename": expected.filename,
                "size": expected.bytes,
                "hashes": {"sha512": expected.sha512},
            }],
        }
        result = reconciliation.classify_modrinth(
            remote,
            [{"id": "remote01", "version_number": remote["version_number"]}],
            expected,
            "project1",
            remote["version_number"],
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "remote01")

    def test_modrinth_fails_closed_on_version_or_hash_collision(self) -> None:
        expected = self.expected()
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_modrinth(
                None,
                [{"id": "other", "version_number": "release-id"}],
                expected,
                "project1",
                "release-id",
            )
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_modrinth(
                {
                    "id": "other",
                    "project_id": "another-project",
                    "version_number": "release-id",
                    "files": [],
                },
                [],
                expected,
                "project1",
                "release-id",
            )

    def test_curseforge_reconciles_by_filename_sha1_and_size(self) -> None:
        expected = self.expected()
        exact = {
            "id": 42,
            "fileName": expected.filename,
            "fileLength": expected.bytes,
            "hashes": [{"algo": 1, "value": expected.sha1}],
        }
        result = reconciliation.classify_curseforge([exact], expected)
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "42")

        different = dict(exact)
        different["hashes"] = [{"algo": 1, "value": "f" * 40}]
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_curseforge([different], expected)

    def test_missing_marketplace_file_is_publishable(self) -> None:
        expected = self.expected()
        self.assertTrue(
            reconciliation.classify_modrinth(
                None, [], expected, "project1", "release-id"
            ).publish
        )
        self.assertTrue(reconciliation.classify_curseforge([], expected).publish)


if __name__ == "__main__":
    unittest.main()
