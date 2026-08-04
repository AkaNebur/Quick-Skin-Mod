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

    def artifact(self, data: dict, loader: str) -> dict:
        return next(row for row in data["artifacts"] if row["loader"] == loader)

    def fml_artifact(self, data: dict) -> dict:
        return next(row for row in data["artifacts"] if row["loader"] != "fabric")

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

    def neoforge_compatibility_matrix(self) -> dict:
        data = self.mutated()
        version = data["unit_test_version"]
        artifact = self.fml_artifact(data)
        old_loader = artifact["loader"]
        old_node = artifact["artifact_node"]
        runtime = next(
            row for row in data["runtimes"] if row["artifact_node"] == old_node
        )
        old_installer = runtime["installer"]

        node = f"neoforge-{version}"
        artifact["artifact_node"] = node
        artifact["loader"] = "neoforge"
        production_task = "shadowJar" if artifact["no_remap"] else "remapJar"
        harness_task = (
            "e2eHarnessJar" if artifact["no_remap"] else "remapE2EHarnessJar"
        )
        artifact["gradle_task"] = f":neoforge:{version}:{production_task}"
        artifact["harness_task"] = f":neoforge:{version}:{harness_task}"
        artifact["jar"] = (
            f"neoforge/versions/{version}/build/libs/"
            f"Quick Skin - NeoForge - {version}-{{mod_version}}.jar"
        )
        artifact["harness_jar"] = (
            f"neoforge/versions/{version}/build/libs/"
            f"Quick Skin E2E - NeoForge - {version}-0.0.0.jar"
        )
        artifact["metadata"]["file"] = "META-INF/neoforge.mods.toml"
        artifact["metadata"]["architectury"] = "[20.0.4]"

        runtime["artifact_node"] = node
        runtime["loader"] = "neoforge"
        runtime["architectury"]["version"] = "20.0.4"
        runtime["compatibility_patch"] = "neoforge-26.1-break-event-v1"
        runtime["installer"] = f"neoforge-{runtime['loader_version']}"
        data["installers"][runtime["installer"]] = data["installers"].pop(
            old_installer
        )
        data["source_overlays"]["neoforge"] = data["source_overlays"].pop(old_loader)
        data["project"]["release_branch"] = f"fabric-and-neoforge-{version}"
        return data

    def test_checked_in_matrix_is_valid(self) -> None:
        release_matrix.validate_matrix(self.mutated())

    def test_runtime_compatibility_patch_is_known_neoforge_only_and_exact(self) -> None:
        release_matrix.validate_matrix(self.neoforge_compatibility_matrix())

        unknown = self.neoforge_compatibility_matrix()
        next(
            row for row in unknown["runtimes"] if row["loader"] == "neoforge"
        )["compatibility_patch"] = "unknown"
        self.assert_invalid(unknown, "unknown compatibility patch")

        wrong_type = self.neoforge_compatibility_matrix()
        next(
            row for row in wrong_type["runtimes"] if row["loader"] == "neoforge"
        )["compatibility_patch"] = []
        self.assert_invalid(wrong_type, "unknown compatibility patch")

        wrong_loader = self.mutated()
        fabric = next(
            row for row in wrong_loader["runtimes"] if row["loader"] == "fabric"
        )
        fabric["compatibility_patch"] = "neoforge-26.1-break-event-v1"
        self.assert_invalid(wrong_loader, "only on NeoForge")

        broad_dependency = self.neoforge_compatibility_matrix()
        self.fml_artifact(broad_dependency)["metadata"]["architectury"] = (
            "[20.0.4,)"
        )
        self.assert_invalid(broad_dependency, "metadata disagrees with its tested Architectury")

    def test_version_can_explicitly_select_no_remap(self) -> None:
        data = self.mutated()
        version = data["unit_test_version"]
        for artifact in data["artifacts"]:
            if artifact["artifact_version"] != version:
                continue
            artifact["no_remap"] = not artifact["no_remap"]
            prefix = f":{artifact['loader']}:{version}:"
            artifact["gradle_task"] = prefix + (
                "shadowJar" if artifact["no_remap"] else "remapJar"
            )
            artifact["harness_task"] = prefix + (
                "e2eHarnessJar" if artifact["no_remap"] else "remapE2EHarnessJar"
            )
        release_matrix.validate_matrix(data)

    def test_new_java_major_is_data_not_an_allowlist_change(self) -> None:
        data = self.mutated()
        version = data["unit_test_version"]
        for artifact in data["artifacts"]:
            if artifact["artifact_version"] == version:
                artifact["java"] = 26
        for runtime in data["runtimes"]:
            if runtime["runtime_version"] == version:
                runtime["java"] = 26
        release_matrix.validate_matrix(data)

    def test_paired_lanes_must_agree_on_no_remap(self) -> None:
        data = self.mutated()
        artifact = self.artifact(data, "fabric")
        prefix = f":fabric:{artifact['artifact_version']}:"
        artifact["no_remap"] = not artifact["no_remap"]
        artifact["gradle_task"] = prefix + (
            "shadowJar" if artifact["no_remap"] else "remapJar"
        )
        artifact["harness_task"] = prefix + (
            "e2eHarnessJar" if artifact["no_remap"] else "remapE2EHarnessJar"
        )
        self.assert_invalid(data, "disagree on Java/no_remap")

    def test_task_names_follow_explicit_no_remap(self) -> None:
        data = self.mutated()
        artifact = data["artifacts"][0]
        artifact["gradle_task"] = (
            f":{artifact['loader']}:{artifact['artifact_version']}:"
            + ("remapJar" if artifact["no_remap"] else "shadowJar")
        )
        self.assert_invalid(data, "Gradle task must be")

    def test_overlay_routes_cannot_name_unknown_versions(self) -> None:
        data = self.mutated()
        data["source_overlays"]["common"]["99.9"] = "legacy99_9"
        self.assert_invalid(data, "names unsupported versions.*99.9")

    def test_overlay_routes_require_legacy_roots(self) -> None:
        data = self.mutated()
        version = data["unit_test_version"]
        data["source_overlays"]["common"][version] = "v_fixture"
        self.assert_invalid(data, "must name legacy")

        traversal = self.mutated()
        traversal["source_overlays"]["common"][version] = "legacy../../escape"
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
            loader = self.fml_artifact(self.base)["loader"]
            oracle = root / loader / "src" / "v99" / "java" / "Oracle.java"
            oracle.parent.mkdir(parents=True)
            oracle.write_text("final class Oracle {}\n", encoding="utf-8")
            with self.assertRaisesRegex(release_matrix.MatrixError, "version snapshots remain"):
                release_matrix.validate_source_roots(matrix_path, self.base)

    def test_source_root_validation_caps_live_class_copies(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = self.mutated()
            data["source_overlays"]["common"]["fixture_one"] = "legacy_fixture_one"
            data["source_overlays"]["common"]["fixture_two"] = "legacy_fixture_two"
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
                root / "common" / "src" / "legacy_fixture_one" / "java",
                root / "common" / "src" / "legacy_fixture_two" / "java",
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
        fabric = self.artifact(fabric_broad, "fabric")
        version = fabric["artifact_version"]
        fabric["metadata_range"] = f">={version}"
        self.assert_invalid(fabric_broad, f"metadata_range must be ={version}")

        fml_wrong_lower = self.mutated()
        row = self.fml_artifact(fml_wrong_lower)
        row["metadata_range"] = "[0.0,0.1)"
        self.assert_invalid(fml_wrong_lower, "must start at its exact version")

        fml_broad = self.mutated()
        row = self.fml_artifact(fml_broad)
        row["metadata_range"] = f"[{row['artifact_version']},999)"
        self.assert_invalid(fml_broad, "immediate patch successor")

        duplicated = self.mutated()
        duplicated["artifacts"][0]["metadata"]["minecraft"] = (
            duplicated["artifacts"][0]["metadata_range"]
        )
        self.assert_invalid(duplicated, "only in metadata_range")

    def test_fml_pack_formats_are_explicit_positive_integers(self) -> None:
        missing = self.mutated()
        fml = self.fml_artifact(missing)
        del fml["metadata"]["pack_format"]
        self.assert_invalid(missing, "metadata.pack_format must be a positive integer")

        wrong_type = self.mutated()
        fml = self.fml_artifact(wrong_type)
        fml["metadata"]["server_data_pack_format"] = "15"
        self.assert_invalid(
            wrong_type,
            "metadata.server_data_pack_format must be a positive integer",
        )

        fabric = self.mutated()
        self.artifact(fabric, "fabric")["metadata"]["pack_format"] = 15
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
