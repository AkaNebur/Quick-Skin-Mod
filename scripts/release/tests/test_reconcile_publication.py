from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from typing import Any
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import reconcile_publication as reconciliation  # noqa: E402


class PublicationReconciliationTest(unittest.TestCase):
    def expected(self) -> reconciliation.ExpectedArtifact:
        return reconciliation.ExpectedArtifact(
            node="fabric-1.20.1",
            filename="Quick Skin - Fabric - 1.20.1-3.0.0.jar",
            path=Path("unused.jar"),
            bytes=123,
            sha1="1" * 40,
            sha256="2" * 64,
            sha512="3" * 128,
        )

    def test_modrinth_skips_only_an_exact_hash_and_identity_match(self) -> None:
        expected = self.expected()
        remote = {
            "id": "remote01",
            "project_id": "project1",
            "version_number": "mc1.20.1-v3.0.0-fabric-1.20.1",
            "files": [{
                "filename": expected.filename,
                "size": expected.bytes,
                "hashes": {"sha512": expected.sha512},
            }],
        }
        result = reconciliation.classify_modrinth(
            remote,
            [{"id": "remote01", "version_number": remote["version_number"]}],
            expected,
            "project1",
            remote["version_number"],
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "remote01")

    def test_modrinth_fails_closed_on_version_or_hash_collision(self) -> None:
        expected = self.expected()
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_modrinth(
                None,
                [{"id": "other", "version_number": "release-id"}],
                expected,
                "project1",
                "release-id",
            )
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_modrinth(
                {
                    "id": "other",
                    "project_id": "another-project",
                    "version_number": "release-id",
                    "files": [],
                },
                [],
                expected,
                "project1",
                "release-id",
            )

    def test_curseforge_reconciles_by_filename_sha1_and_size(self) -> None:
        expected = self.expected()
        exact = {
            "id": 42,
            "fileName": expected.filename,
            "fileLength": expected.bytes,
            "hashes": [{"algo": 1, "value": expected.sha1}],
        }
        result = reconciliation.classify_curseforge([exact], expected)
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "42")

        different = dict(exact)
        different["hashes"] = [{"algo": 1, "value": "f" * 40}]
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_curseforge([different], expected)

    def test_missing_marketplace_file_is_publishable(self) -> None:
        expected = self.expected()
        self.assertTrue(
            reconciliation.classify_modrinth(
                None, [], expected, "project1", "release-id"
            ).publish
        )
        self.assertTrue(reconciliation.classify_curseforge([], expected).publish)


class RequestRetryTest(unittest.TestCase):
    def test_transient_failures_retry_until_success(self) -> None:
        attempts = iter([
            urllib.error.HTTPError("https://api", 502, "bad gateway", None, None),
            urllib.error.URLError(TimeoutError("timed out")),
            io.BytesIO(json.dumps({"ok": True}).encode("utf-8")),
        ])

        def urlopen(request: Any, timeout: float) -> Any:
            value = next(attempts)
            if isinstance(value, Exception):
                raise value
            return value

        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            value = reconciliation.request_json("https://api", {}, sleep=sleeps.append)
        self.assertEqual(value, {"ok": True})
        self.assertEqual(sleeps, [2.0, 2.5])

    def test_client_rejection_never_retries(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://api", 403, "forbidden", None, None)

        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            with self.assertRaisesRegex(reconciliation.ReconciliationError, "HTTP 403"):
                reconciliation.request_json("https://api", {}, sleep=sleeps.append)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])

    def test_allowed_not_found_returns_none_without_retry(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://api", 404, "not found", None, None)

        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            value = reconciliation.request_json(
                "https://api", {}, allow_not_found=True, sleep=lambda _: None
            )
        self.assertIsNone(value)
        self.assertEqual(len(calls), 1)

    def test_persistent_transient_failure_stays_bounded(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://api", 503, "unavailable", None, None)

        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            with self.assertRaisesRegex(
                reconciliation.ReconciliationError,
                f"after {reconciliation.REQUEST_ATTEMPTS} attempts",
            ):
                reconciliation.request_json("https://api", {}, sleep=sleeps.append)
        self.assertEqual(len(calls), reconciliation.REQUEST_ATTEMPTS)
        self.assertEqual(len(sleeps), reconciliation.REQUEST_ATTEMPTS - 1)


class VerifySettleTest(unittest.TestCase):
    def test_verify_polls_until_the_publication_is_observable(self) -> None:
        results = iter([
            reconciliation.Reconciliation(True, None),
            reconciliation.Reconciliation(True, None),
            reconciliation.Reconciliation(False, "remote01"),
        ])
        sleeps: list[float] = []
        result = reconciliation.settle_publication(
            lambda: next(results),
            verify=True,
            attempts=6,
            delay_seconds=10.0,
            sleep=sleeps.append,
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "remote01")
        self.assertEqual(sleeps, [10.0, 10.0])

    def test_verify_fails_closed_when_never_observable(self) -> None:
        sleeps: list[float] = []
        with self.assertRaisesRegex(
            reconciliation.ReconciliationError, "not observable"
        ):
            reconciliation.settle_publication(
                lambda: reconciliation.Reconciliation(True, None),
                verify=True,
                attempts=3,
                delay_seconds=5.0,
                sleep=sleeps.append,
            )
        self.assertEqual(sleeps, [5.0, 5.0])

    def test_non_verify_inspects_exactly_once(self) -> None:
        calls: list[int] = []

        def inspector() -> reconciliation.Reconciliation:
            calls.append(1)
            return reconciliation.Reconciliation(True, None)

        result = reconciliation.settle_publication(
            inspector,
            verify=False,
            attempts=6,
            delay_seconds=10.0,
            sleep=lambda _: self.fail("the non-verify path must not wait"),
        )
        self.assertTrue(result.publish)
        self.assertEqual(len(calls), 1)


if __name__ == "__main__":
    unittest.main()
