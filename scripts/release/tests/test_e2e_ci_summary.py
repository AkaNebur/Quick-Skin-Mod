from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "e2e" / "ci_summary.py"
SPEC = importlib.util.spec_from_file_location("e2e_ci_summary", MODULE_PATH)
assert SPEC and SPEC.loader
summary = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = summary
SPEC.loader.exec_module(summary)


class E2ECISummaryTest(unittest.TestCase):
    def test_renders_bounded_scenario_and_store_telemetry(self) -> None:
        rendered = summary.render(
            {
                "results": [
                    {
                        "artifact_node": "fabric-1.20.1",
                        "scenario": "full",
                        "status": "pass",
                        "elapsed_s": 42.5,
                    }
                ],
                "runtime_store": {
                    "hits": 3,
                    "misses": 1,
                    "pruned_entries": 2,
                    "pruned_bytes": 1024,
                    "total_bytes": 2048,
                },
            }
        )
        self.assertIn("1/1 passed", rendered)
        self.assertIn("3 hit(s), 1 miss(es)", rendered)

    def test_rejects_unbounded_or_injected_data(self) -> None:
        with self.assertRaises(summary.SummaryError):
            summary.render({"results": [], "runtime_store": {}})
        with self.assertRaises(summary.SummaryError):
            summary.render(
                {
                    "results": [
                        {
                            "artifact_node": "fabric\nspoof",
                            "scenario": "full",
                            "status": "pass",
                            "elapsed_s": 1,
                        }
                    ],
                    "runtime_store": {
                        "hits": 0,
                        "misses": 0,
                        "pruned_entries": 0,
                        "pruned_bytes": 0,
                        "total_bytes": 0,
                    },
                }
            )
        with self.assertRaises(summary.SummaryError):
            summary.render(
                {
                    "results": [
                        {
                            "artifact_node": "fabric",
                            "scenario": "full",
                            "status": "pass",
                            "elapsed_s": 1,
                        }
                    ],
                    "runtime_store": {"surprise": 1},
                }
            )
        with self.assertRaises(summary.SummaryError):
            summary.render(
                {
                    "results": [
                        {
                            "artifact_node": "fabric",
                            "scenario": "full",
                            "status": "pass",
                            "elapsed_s": 1,
                        }
                    ],
                    "runtime_store": {
                        "hits": 0,
                        "misses": 0,
                        "pruned_entries": 0,
                        "pruned_bytes": 0,
                    },
                }
            )


if __name__ == "__main__":
    unittest.main()
