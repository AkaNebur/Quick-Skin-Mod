from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402


class PackagedRuntimeReportContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.game_dir = Path(self.temporary.name)
        (self.game_dir / "e2e-report").mkdir()
        (self.game_dir / "screenshots").mkdir()
        self.row = {"runtime_version": "1.21.10"}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def report(self, scenario: str, role: str) -> dict[str, object]:
        role_contract = packaged_runtime.SCENARIO_CONTRACT.role(scenario, role)
        return {
            "version": self.row["runtime_version"],
            "role": role,
            "scenario": scenario,
            "contract_sha256": packaged_runtime.SCENARIO_CONTRACT.sha256,
            "status": "pass",
            "steps": [
                {
                    "name": step.id,
                    "status": "pass",
                    "message": "assertion passed",
                    "screenshot": (
                        f"{step.id}.png"
                        if step.capture is not None
                        else None
                    ),
                }
                for step in role_contract.steps
            ],
        }

    def write_report(self, report: dict[str, object]) -> None:
        (self.game_dir / "e2e-report" / "report.json").write_text(
            json.dumps(report),
            encoding="utf-8",
        )

    def validate(self, scenario: str, role: str) -> dict[str, object]:
        with (
            patch.object(
                packaged_runtime,
                "inspect_screenshot_for_step",
                return_value={"validated": True},
            ),
            patch.object(
                packaged_runtime,
                "compare_screenshots",
                return_value={
                    "changed_fraction": 0.5,
                    "rms_difference": 1.0,
                    "required_changed_fraction": 0.03,
                    "region": [0.30, 0.28, 0.60, 0.85],
                },
            ) as compare,
        ):
            validated = packaged_runtime.validate_report(
                self.game_dir,
                self.row,
                scenario,
                role,
            )
        if scenario == "propagation-live" and role == "client_b":
            compare.assert_called_once_with(
                (self.game_dir / "screenshots" / "observe_before.png").resolve(),
                (
                    self.game_dir
                    / "screenshots"
                    / "await_live_change.png"
                ).resolve(),
                0.03,
                (0.30, 0.28, 0.60, 0.85),
            )
        return validated

    def test_accepts_exact_steps_captures_assertions_hash_and_comparisons(self) -> None:
        self.write_report(self.report("propagation-live", "client_b"))

        validated = self.validate("propagation-live", "client_b")

        self.assertEqual(
            {"observe_before->await_live_change"},
            set(validated["pixel_validation"]["comparisons"]),
        )
        self.assertEqual(
            {"baseline", "observe_before", "await_live_change"},
            set(validated["pixel_validation"]["screenshots"]),
        )

    def test_rejects_contract_step_capture_and_assertion_drift(self) -> None:
        base = self.report("propagation", "client_b")

        cases: list[tuple[str, dict[str, object]]] = []
        wrong_hash = copy.deepcopy(base)
        wrong_hash["contract_sha256"] = "0" * 64
        cases.append(("contract hash", wrong_hash))

        reordered = copy.deepcopy(base)
        reordered["steps"][0], reordered["steps"][1] = (
            reordered["steps"][1],
            reordered["steps"][0],
        )
        cases.append(("step order", reordered))

        extra_capture = copy.deepcopy(base)
        extra_capture["steps"][1]["screenshot"] = "unexpected.png"
        cases.append(("non-capture screenshot", extra_capture))

        missing_capture = copy.deepcopy(base)
        missing_capture["steps"][0]["screenshot"] = None
        cases.append(("missing screenshot", missing_capture))

        failed_assertion = copy.deepcopy(base)
        failed_assertion["steps"][1]["status"] = "fail"
        cases.append(("assertion status", failed_assertion))

        for label, report in cases:
            with self.subTest(label=label):
                self.write_report(report)
                with self.assertRaises(packaged_runtime.RuntimeFailure):
                    self.validate("propagation", "client_b")


if __name__ == "__main__":
    unittest.main()
