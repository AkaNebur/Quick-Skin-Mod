from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from dependency_integrity import DependencyIntegrityError, verified_sha256  # noqa: E402


def runtime_dependency_coordinates(matrix_path: Path) -> set[tuple[str, str, str, str]]:
    """Mirror e2e/packaged_runtime.py's runtime_dependencies for the active matrix.

    The identities are derived rather than pinned so this stays a real invariant on
    every release branch instead of only on the branch whose matrix was current.
    """

    data = json.loads(matrix_path.read_text(encoding="utf-8"))
    coordinates: set[tuple[str, str, str, str]] = set()
    for runtime in data["runtimes"]:
        loader = runtime["loader"]
        if loader == "fabric":
            version = runtime["fabric_api"]
            coordinates.add(
                (
                    "net.fabricmc.fabric-api",
                    "fabric-api",
                    version,
                    f"fabric-api-{version}.jar",
                )
            )
        module = f"architectury-{loader}"
        version = runtime["architectury"]["version"]
        coordinates.add(
            ("dev.architectury", module, version, f"{module}-{version}.jar")
        )
    return coordinates


class E2EDependencyIntegrityTest(unittest.TestCase):
    def test_checked_in_runtime_dependencies_have_exact_gradle_hashes(self) -> None:
        metadata = ROOT / "gradle" / "verification-metadata.xml"
        coordinates = runtime_dependency_coordinates(
            ROOT / "release" / "release-matrix.json"
        )
        self.assertTrue(coordinates, "matrix declares no runtime dependencies")
        for group, name, version, artifact in sorted(coordinates):
            with self.subTest(artifact=artifact):
                self.assertRegex(
                    verified_sha256(
                        metadata,
                        group=group,
                        name=name,
                        version=version,
                        artifact=artifact,
                    ),
                    r"\A[0-9a-f]{64}\Z",
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
        # Derive a component that really exists here, so the rejection is the traversal
        # guard rather than an incidentally absent coordinate on this branch.
        group, name, version, artifact = sorted(
            runtime_dependency_coordinates(ROOT / "release" / "release-matrix.json")
        )[0]
        with self.assertRaises(DependencyIntegrityError):
            verified_sha256(
                ROOT / "gradle" / "verification-metadata.xml",
                group=group,
                name=name,
                version=version,
                artifact=f"../{artifact}",
            )


if __name__ == "__main__":
    unittest.main()
