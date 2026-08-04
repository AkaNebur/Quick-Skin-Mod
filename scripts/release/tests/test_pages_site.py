from __future__ import annotations

import hashlib
import json
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
sys.path.insert(0, str(ROOT / "scripts" / "pages"))
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import packaged_runtime  # noqa: E402
from build_site import SiteBuildError, build  # noqa: E402
from evidence import (  # noqa: E402
    PublicEvidenceError,
    compact_bundle,
    prepare,
    validate_bundle,
)
from version_branches import parse_version_branch  # noqa: E402
from visual_evidence import load_catalog  # noqa: E402


def fixture_png(variant: int) -> bytes:
    width, height = 640, 360
    rows: list[bytes] = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            if variant == 0:
                pixel = (
                    (x // 40 * 17) % 256,
                    (y // 30 * 23) % 256,
                    ((x // 40 + y // 30) * 31) % 256,
                )
            else:
                pixel = (
                    (x // 40 * 17 + 83) % 256,
                    (y // 30 * 23 + 47) % 256,
                    ((x // 40 + y // 30) * 31 + 131) % 256,
                )
            row.extend(pixel)
        rows.append(b"\0" + bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        checksum = zlib.crc32(kind + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
        + chunk(b"IEND", b"")
    )


PNGS = (fixture_png(0), fixture_png(1))
PIXEL_METRICS = (
    {
        "width": 640,
        "height": 360,
        "file_sha256": hashlib.sha256(PNGS[0]).hexdigest(),
        "pixel_sha256": "c29b63f78c2d57d8c516c4eae485128ab8126b305ab13f23fd2a8404c310e6b2",
        "luma_entropy": 6.991,
        "meaningful_colors": 32,
        "dark_fraction": 0.0049,
        "light_fraction": 0.0,
    },
    {
        "width": 640,
        "height": 360,
        "file_sha256": hashlib.sha256(PNGS[1]).hexdigest(),
        "pixel_sha256": "94adbf8983321a30e282c91b57f45e292dbd6300b63edaaf3ba8d876d2df2540",
        "luma_entropy": 7.067,
        "meaningful_colors": 32,
        "dark_fraction": 0.0,
        "light_fraction": 0.0033,
    },
)


class PagesSiteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.evidence_root = self.root / "evidence"
        self.catalog = load_catalog()
        self.next_run_id = 1000

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_branch(self, branch: str, version: str) -> Path:
        self.next_run_id += 10
        parsed = parse_version_branch(branch)
        assert parsed is not None
        e2e_root = self.root / f"e2e-{version}"
        scenarios = ["phase0-smoke", "propagation", "propagation-live", "full"]
        for loader in parsed.loaders:
            artifact_node = f"{loader}-{version}"
            jar_sha256 = hashlib.sha256(f"jar:{artifact_node}".encode()).hexdigest()
            for scenario in scenarios:
                profile_relative = (
                    Path("profiles") / f"{artifact_node}--{version}--{scenario}"
                )
                profile = e2e_root / profile_relative
                reports: dict[str, object] = {}
                roles = sorted(
                    {
                        capture["role"]
                        for capture in self.catalog.captures
                        if capture["scenario"] == scenario
                    }
                )
                for role in roles:
                    captures = [
                        capture
                        for capture in self.catalog.captures
                        if capture["scenario"] == scenario and capture["role"] == role
                    ]
                    pairs = packaged_runtime.DISTINCT_SCREENSHOT_PAIRS.get(
                        (scenario, role), []
                    )
                    second_steps = {pair[1] for pair in pairs}
                    steps = []
                    metrics = {}
                    screenshots = profile / role / "screenshots"
                    screenshots.mkdir(parents=True, exist_ok=True)
                    for capture in captures:
                        filename = f"{capture['step']}.png"
                        variant = 1 if capture["step"] in second_steps else 0
                        (screenshots / filename).write_bytes(PNGS[variant])
                        steps.append(
                            {
                                "name": capture["step"],
                                "status": "pass",
                                "screenshot": filename,
                            }
                        )
                        metrics[capture["step"]] = dict(PIXEL_METRICS[variant])
                    comparison_metrics: dict[str, dict[str, object]] = {}
                    for pair in pairs:
                        first_step, second_step, required_change = pair[:3]
                        comparison = {
                            "changed_fraction": 1.0,
                            "rms_difference": 71.573 if len(pair) == 4 else 98.694,
                            "required_changed_fraction": required_change,
                        }
                        if len(pair) == 4:
                            comparison["region"] = list(pair[3])
                        comparison_metrics[f"{first_step}->{second_step}"] = comparison
                    reports[role] = {
                        "version": version,
                        "role": role,
                        "scenario": scenario,
                        "status": "pass",
                        "steps": steps,
                        "pixel_validation": {
                            "screenshots": metrics,
                            "comparisons": comparison_metrics,
                        },
                    }
                result = {
                    "artifact_node": artifact_node,
                    "runtime_version": version,
                    "loader": loader,
                    "scenario": scenario,
                    "jar_sha256": jar_sha256,
                    "port": 12345,
                    "status": "pass",
                    "profile": profile_relative.as_posix(),
                    "elapsed_s": 2.5,
                    "reports": reports,
                }
                (profile / "result.json").write_text(
                    json.dumps(result), encoding="utf-8"
                )

        matrix = self.root / f"matrix-{version}.json"
        matrix.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "project": {
                        "name": "Quick Skin",
                        "description": "Change and synchronize Minecraft appearances.",
                        "homepage": "https://modrinth.com/mod/quick-skin",
                        "sources": "https://github.com/AkaNebur/Quick-Skin-Mod",
                        "issues": "https://github.com/AkaNebur/Quick-Skin-Mod/issues",
                        "license": "All Rights Reserved",
                        "release_branch": branch,
                    },
                    "artifacts": [
                        {
                            "artifact_node": f"{loader}-{version}",
                            "artifact_version": version,
                            "loader": loader,
                        }
                        for loader in parsed.loaders
                    ],
                    "runtimes": [
                        {
                            "artifact_node": f"{loader}-{version}",
                            "runtime_version": version,
                            "loader": loader,
                        }
                        for loader in parsed.loaders
                    ],
                    "pr_scenarios": scenarios,
                }
            ),
            encoding="utf-8",
        )
        prepare(
            e2e_root=e2e_root,
            matrix_path=matrix,
            catalog_path=ROOT / "e2e" / "visual-catalog.json",
            output_root=self.evidence_root,
            repository="AkaNebur/Quick-Skin-Mod",
            source_run_id=str(self.next_run_id),
            source_branch=branch,
            source_sha="1" * 40,
            source_created_at="2026-08-02T12:00:00Z",
            target_run_id=str(self.next_run_id + 1),
            target_branch=branch,
            target_sha="2" * 40,
            target_created_at="2026-08-02T13:00:00Z",
        )
        return matrix

    def test_prepare_deduplicates_images_and_keeps_all_validated_captures(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")

        manifest = validate_bundle(
            self.evidence_root,
            branch,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )

        self.assertEqual(72, len(manifest["frames"]))
        self.assertEqual(8, len(manifest["lanes"]))
        self.assertEqual(2, len(list((self.evidence_root / branch / "images").glob("*.png"))))
        self.assertNotIn("source_path", json.dumps(manifest))

    def test_compacts_validated_pngs_and_preserves_both_image_identities(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        raw_manifest = validate_bundle(self.evidence_root, branch)
        compact_root = self.root / "compact"

        compact_bundle(
            self.evidence_root,
            compact_root,
            branch,
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )
        compact_manifest = validate_bundle(
            compact_root,
            branch,
            expected_kind="compact",
            expected_repository="AkaNebur/Quick-Skin-Mod",
            expected_target_sha="2" * 40,
        )

        self.assertEqual(2, compact_manifest["schema_version"])
        self.assertFalse(list((compact_root / branch).rglob("*.png")))
        self.assertEqual(
            2, len(list((compact_root / branch / "images").glob("*.webp")))
        )
        raw_by_id = {frame["frame_id"]: frame for frame in raw_manifest["frames"]}
        for frame in compact_manifest["frames"]:
            raw = raw_by_id[frame["frame_id"]]
            self.assertEqual(raw["file_sha256"], frame["file_sha256"])
            self.assertEqual((raw["width"], raw["height"]), (frame["width"], frame["height"]))
            derivative = frame["derivative"]
            published = compact_root / branch / derivative["asset"]
            self.assertEqual("webp", derivative["format"])
            self.assertEqual(
                derivative["file_sha256"], hashlib.sha256(published.read_bytes()).hexdigest()
            )
            self.assertEqual(
                derivative["file_sha256"], derivative["pixel_validation"]["file_sha256"]
            )
        self.assertTrue(
            all(
                "derivative_pixel_validation" in comparison
                for comparison in compact_manifest["comparisons"]
            )
        )

    def test_compaction_is_atomic_and_compact_validation_rejects_tampering(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        raw_manifest_path = self.evidence_root / branch / "manifest.json"
        original_manifest = json.loads(raw_manifest_path.read_text(encoding="utf-8"))
        original_manifest["frames"][0]["width"] += 1
        raw_manifest_path.write_text(json.dumps(original_manifest), encoding="utf-8")
        failed_output = self.root / "failed-compact"
        with self.assertRaises(PublicEvidenceError):
            compact_bundle(self.evidence_root, failed_output, branch)
        self.assertFalse((failed_output / branch).exists())

        self.evidence_root = self.root / "fresh-evidence"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        manifest_path = compact_root / branch / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        derivative_path = compact_root / branch / manifest["frames"][0]["derivative"]["asset"]
        derivative_path.write_bytes(derivative_path.read_bytes() + b"tampered")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(compact_root, branch, expected_kind="compact")

    def test_compacting_a_compact_cache_preserves_exact_bytes(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        first = self.root / "compact-first"
        second = self.root / "compact-second"
        compact_bundle(self.evidence_root, first, branch)
        compact_bundle(first, second, branch)

        first_files = {
            path.relative_to(first / branch): path.read_bytes()
            for path in (first / branch).rglob("*")
            if path.is_file()
        }
        second_files = {
            path.relative_to(second / branch): path.read_bytes()
            for path in (second / branch).rglob("*")
            if path.is_file()
        }
        self.assertEqual(first_files, second_files)

    def test_compact_manifest_rejects_source_and_derivative_metadata_drift(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        manifest_path = compact_root / branch / "manifest.json"
        original = json.loads(manifest_path.read_text(encoding="utf-8"))

        cases: list[tuple[str, dict[str, object]]] = []
        source_hash = json.loads(json.dumps(original))
        source_hash["frames"][0]["file_sha256"] = "f" * 64
        cases.append(("source hash", source_hash))
        source_dimensions = json.loads(json.dumps(original))
        source_dimensions["frames"][0]["width"] += 1
        cases.append(("source dimensions", source_dimensions))
        derivative_hash = json.loads(json.dumps(original))
        derivative_hash["frames"][0]["derivative"]["file_sha256"] = "f" * 64
        cases.append(("derivative hash", derivative_hash))
        derivative_dimensions = json.loads(json.dumps(original))
        derivative_dimensions["frames"][0]["derivative"]["width"] -= 1
        cases.append(("derivative dimensions", derivative_dimensions))
        derivative_metrics = json.loads(json.dumps(original))
        derivative_metrics["frames"][0]["derivative"]["pixel_validation"][
            "private_note"
        ] = "forbidden"
        cases.append(("derivative payload", derivative_metrics))
        derivative_comparison = json.loads(json.dumps(original))
        derivative_comparison["comparisons"][0]["derivative_pixel_validation"][
            "required_changed_fraction"
        ] = 0.0
        cases.append(("derivative comparison threshold", derivative_comparison))

        for label, manifest in cases:
            with self.subTest(label=label):
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                with self.assertRaises(PublicEvidenceError):
                    validate_bundle(compact_root, branch, expected_kind="compact")
        manifest_path.write_text(json.dumps(original), encoding="utf-8")

    def test_single_branch_artifact_rejects_a_sibling_bundle(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        self.write_branch("fabric-and-neoforge-1.21.1", "1.21.1")

        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch, only_branch=True)

    def test_build_rejects_a_branch_inventory_different_from_discovery(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")

        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                output=self.root / "site-output",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                optimize=False,
                expected_branches={branch, "fabric-and-neoforge-1.21.1"},
            )

    def test_builds_multiversion_link_page_gallery_and_machine_inventory(self) -> None:
        first_matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        self.write_branch("fabric-and-neoforge-1.21.1", "1.21.1")
        output = self.root / "site-output"

        summary = build(
            evidence_root=self.evidence_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=first_matrix,
            optimize=False,
        )

        self.assertEqual(2, summary["versions"])
        self.assertEqual(144, summary["frames"])
        self.assertTrue((output / ".nojekyll").is_file())
        self.assertTrue((output / "index.html").is_file())
        self.assertTrue((output / "e2e" / "index.html").is_file())
        self.assertEqual(4, len(list((output / "e2e" / "images").glob("*/*.png"))))
        site_data = json.loads((output / "site-data.json").read_text(encoding="utf-8"))
        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        self.assertEqual(["1.21.1", "1.20.1"], [row["version"] for row in site_data["releases"]])
        self.assertEqual(144, len(gallery["frames"]))
        self.assertEqual(144, len({frame["frame_id"] for frame in gallery["frames"]}))
        sample = gallery["frames"][0]
        published = output / "e2e" / sample["image"]
        self.assertEqual(sample["published_file_sha256"], published.stem)
        self.assertEqual(sample["published_file_sha256"], hashlib.sha256(published.read_bytes()).hexdigest())
        self.assertEqual(sample["source_file_sha256"], sample["source_pixel_validation"]["file_sha256"])
        self.assertEqual("forge-and-fabric-1.20.1", next(
            frame["source_branch"] for frame in gallery["frames"] if frame["version"] == "1.20.1"
        ))
        self.assertEqual(sample["target_branch"], next(
            release["target_branch"] for release in gallery["releases"] if release["version"] == sample["version"]
        ))
        self.assertNotIn("file_sha256", sample)
        self.assertNotIn("branch", sample)
        fabric_ids = [
            frame["capture_id"]
            for frame in gallery["frames"]
            if frame["version"] == "1.20.1" and frame["loader"] == "fabric"
        ]
        self.assertLess(
            fabric_ids.index("full.client_a.animated_cape_apply"),
            fabric_ids.index("full.client_a.animated_cape_advance"),
        )
        self.assertLess(
            fabric_ids.index("propagation-live.client_b.observe_before"),
            fabric_ids.index("propagation-live.client_b.await_live_change"),
        )
        gallery_js = (output / "assets" / "gallery.js").read_text(encoding="utf-8")
        self.assertNotIn("innerHTML", gallery_js)
        for html in (output / "index.html", output / "e2e" / "index.html"):
            content = html.read_text(encoding="utf-8")
            self.assertIn("Content-Security-Policy", content)
            self.assertNotRegex(content, r'<script[^>]+src="https?://')
            self.assertNotRegex(content, r'<link[^>]+href="https?://[^\"]+\.css')

    def test_rejects_stale_sha_and_traversing_asset(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(
                self.evidence_root,
                branch,
                expected_target_sha="3" * 40,
            )

        manifest_path = self.evidence_root / branch / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["frames"][0]["asset"] = "../manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)

    def test_rejects_symlinked_public_image_even_when_bytes_match(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        manifest = json.loads(
            (self.evidence_root / branch / "manifest.json").read_text(encoding="utf-8")
        )
        image = self.evidence_root / branch / manifest["frames"][0]["asset"]
        outside = self.root / "outside.png"
        outside.write_bytes(PNGS[0])
        image.unlink()
        image.symlink_to(outside)

        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)

    def test_rejects_reduced_fabricated_or_payload_bearing_manifest(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        manifest_path = self.evidence_root / branch / "manifest.json"
        original = json.loads(manifest_path.read_text(encoding="utf-8"))

        cases: list[tuple[str, object]] = []
        reduced = json.loads(json.dumps(original))
        reduced["frames"].pop()
        cases.append(("missing frame", reduced))
        fabricated = json.loads(json.dumps(original))
        fabricated["frames"][0]["frame_id"] = (
            fabricated["frames"][0]["frame_id"].rsplit("/", 1)[0] + "/fabricated"
        )
        cases.append(("fabricated frame id", fabricated))
        frame_payload = json.loads(json.dumps(original))
        frame_payload["frames"][0]["pixel_validation"]["private_note"] = "secret"
        cases.append(("frame payload", frame_payload))
        comparison_payload = json.loads(json.dumps(original))
        comparison_payload["comparisons"][0]["pixel_validation"]["private_note"] = "secret"
        cases.append(("comparison payload", comparison_payload))
        missing_comparison = json.loads(json.dumps(original))
        missing_comparison["comparisons"].pop()
        cases.append(("missing comparison", missing_comparison))
        weakened_comparison = json.loads(json.dumps(original))
        weakened_comparison["comparisons"][0]["pixel_validation"][
            "required_changed_fraction"
        ] = 0.0
        cases.append(("weakened comparison threshold", weakened_comparison))
        impossible_dimensions = json.loads(json.dumps(original))
        impossible_dimensions["frames"][0]["width"] = 1
        impossible_dimensions["frames"][0]["height"] = 1
        impossible_dimensions["frames"][0]["pixel_validation"]["width"] = 1
        impossible_dimensions["frames"][0]["pixel_validation"]["height"] = 1
        cases.append(("implausible dimensions", impossible_dimensions))
        jar_drift = json.loads(json.dumps(original))
        first_artifact = jar_drift["lanes"][0]["artifact_node"]
        drift_lane = next(
            lane
            for lane in jar_drift["lanes"][1:]
            if lane["artifact_node"] == first_artifact
        )
        drift_lane["jar_sha256"] = "f" * 64
        cases.append(("scenario jar drift", jar_drift))
        loader_drift = json.loads(json.dumps(original))
        loader_drift["release"]["artifacts"][1]["loader"] = loader_drift["release"][
            "artifacts"
        ][0]["loader"]
        cases.append(("duplicate loader", loader_drift))

        for label, data in cases:
            with self.subTest(label=label):
                manifest_path.write_text(json.dumps(data), encoding="utf-8")
                with self.assertRaises(PublicEvidenceError):
                    validate_bundle(self.evidence_root, branch)
        manifest_path.write_text(json.dumps(original), encoding="utf-8")

    def test_rejects_every_extra_entry_in_curated_bundle(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        self.write_branch(branch, "1.20.1")
        bundle = self.evidence_root / branch
        extra = bundle / "secret.txt"
        extra.write_text("must not enter the public cache", encoding="utf-8")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)
        extra.unlink()

        nested = bundle / "images" / "nested"
        nested.mkdir()
        (nested / "secret.txt").write_text("also forbidden", encoding="utf-8")
        with self.assertRaises(PublicEvidenceError):
            validate_bundle(self.evidence_root, branch)

    def test_optimized_asset_url_is_addressed_by_its_published_digest(self) -> None:
        matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        output = self.root / "optimized-site"
        build(
            evidence_root=self.evidence_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            optimize=True,
        )

        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        for frame in gallery["frames"]:
            published = output / "e2e" / frame["image"]
            digest = hashlib.sha256(published.read_bytes()).hexdigest()
            self.assertEqual("webp", frame["published_format"])
            self.assertEqual(digest, frame["published_file_sha256"])
            self.assertEqual(digest, published.stem)
            self.assertNotEqual(frame["source_file_sha256"], digest)
        from PIL import Image

        for published in list((output / "e2e" / "images").glob("*/*.webp")):
            with Image.open(published) as image:
                image.load()
                self.assertEqual("WEBP", image.format)
                self.assertEqual((640, 360), image.size)
        self.assertFalse(list(output.rglob("*.rendering.*")))

    def test_site_builds_directly_from_compact_cache_without_reencoding(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")
        compact_root = self.root / "compact"
        compact_bundle(self.evidence_root, compact_root, branch)
        output = self.root / "compact-site"

        build(
            evidence_root=compact_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            require_compact=True,
        )

        manifest = json.loads(
            (compact_root / branch / "manifest.json").read_text(encoding="utf-8")
        )
        gallery = json.loads(
            (output / "e2e" / "gallery-data.json").read_text(encoding="utf-8")
        )
        compact_by_id = {frame["frame_id"]: frame for frame in manifest["frames"]}
        for frame in gallery["frames"]:
            cached = compact_by_id[frame["frame_id"]]["derivative"]
            source = compact_root / branch / cached["asset"]
            published = output / "e2e" / frame["image"]
            self.assertEqual(source.read_bytes(), published.read_bytes())
            self.assertEqual(cached["file_sha256"], frame["published_file_sha256"])
            self.assertEqual(
                compact_by_id[frame["frame_id"]]["file_sha256"],
                frame["source_file_sha256"],
            )
        self.assertTrue(
            all("published_pixel_validation" in item for item in gallery["comparisons"])
        )

    def test_protected_site_mode_rejects_a_raw_handoff(self) -> None:
        branch = "forge-and-fabric-1.20.1"
        matrix = self.write_branch(branch, "1.20.1")
        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                output=self.root / "raw-site",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                require_compact=True,
            )

    def test_workflows_compact_before_fan_in_and_use_bounded_retention(self) -> None:
        pages = (ROOT / ".github" / "workflows" / "pages.yml").read_text(
            encoding="utf-8"
        )
        packaged = (ROOT / ".github" / "workflows" / "on-demand-e2e.yml").read_text(
            encoding="utf-8"
        )

        handoff = packaged.index("name: pages-e2e-${{ github.ref_name }}")
        self.assertIn("retention-days: 1", packaged[handoff : handoff + 600])
        compact = pages.index("python3 scripts/pages/evidence.py compact")
        fan_in = pages.index("name: collected-pages-${{ matrix.branch }}", compact)
        self.assertLess(compact, fan_in)
        self.assertIn("--kind compact", pages[compact:fan_in])
        durable = pages.index("name: ${{ steps.cache.outputs.name }}")
        self.assertIn("retention-days: 90", pages[durable : durable + 500])
        self.assertIn("--require-compact-evidence", pages)

    def test_untrusted_project_text_never_becomes_inline_html(self) -> None:
        matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        data = json.loads(matrix.read_text(encoding="utf-8"))
        payload = "<img src=x onerror=alert(1)>"
        data["project"]["description"] = payload
        matrix.write_text(json.dumps(data), encoding="utf-8")
        output = self.root / "site-output"

        build(
            evidence_root=self.evidence_root,
            output=output,
            repository="AkaNebur/Quick-Skin-Mod",
            matrix_path=matrix,
            optimize=False,
        )

        self.assertNotIn(payload, (output / "index.html").read_text(encoding="utf-8"))
        self.assertIn(payload, (output / "site-data.json").read_text(encoding="utf-8"))
        self.assertIn("textContent", (output / "assets" / "site.js").read_text(encoding="utf-8"))
        self.assertNotIn("innerHTML", (output / "assets" / "site.js").read_text(encoding="utf-8"))

    def test_rejects_project_links_that_cannot_render_safely(self) -> None:
        matrix = self.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        data = json.loads(matrix.read_text(encoding="utf-8"))
        data["project"]["homepage"] = "javascript:alert(1)"
        matrix.write_text(json.dumps(data), encoding="utf-8")

        with self.assertRaises(SiteBuildError):
            build(
                evidence_root=self.evidence_root,
                output=self.root / "unsafe-site",
                repository="AkaNebur/Quick-Skin-Mod",
                matrix_path=matrix,
                optimize=False,
            )


if __name__ == "__main__":
    unittest.main()
