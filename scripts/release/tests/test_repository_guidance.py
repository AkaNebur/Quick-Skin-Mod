from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
LOCAL_LINK = re.compile(r"\[[^\]]+\]\((?!https?://)([^)#]+)(?:#[^)]+)?\)")


class RepositoryGuidanceTest(unittest.TestCase):
    def test_claude_is_only_the_agents_redirect(self) -> None:
        self.assertEqual(
            (ROOT / "CLAUDE.md").read_text(encoding="utf-8"),
            "@AGENTS.md\n",
        )

    def test_human_and_agent_entry_points_are_linked(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        contributing = (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8")
        build_gate = (ROOT / ".github" / "workflows" / "build-gate.yml").read_text(
            encoding="utf-8"
        )
        pull_request_template = (
            ROOT / ".github" / "pull_request_template.md"
        ).read_text(encoding="utf-8")

        self.assertIn("[CONTRIBUTING.md](CONTRIBUTING.md)", readme)
        self.assertIn("[AGENTS.md](AGENTS.md)", contributing)
        self.assertIn("CONTRIBUTING.md", pull_request_template)
        self.assertIn("AGENTS.md", pull_request_template)
        self.assertIn(
            "python -m unittest discover -s scripts/release/tests",
            build_gate,
        )

    def test_new_guidance_has_no_broken_local_links(self) -> None:
        documents = (
            ROOT / "README.md",
            ROOT / "CONTRIBUTING.md",
            ROOT / ".github" / "pull_request_template.md",
        )
        for document in documents:
            text = document.read_text(encoding="utf-8")
            for target in LOCAL_LINK.findall(text):
                with self.subTest(document=document.name, target=target):
                    self.assertTrue((document.parent / target).resolve().is_file())


if __name__ == "__main__":
    unittest.main()
