from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import e2e_readme  # noqa: E402
import matrix  # noqa: E402


class E2EReadmeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.matrix = matrix.load_matrix(ROOT / "release" / "release-matrix.json")
        cls.contract = ROOT / "e2e" / "scenario-contract.json"

    def test_profile_is_derived_from_matrix_and_contract(self) -> None:
        rendered = e2e_readme.render_profile(
            self.matrix, profile_branch="master", contract_path=self.contract
        )
        self.assertIn("`fabric-1.20.1`", rendered)
        self.assertIn("`forge-1.20.1`", rendered)
        self.assertIn("`concurrent-two-client`", rendered)
        self.assertIn("`40`", rendered)
        self.assertIn("`36`", rendered)
        self.assertIn("| Scenario | Profiles | Orchestration |", rendered)
        self.assertIn(
            "| `phase0-smoke` | `runtime-default`, `pr`, `release` |",
            rendered,
        )
        self.assertIn("| `propagation` | `pr`, `release` |", rendered)
        self.assertIn(
            "must emit a screenshot if and only if its contract entry declares `capture`",
            rendered,
        )

    def test_execution_profile_order_is_rendered_from_the_contract(self) -> None:
        payload = json.loads(self.contract.read_text(encoding="utf-8"))
        payload["scenarios"][-1]["execution_profiles"] = ["release", "pr"]
        with tempfile.TemporaryDirectory() as temporary:
            contract = Path(temporary) / "scenario-contract.json"
            contract.write_text(json.dumps(payload) + "\n", encoding="utf-8")
            rendered = e2e_readme.render_profile(
                self.matrix,
                profile_branch="master",
                contract_path=contract,
            )

        self.assertIn("| `full` | `release`, `pr` |", rendered)

    def test_release_profile_rejects_a_branch_matrix_disagreement(self) -> None:
        with self.assertRaises(e2e_readme.E2EReadmeError):
            e2e_readme.render_profile(
                self.matrix,
                profile_branch="fabric-and-neoforge-1.21.11",
                contract_path=self.contract,
            )

    def test_changed_matrix_facts_change_generated_lane_rows(self) -> None:
        changed = copy.deepcopy(self.matrix)
        for artifact in changed["artifacts"]:
            artifact["java"] = 21
        rendered = e2e_readme.render_profile(
            changed, profile_branch="master", contract_path=self.contract
        )
        self.assertIn("| `fabric-1.20.1` | `1.20.1` | Fabric | `21` |", rendered)

    def test_replacement_owns_only_the_marked_section(self) -> None:
        original = f"before\n{e2e_readme.START_MARKER}\nold\n{e2e_readme.END_MARKER}\nafter\n"
        replacement = f"{e2e_readme.START_MARKER}\nnew\n{e2e_readme.END_MARKER}"
        self.assertEqual(
            e2e_readme.replace_profile(original, replacement),
            f"before\n{replacement}\nafter\n",
        )
        with self.assertRaises(e2e_readme.E2EReadmeError):
            e2e_readme.replace_profile("unmarked", replacement)

    def test_readme_documents_the_current_platform_without_legacy_model(self) -> None:
        readme = (ROOT / "e2e" / "README.md").read_text(encoding="utf-8")
        for required in (
            "Java major declared by its artifact",
            "e2e-out/current",
            "atomic promotion",
            "scratch namespace",
            "RuntimeStore/v1",
            "gradle/verification-metadata.xml",
            "if and only if",
            "AI visual review",
            "pages-evidence-ready",
            "operation=rotate",
            "`full` by default",
            "`not-applicable`",
        ):
            with self.subTest(required=required):
                self.assertIn(required, readme)

        lowered = readme.lower()
        for obsolete in (
            "four " "scenarios",
            "completion " "marker",
            "visual-" "catalog",
            "e2e-out/" "profiles",
        ):
            with self.subTest(obsolete=obsolete):
                self.assertNotIn(obsolete, lowered)


if __name__ == "__main__":
    unittest.main()
