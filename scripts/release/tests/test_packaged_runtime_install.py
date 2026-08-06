from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402


class PackagedRuntimeClientInstallTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.cache = self.root / "cache"
        self.installer = self.root / "neoforge-installer.jar"
        self.installer.write_bytes(b"verified installer")
        self.row = {
            "loader": "neoforge",
            "runtime_version": "1.21.4",
            "loader_version": "21.4.156",
            "installer": "neoforge-21.4.156",
        }
        self.matrix = {"installers": {}}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def install_paths(self, row: dict[str, str] | None = None) -> tuple[Path, str, Path]:
        selected = self.row if row is None else row
        key = packaged_runtime.safe_id(
            f"{selected['loader']}-{selected['runtime_version']}-{selected['loader_version']}"
        )
        version_id = packaged_runtime.installed_version_id(selected)
        directory = self.cache / "clients" / key
        version_json = directory / "versions" / version_id / f"{version_id}.json"
        return directory, version_id, version_json

    def patched_launcher(self, install_minecraft_version: object) -> ExitStack:
        package = types.ModuleType("minecraft_launcher_lib")
        package.__path__ = []  # type: ignore[attr-defined]
        install = types.ModuleType("minecraft_launcher_lib.install")
        install.install_minecraft_version = install_minecraft_version  # type: ignore[attr-defined]
        package.install = install  # type: ignore[attr-defined]

        stack = ExitStack()
        stack.enter_context(
            mock.patch.dict(
                sys.modules,
                {
                    "minecraft_launcher_lib": package,
                    "minecraft_launcher_lib.install": install,
                },
            )
        )
        stack.enter_context(
            mock.patch.object(packaged_runtime.importlib.metadata, "version", return_value="8.0")
        )
        stack.enter_context(
            mock.patch.object(
                packaged_runtime, "installer_path", return_value=self.installer
            )
        )
        return stack

    @staticmethod
    def write_loader_profile(staging: Path, version_id: str) -> Path:
        version_json = staging / "versions" / version_id / f"{version_id}.json"
        version_json.parent.mkdir(parents=True, exist_ok=True)
        version_json.write_text(
            json.dumps({"inheritsFrom": "1.21.4", "jar": version_id}),
            encoding="utf-8",
        )
        return version_json

    def test_partial_first_attempt_is_discarded_before_clean_retry_is_published(self) -> None:
        directory, version_id, version_json = self.install_paths()
        staging_directories: list[Path] = []

        def install_vanilla(_version: str, target: str) -> None:
            staging_directories.append(Path(target))
            self.assertFalse(directory.exists())

        def run_installer(
            command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.assertEqual(cwd, Path(command[-1]))
            self.assertFalse(directory.exists())
            self.write_loader_profile(cwd, version_id)
            if len(staging_directories) == 1:
                raise packaged_runtime.RuntimeFailure("Read timed out")

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=run_installer
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            actual = packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.cache, "/fake/java"
            )

        self.assertEqual((directory, version_id), actual)
        self.assertEqual(2, checked.call_count)
        self.assertEqual(2, len(staging_directories))
        self.assertNotEqual(staging_directories[0], staging_directories[1])
        self.assertFalse(staging_directories[0].exists())
        self.assertTrue(version_json.is_file())
        self.assertNotIn("jar", json.loads(version_json.read_text(encoding="utf-8")))
        marker = json.loads(
            (directory / packaged_runtime.CLIENT_INSTALL_MARKER).read_text(encoding="utf-8")
        )
        self.assertEqual(
            packaged_runtime.client_install_marker_payload(self.row, version_id), marker
        )
        sleep.assert_called_once_with(5)

    def test_all_neoforge_attempts_fail_without_leaving_a_reusable_cache(self) -> None:
        directory, version_id, _version_json = self.install_paths()
        staging_directories: list[Path] = []

        def install_vanilla(_version: str, target: str) -> None:
            staging_directories.append(Path(target))

        def fail_after_partial_profile(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)
            raise packaged_runtime.RuntimeFailure("Read timed out")

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=fail_after_partial_profile
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            with self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure,
                "client installation failed after 3 attempt.*Read timed out",
            ):
                packaged_runtime.prepare_client_install(
                    self.matrix, self.row, self.cache, "/fake/java"
                )

        self.assertEqual(3, checked.call_count)
        self.assertEqual(3, len(staging_directories))
        self.assertFalse(directory.exists())
        self.assertTrue(all(not staging.exists() for staging in staging_directories))
        self.assertEqual([mock.call(5), mock.call(15)], sleep.call_args_list)

    def test_profile_without_completion_marker_is_rebuilt_not_reused(self) -> None:
        directory, version_id, version_json = self.install_paths()
        version_json.parent.mkdir(parents=True)
        version_json.write_text("{}\n", encoding="utf-8")
        poison = directory / "partial-download.txt"
        poison.write_text("must disappear", encoding="utf-8")

        def install_vanilla(_version: str, target: str) -> None:
            self.assertFalse(poison.exists())
            self.assertFalse(directory.exists())

        def successful_installer(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=successful_installer
        ) as checked:
            packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.cache, "/fake/java"
            )

        checked.assert_called_once()
        self.assertFalse(poison.exists())
        self.assertTrue(packaged_runtime.client_install_is_complete(directory, self.row, version_id))

    def test_exact_completed_install_is_reused_without_network_or_installer_work(self) -> None:
        directory, version_id, version_json = self.install_paths()
        version_json.parent.mkdir(parents=True)
        version_json.write_text("{}\n", encoding="utf-8")
        (directory / packaged_runtime.CLIENT_INSTALL_MARKER).write_text(
            json.dumps(packaged_runtime.client_install_marker_payload(self.row, version_id)) + "\n",
            encoding="utf-8",
        )
        install_vanilla = mock.Mock()

        with self.patched_launcher(install_vanilla):
            actual = packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.cache, "/fake/java"
            )
            packaged_runtime.installer_path.assert_not_called()  # type: ignore[attr-defined]

        self.assertEqual((directory, version_id), actual)
        install_vanilla.assert_not_called()

    def test_exact_marker_with_corrupt_profile_is_rebuilt(self) -> None:
        directory, version_id, version_json = self.install_paths()
        version_json.parent.mkdir(parents=True)
        version_json.write_text("{not-json\n", encoding="utf-8")
        (directory / packaged_runtime.CLIENT_INSTALL_MARKER).write_text(
            json.dumps(packaged_runtime.client_install_marker_payload(self.row, version_id)) + "\n",
            encoding="utf-8",
        )
        install_vanilla = mock.Mock()

        def successful_installer(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=successful_installer
        ) as checked:
            packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.cache, "/fake/java"
            )

        install_vanilla.assert_called_once()
        checked.assert_called_once()
        self.assertTrue(packaged_runtime.client_install_is_complete(directory, self.row, version_id))
        self.assertNotEqual("{not-json\n", version_json.read_text(encoding="utf-8"))

    def test_forge_failure_is_not_retried_by_the_neoforge_policy(self) -> None:
        row = {
            "loader": "forge",
            "runtime_version": "1.20.1",
            "loader_version": "1.20.1-47.4.9",
            "installer": "forge-1.20.1-47.4.9",
        }
        directory, _version_id, _version_json = self.install_paths(row)
        install_vanilla = mock.Mock()

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime,
            "run_checked",
            side_effect=packaged_runtime.RuntimeFailure("installer failed"),
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            with self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure, "after 1 attempt.*installer failed"
            ):
                packaged_runtime.prepare_client_install(
                    self.matrix, row, self.cache, "/fake/java"
                )

        checked.assert_called_once()
        sleep.assert_not_called()
        self.assertFalse(directory.exists())


if __name__ == "__main__":
    unittest.main()
