from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from orchestrator import manifest_hash, read_manifest  # noqa: E402


class E2EManifestContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.matrix = self.root / "release-matrix.json"
        self.commit = "a" * 40
        self.data: dict[str, object] = {
            "lane_count": 2,
            "project": {"release_branch": "fabric-and-forge-1.20.1"},
            "artifacts": [
                {
                    "artifact_node": "fabric-1.20.1",
                    "artifact_version": "1.20.1",
                    "loader": "fabric",
                    "jar": "fabric/Quick Skin - Fabric - 1.20.1-{mod_version}.jar",
                    "harness_jar": "fabric/Quick Skin E2E - Fabric - 1.20.1-0.0.0.jar",
                    "game_versions": ["1.20.1"],
                },
                {
                    "artifact_node": "forge-1.20.1",
                    "artifact_version": "1.20.1",
                    "loader": "forge",
                    "jar": "forge/Quick Skin - Forge - 1.20.1-{mod_version}.jar",
                    "harness_jar": "forge/Quick Skin E2E - Forge - 1.20.1-0.0.0.jar",
                    "game_versions": ["1.20.1"],
                },
            ],
        }
        self.production_payloads = {
            "fabric-1.20.1": b"fabric-production",
            "forge-1.20.1": b"forge-production",
        }
        self.harness_payloads = {
            "fabric-1.20.1": b"fabric-harness",
            "forge-1.20.1": b"forge-harness",
        }
        self.sbom = self.root / "sbom" / "quick-skin.cdx.json"
        self.sbom.parent.mkdir(parents=True)
        self.sbom.write_bytes(b'{"bomFormat":"CycloneDX"}\n')

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def digest(payload: bytes, algorithm: str) -> str:
        return hashlib.new(algorithm, payload).hexdigest()

    def expected_release(self, data: dict[str, object]) -> dict[str, object]:
        artifacts = data["artifacts"]
        assert isinstance(artifacts, list)
        versions = sorted({str(artifact["artifact_version"]) for artifact in artifacts})
        return {
            "release_id": "mc1.20.1-v3.0.0",
            "tag": "mc1.20.1-v3.0.0",
            "branch": "fabric-and-forge-1.20.1",
            "mod_version": "3.0.0",
            "minecraft_versions": versions,
        }

    def artifact_record(self, artifact: dict[str, object]) -> dict[str, object]:
        node = str(artifact["artifact_node"])
        loader = str(artifact["loader"])
        production_name = f"Quick Skin - {loader.title()} - 1.20.1-3.0.0.jar"
        harness_name = f"Quick Skin E2E - {loader.title()} - 1.20.1-0.0.0.jar"
        production_payload = self.production_payloads[node]
        harness_payload = self.harness_payloads[node]
        production = self.root / "files" / production_name
        harness = self.root / "harness" / harness_name
        production.parent.mkdir(parents=True, exist_ok=True)
        harness.parent.mkdir(parents=True, exist_ok=True)
        production.write_bytes(production_payload)
        harness.write_bytes(harness_payload)
        return {
            "artifact_node": node,
            "artifact_version": "1.20.1",
            "loader": loader,
            "game_versions": ["1.20.1"],
            "filename": production_name,
            "path": f"files/{production_name}",
            "bytes": len(production_payload),
            "sha1": self.digest(production_payload, "sha1"),
            "sha256": self.digest(production_payload, "sha256"),
            "sha512": self.digest(production_payload, "sha512"),
            "harness": {
                "filename": harness_name,
                "path": f"harness/{harness_name}",
                "bytes": len(harness_payload),
                "sha256": self.digest(harness_payload, "sha256"),
            },
        }

    def write_manifest(
        self,
        *,
        data: dict[str, object] | None = None,
        **overrides: object,
    ) -> Path:
        selected = self.data if data is None else data
        self.matrix.write_text(json.dumps(selected), encoding="utf-8")
        artifacts = selected["artifacts"]
        assert isinstance(artifacts, list)
        manifest: dict[str, object] = {
            "schema_version": 2,
            "matrix": "release-matrix.json",
            "matrix_sha256": hashlib.sha256(self.matrix.read_bytes()).hexdigest(),
            "lane_count": selected["lane_count"],
            "mod_version": "3.0.0",
            "git_commit": self.commit,
            "release": self.expected_release(selected),
            "artifacts": [self.artifact_record(artifact) for artifact in artifacts],
            "sbom": {
                "format": "CycloneDX",
                "spec_version": "1.6",
                "filename": self.sbom.name,
                "path": "sbom/quick-skin.cdx.json",
                "bytes": self.sbom.stat().st_size,
                "sha256": hashlib.sha256(self.sbom.read_bytes()).hexdigest(),
            },
        }
        manifest.update(overrides)
        path = self.root / "artifacts.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def read(self, path: Path, data: dict[str, object] | None = None) -> dict[str, object]:
        selected = self.data if data is None else data
        return read_manifest(
            path,
            selected,
            self.matrix,
            self.root,
            "3.0.0",
            self.commit,
            self.expected_release(selected),
        )

    def test_accepts_complete_schema_two_and_resolves_exact_hash(self) -> None:
        manifest = self.read(self.write_manifest())

        expected = self.digest(self.production_payloads["fabric-1.20.1"], "sha256")
        self.assertEqual(expected, manifest_hash(manifest, "fabric-1.20.1"))

    def test_rejects_legacy_schema_matrix_drift_and_duplicate_nodes(self) -> None:
        valid = json.loads(self.write_manifest().read_text(encoding="utf-8"))
        duplicate = copy.deepcopy(valid["artifacts"])
        duplicate[1] = copy.deepcopy(duplicate[0])
        cases = (
            {"schema_version": 1},
            {"matrix_sha256": "0" * 64},
            {"artifacts": duplicate},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                self.read(self.write_manifest(**overrides))

    def test_rejects_truncated_traversal_and_unhashable_node_records(self) -> None:
        valid = json.loads(self.write_manifest().read_text(encoding="utf-8"))
        truncated = [{"artifact_node": "fabric-1.20.1", "sha256": "a" * 64}, valid["artifacts"][1]]
        traversal = copy.deepcopy(valid["artifacts"])
        traversal[0]["path"] = "../../outside.jar"
        unhashable = copy.deepcopy(valid["artifacts"])
        unhashable[0]["artifact_node"] = ["fabric-1.20.1"]
        for artifacts in (truncated, traversal, unhashable):
            with self.subTest(artifacts=artifacts), self.assertRaises(ValueError):
                self.read(self.write_manifest(artifacts=artifacts))

    def test_rejects_boolean_lane_count_even_for_a_one_lane_matrix(self) -> None:
        one_lane = copy.deepcopy(self.data)
        one_lane["lane_count"] = 1
        one_lane["artifacts"] = copy.deepcopy(self.data["artifacts"][:1])
        with self.assertRaises(ValueError):
            self.read(self.write_manifest(data=one_lane, lane_count=True), one_lane)

    def test_rejects_changed_production_or_harness_bytes(self) -> None:
        path = self.write_manifest()
        production = self.root / "files" / "Quick Skin - Fabric - 1.20.1-3.0.0.jar"
        production.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.read(path)

        path = self.write_manifest()
        harness = self.root / "harness" / "Quick Skin E2E - Fabric - 1.20.1-0.0.0.jar"
        harness.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.read(path)

    def test_manifest_must_live_directly_in_its_stage(self) -> None:
        path = self.write_manifest()
        nested = self.root / "metadata" / "artifacts.json"
        nested.parent.mkdir()
        nested.write_bytes(path.read_bytes())
        with self.assertRaises(ValueError):
            read_manifest(
                nested,
                self.data,
                self.matrix,
                self.root,
                "3.0.0",
                self.commit,
                self.expected_release(self.data),
            )

    def test_manifest_hash_rejects_ambiguous_lookup(self) -> None:
        with self.assertRaises(ValueError):
            manifest_hash(
                {
                    "artifacts": [
                        {"artifact_node": "fabric-1.20.1", "sha256": "a" * 64},
                        {"artifact_node": "fabric-1.20.1", "sha256": "b" * 64},
                    ]
                },
                "fabric-1.20.1",
            )


if __name__ == "__main__":
    unittest.main()
