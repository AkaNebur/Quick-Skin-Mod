from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import matrix as release_matrix  # noqa: E402


class ReleaseMatrixMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base = json.loads(
            (ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8")
        )

    def mutated(self) -> dict:
        return copy.deepcopy(self.base)

    def assert_invalid(self, data: dict, message: str) -> None:
        with self.assertRaisesRegex(release_matrix.MatrixError, message):
            release_matrix.validate_matrix(data)

    def make_source_fixture(self, root: Path) -> Path:
        matrix_path = root / "release" / "release-matrix.json"
        matrix_path.parent.mkdir(parents=True)
        for module, routes in self.base["source_overlays"].items():
            source_root = root / module / "src"
            (source_root / "main" / "java").mkdir(parents=True)
            for overlay in routes.values():
                marker = source_root / overlay / "resources" / "fixture.marker"
                marker.parent.mkdir(parents=True)
                marker.write_text("live\n", encoding="utf-8")
        return matrix_path

    def test_checked_in_matrix_is_valid(self) -> None:
        release_matrix.validate_matrix(self.mutated())

    def test_version_can_explicitly_select_no_remap(self) -> None:
        data = self.mutated()
        for artifact in data["artifacts"]:
            if artifact["artifact_version"] != "1.20.1":
                continue
            artifact["no_remap"] = True
            prefix = f":{artifact['loader']}:1.20.1:"
            artifact["gradle_task"] = prefix + "shadowJar"
            artifact["harness_task"] = prefix + "e2eHarnessJar"
        release_matrix.validate_matrix(data)

    def test_new_java_major_is_data_not_an_allowlist_change(self) -> None:
        data = self.mutated()
        for artifact in data["artifacts"]:
            if artifact["artifact_version"] == "1.20.1":
                artifact["java"] = 26
        for runtime in data["runtimes"]:
            if runtime["runtime_version"] == "1.20.1":
                runtime["java"] = 26
        release_matrix.validate_matrix(data)

    def test_paired_lanes_must_agree_on_no_remap(self) -> None:
        data = self.mutated()
        artifact = next(row for row in data["artifacts"] if row["artifact_node"] == "fabric-1.20.1")
        artifact["no_remap"] = True
        artifact["gradle_task"] = ":fabric:1.20.1:shadowJar"
        artifact["harness_task"] = ":fabric:1.20.1:e2eHarnessJar"
        self.assert_invalid(data, "disagree on Java/no_remap")

    def test_task_names_follow_explicit_no_remap(self) -> None:
        data = self.mutated()
        data["artifacts"][0]["gradle_task"] = ":fabric:1.20.1:shadowJar"
        self.assert_invalid(data, "Gradle task must be")

    def test_overlay_routes_cannot_name_unknown_versions(self) -> None:
        data = self.mutated()
        data["source_overlays"]["common"]["99.9"] = "legacy99_9"
        self.assert_invalid(data, "names unsupported versions.*99.9")

    def test_overlay_routes_require_legacy_roots(self) -> None:
        data = self.mutated()
        data["source_overlays"]["common"]["1.20.1"] = "v1_20_1"
        self.assert_invalid(data, "must name legacy")

        traversal = self.mutated()
        traversal["source_overlays"]["common"]["1.20.1"] = "legacy../../escape"
        self.assert_invalid(traversal, "must name legacy")

    def test_source_root_validation_rejects_orphans_and_retired_snapshots(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix_path = self.make_source_fixture(root)
            orphan = root / "fabric" / "src" / "legacy_orphan" / "java" / "Orphan.java"
            orphan.parent.mkdir(parents=True)
            orphan.write_text("final class Orphan {}\n", encoding="utf-8")
            with self.assertRaisesRegex(release_matrix.MatrixError, "overlay roots disagree"):
                release_matrix.validate_source_roots(matrix_path, self.base)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix_path = self.make_source_fixture(root)
            oracle = root / "forge" / "src" / "v99" / "java" / "Oracle.java"
            oracle.parent.mkdir(parents=True)
            oracle.write_text("final class Oracle {}\n", encoding="utf-8")
            with self.assertRaisesRegex(release_matrix.MatrixError, "version snapshots remain"):
                release_matrix.validate_source_roots(matrix_path, self.base)

    def test_source_root_validation_caps_live_class_copies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = self.mutated()
            data["source_overlays"]["common"]["fixture"] = "legacy_fixture"
            matrix_path = root / "release" / "release-matrix.json"
            matrix_path.parent.mkdir(parents=True)
            for module, routes in data["source_overlays"].items():
                source_root = root / module / "src"
                (source_root / "main" / "java").mkdir(parents=True)
                for overlay in routes.values():
                    marker = source_root / overlay / "resources" / "fixture.marker"
                    marker.parent.mkdir(parents=True)
                    marker.write_text("live\n", encoding="utf-8")
            relative = Path("com/quickskin/mod/Duplicated.java")
            roots = [
                root / "common" / "src" / "main" / "java",
                root / "common" / "src" / "legacy1_20_1" / "java",
                root / "common" / "src" / "legacy_fixture" / "java",
            ]
            for java_root in roots:
                source = java_root / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("final class Duplicated {}\n", encoding="utf-8")
            with self.assertRaisesRegex(release_matrix.MatrixError, "two-copy overlay limit"):
                release_matrix.validate_source_roots(matrix_path, data)

    def test_no_remap_is_required_and_boolean(self) -> None:
        missing = self.mutated()
        del missing["artifacts"][0]["no_remap"]
        self.assert_invalid(missing, "missing.*no_remap")

        wrong_type = self.mutated()
        wrong_type["artifacts"][0]["no_remap"] = "false"
        self.assert_invalid(wrong_type, "no_remap must be a boolean")

    def test_metadata_range_is_explicit_and_loader_shaped(self) -> None:
        missing = self.mutated()
        del missing["artifacts"][0]["metadata_range"]
        self.assert_invalid(missing, "missing.*metadata_range")

        fabric_broad = self.mutated()
        fabric_broad["artifacts"][0]["metadata_range"] = ">=1.20.1"
        self.assert_invalid(fabric_broad, "metadata_range must be =1.20.1")

        forge_wrong_lower = self.mutated()
        row = next(
            artifact for artifact in forge_wrong_lower["artifacts"]
            if artifact["artifact_node"] == "forge-1.20.1"
        )
        row["metadata_range"] = "[1.20,1.20.2)"
        self.assert_invalid(forge_wrong_lower, "must start at its exact version")

        forge_broad = self.mutated()
        row = next(
            artifact for artifact in forge_broad["artifacts"]
            if artifact["artifact_node"] == "forge-1.20.1"
        )
        row["metadata_range"] = "[1.20.1,1.21)"
        self.assert_invalid(forge_broad, "immediate patch successor 1.20.2")

        duplicated = self.mutated()
        duplicated["artifacts"][0]["metadata"]["minecraft"] = "=1.20.1"
        self.assert_invalid(duplicated, "only in metadata_range")

    def test_fml_pack_formats_are_explicit_positive_integers(self) -> None:
        missing = self.mutated()
        forge = next(
            artifact for artifact in missing["artifacts"]
            if artifact["artifact_node"] == "forge-1.20.1"
        )
        del forge["metadata"]["pack_format"]
        self.assert_invalid(missing, "metadata.pack_format must be a positive integer")

        wrong_type = self.mutated()
        forge = next(
            artifact for artifact in wrong_type["artifacts"]
            if artifact["artifact_node"] == "forge-1.20.1"
        )
        forge["metadata"]["server_data_pack_format"] = "15"
        self.assert_invalid(
            wrong_type,
            "metadata.server_data_pack_format must be a positive integer",
        )

        fabric = self.mutated()
        fabric["artifacts"][0]["metadata"]["pack_format"] = 15
        self.assert_invalid(fabric, "must not declare FML pack formats")

    def test_orphan_versioned_gradle_property_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            release_dir = root / "release"
            release_dir.mkdir()
            matrix_path = release_dir / "release-matrix.json"
            matrix_path.write_text(json.dumps(self.base), encoding="utf-8")
            properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
            (root / "gradle.properties").write_text(
                properties + "\nminecraft_version_99_9=99.9\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                release_matrix.MatrixError, "belongs to no supported Minecraft version"
            ):
                release_matrix.validate_build_properties(matrix_path, self.base)


if __name__ == "__main__":
    unittest.main()
