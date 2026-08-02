from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import github_release  # noqa: E402


class GitHubReleaseContractTest(unittest.TestCase):
    def fixture(self, root: Path) -> tuple[Path, Path, str, str]:
        stage = root / "build" / "release"
        file = stage / "files" / "Quick Skin.jar"
        file.parent.mkdir(parents=True)
        file.write_bytes(b"verified-jar")
        sbom = stage / "sbom" / "quick-skin.cdx.json"
        sbom.parent.mkdir()
        sbom.write_bytes(b'{"bomFormat":"CycloneDX","specVersion":"1.6"}\n')
        commit = "a" * 40
        tag = "mc1.20.1-v3.0.0"
        manifest = stage / "artifacts.json"
        manifest.write_text(json.dumps({
            "schema_version": 2,
            "lane_count": 1,
            "git_commit": commit,
            "release": {"tag": tag},
            "artifacts": [{
                "artifact_node": "fabric-1.20.1",
                "filename": file.name,
                "path": "files/Quick Skin.jar",
                "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
            }],
            "sbom": {
                "format": "CycloneDX",
                "spec_version": "1.6",
                "filename": sbom.name,
                "path": "sbom/quick-skin.cdx.json",
                "bytes": sbom.stat().st_size,
                "sha256": hashlib.sha256(sbom.read_bytes()).hexdigest(),
            },
        }), encoding="utf-8")
        return stage, manifest, tag, commit

    def test_contract_binds_tag_commit_paths_and_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            contract = github_release.load_contract(manifest, stage, tag, commit)
            self.assertEqual(contract.tag, tag)
            self.assertEqual(len(contract.assets), 3)
            checksums = github_release.write_checksums(contract, stage)
            text = checksums.read_text(encoding="utf-8")
            self.assertIn("Quick Skin.jar", text)
            self.assertIn("quick-skin.cdx.json", text)
            self.assertIn("artifacts.json", text)

    def test_contract_rejects_changed_asset_or_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            (stage / "files" / "Quick Skin.jar").write_bytes(b"changed")
            with self.assertRaises(github_release.GitHubReleaseError):
                github_release.load_contract(manifest, stage, tag, commit)
            with self.assertRaises(github_release.GitHubReleaseError):
                github_release.load_contract(manifest, stage, "wrong-tag", commit)

    def test_contract_rejects_missing_or_changed_sbom(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            sbom = stage / "sbom" / "quick-skin.cdx.json"
            sbom.write_bytes(b"changed")
            with self.assertRaisesRegex(github_release.GitHubReleaseError, "SBOM release asset bytes"):
                github_release.load_contract(manifest, stage, tag, commit)

            data = json.loads(manifest.read_text(encoding="utf-8"))
            del data["sbom"]
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(github_release.GitHubReleaseError, "no CycloneDX SBOM"):
                github_release.load_contract(manifest, stage, tag, commit)


if __name__ == "__main__":
    unittest.main()
