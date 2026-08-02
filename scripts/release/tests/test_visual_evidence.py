from __future__ import annotations

import base64
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402
from check_visual_review import ReviewError, render, validate  # noqa: E402
from visual_evidence import (  # noqa: E402
    VisualEvidenceError,
    collect_evidence,
    load_catalog,
)
from visual_review import build_manifest  # noqa: E402


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class VisualEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.e2e_root = self.root / "e2e-out"
        self.catalog_path = self.root / "visual-catalog.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_catalog(self, captures: list[tuple[str, str, str]]) -> None:
        self.catalog_path.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "captures": [
                        {
                            "capture_id": f"{scenario}.{role}.{step}",
                            "scenario": scenario,
                            "role": role,
                            "step": step,
                            "title": f"{scenario} {step}",
                            "review_tier": "key",
                            "expectation": f"Expected {scenario} {step}",
                        }
                        for scenario, role, step in captures
                    ],
                }
            ),
            encoding="utf-8",
        )

    def write_result(
        self,
        scenario: str,
        *,
        step: str = "baseline",
        filename: str = "same.png",
        digest: str | None = None,
        status: str = "pass",
    ) -> Path:
        artifact = "fabric-1.20.1"
        profile_relative = Path("profiles") / f"{artifact}--1.20.1--{scenario}"
        profile = self.e2e_root / profile_relative
        screenshot = profile / "client_a" / "screenshots" / filename
        screenshot.parent.mkdir(parents=True)
        screenshot.write_bytes(PNG)
        file_sha256 = hashlib.sha256(PNG).hexdigest() if digest is None else digest
        result = {
            "artifact_node": artifact,
            "runtime_version": "1.20.1",
            "loader": "fabric",
            "scenario": scenario,
            "jar_sha256": "a" * 64,
            "port": 12345,
            "status": status,
            "profile": profile_relative.as_posix(),
            "elapsed_s": 1.0,
            "reports": {
                "client_a": {
                    "version": "1.20.1",
                    "role": "client_a",
                    "scenario": scenario,
                    "status": "pass",
                    "steps": [
                        {
                            "name": step,
                            "status": "pass",
                            "screenshot": filename,
                        }
                    ],
                    "pixel_validation": {
                        "screenshots": {
                            step: {
                                "width": 1,
                                "height": 1,
                                "file_sha256": file_sha256,
                                "pixel_sha256": "b" * 64,
                                "luma_entropy": 1.0,
                                "meaningful_colors": 1,
                                "dark_fraction": 0.0,
                                "light_fraction": 0.0,
                            }
                        },
                        "comparisons": {},
                    },
                }
            },
        }
        result_path = profile / "result.json"
        result_path.write_text(json.dumps(result), encoding="utf-8")
        return result_path

    def test_catalog_exactly_covers_runtime_screenshot_contract(self) -> None:
        catalog = load_catalog()
        runtime = {
            (scenario, role, step)
            for (scenario, role), steps in packaged_runtime.EXPECTED_SCREENSHOT_STEPS.items()
            for step in steps
        }
        self.assertEqual(runtime, set(catalog.by_key))

    def test_semantic_identity_keeps_same_filename_in_two_scenarios(self) -> None:
        self.write_catalog(
            [
                ("phase0-smoke", "client_a", "baseline"),
                ("propagation", "client_a", "baseline"),
            ]
        )
        self.write_result("phase0-smoke")
        self.write_result("propagation")

        lanes, frames, comparisons = collect_evidence(
            self.e2e_root, load_catalog(self.catalog_path)
        )

        self.assertEqual(2, len(lanes))
        self.assertEqual(2, len(frames))
        self.assertEqual(2, len({frame["frame_id"] for frame in frames}))
        self.assertEqual(
            {
                "phase0-smoke.client_a.baseline",
                "propagation.client_a.baseline",
            },
            {frame["capture_id"] for frame in frames},
        )
        self.assertEqual([], comparisons)

    def test_rejects_digest_drift_non_pass_and_path_traversal(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        cases = (
            {"digest": "0" * 64},
            {"status": "fail"},
            {"filename": "../same.png"},
        )
        for index, values in enumerate(cases):
            with self.subTest(values=values):
                case_root = self.root / f"case-{index}"
                original = self.e2e_root
                self.e2e_root = case_root
                try:
                    if values.get("filename") == "../same.png":
                        result_path = self.write_result("phase0-smoke")
                        data = json.loads(result_path.read_text(encoding="utf-8"))
                        data["reports"]["client_a"]["steps"][0]["screenshot"] = "../same.png"
                        result_path.write_text(json.dumps(data), encoding="utf-8")
                    else:
                        self.write_result("phase0-smoke", **values)
                    with self.assertRaises(VisualEvidenceError):
                        collect_evidence(self.e2e_root, load_catalog(self.catalog_path))
                finally:
                    self.e2e_root = original

    def test_ai_manifest_is_non_empty_and_uses_unique_frame_labels(self) -> None:
        self.write_catalog([("phase0-smoke", "client_a", "baseline")])
        self.write_result("phase0-smoke")

        manifest = build_manifest(
            self.e2e_root,
            self.catalog_path,
            include_all=False,
            combos={("1.20.1", "fabric")},
        )

        self.assertEqual(1, len(manifest))
        self.assertEqual("fabric-1.20.1/phase0-smoke/client_a/baseline", manifest[0]["label"])

    def test_rejects_a_missing_catalogued_client_role(self) -> None:
        self.write_catalog(
            [
                ("propagation", "client_a", "baseline"),
                ("propagation", "client_b", "baseline"),
            ]
        )
        self.write_result("propagation")

        with self.assertRaises(VisualEvidenceError):
            collect_evidence(self.e2e_root, load_catalog(self.catalog_path))


class VisualReviewContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = [
            {
                "path": "/tmp/frame.png",
                "label": "lane/scenario/client/step",
                "capture_id": "scenario.client.step",
                "kind": "scenario.client.step",
                "expectation": "Expected player",
            }
        ]
        self.clean = [
            {
                "label": "lane/scenario/client/step",
                "matches": True,
                "visible": "Expected player",
                "anomalies": [],
                "defect": False,
            }
        ]

    def test_accepts_exact_typed_verdict_and_renders_advisory_status(self) -> None:
        verdicts = validate(self.manifest, self.clean)
        summary, has_defects = render(verdicts)
        self.assertFalse(has_defects)
        self.assertIn("advisory", summary.lower())

    def test_rejects_empty_duplicate_extra_and_incoherent_verdicts(self) -> None:
        cases = (
            ([], self.clean),
            (self.manifest, []),
            (self.manifest, self.clean + self.clean),
            (self.manifest, [{**self.clean[0], "extra": True}]),
            (self.manifest, [{**self.clean[0], "matches": False, "defect": False}]),
            (self.manifest, [{**self.clean[0], "label": "unexpected"}]),
        )
        for manifest, report in cases:
            with self.subTest(manifest=manifest, report=report), self.assertRaises(ReviewError):
                validate(manifest, report)


if __name__ == "__main__":
    unittest.main()
