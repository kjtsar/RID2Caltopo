import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
from io import StringIO
from pathlib import Path

import refresh_catalog


def sample_catalog() -> dict:
    return {
        "schemaVersion": 1,
        "catalogVersion": "test",
        "reviewedAt": "2026-07-21T00:00:00Z",
        "refreshPolicy": {
            "automaticMaximumFrequencyDays": 7,
            "failureMode": "retain-last-known-good-and-warn",
        },
        "sources": [
            {
                "id": "example",
                "scope": "state",
                "jurisdiction": "CO",
                "agency": "Example Agency",
                "boundaryQueryUrl": "https://example.invalid/FeatureServer/0/query",
                "rulesUrl": "https://example.invalid/rules",
                "authority": "agency",
                "runtimeStatus": "active",
            }
        ],
    }


class RefreshCatalogTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.catalog = self.root / "catalog.json"
        self.state = self.root / "state.json"
        self.catalog.write_text(json.dumps(sample_catalog()), encoding="utf-8")
        self.now = datetime(2026, 7, 21, tzinfo=timezone.utc)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_verify(self, fetcher, *, now=None, force=False, strict=False):
        with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
            return refresh_catalog.verify(
                self.catalog,
                self.state,
                force=force,
                strict=strict,
                max_age_days=7,
                timeout=1,
                now=now or self.now,
                fetcher=fetcher,
            )

    def test_success_is_cached_for_seven_days(self):
        calls = []

        def fetcher(url, timeout):
            calls.append((url, timeout))
            return {"count": 0}

        self.assertEqual(0, self.run_verify(fetcher))
        self.assertEqual(0, self.run_verify(fetcher, now=self.now + timedelta(days=6)))
        self.assertEqual(1, len(calls))
        state = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual("success", state["lastResult"])

    def test_seventh_day_attempts_again(self):
        calls = []

        def fetcher(url, timeout):
            calls.append(url)
            return {"count": 0}

        self.run_verify(fetcher)
        self.run_verify(fetcher, now=self.now + timedelta(days=7))
        self.assertEqual(2, len(calls))

    def test_failure_is_rate_limited_and_keeps_previous_success(self):
        self.run_verify(lambda url, timeout: {"count": 0})
        successful_at = json.loads(self.state.read_text(encoding="utf-8"))["lastSuccessfulAt"]

        def failing_fetcher(url, timeout):
            raise OSError("offline")

        self.assertEqual(0, self.run_verify(failing_fetcher, now=self.now + timedelta(days=7)))
        self.assertEqual(0, self.run_verify(failing_fetcher, now=self.now + timedelta(days=8)))
        state = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual("failure", state["lastResult"])
        self.assertEqual(successful_at, state["lastSuccessfulAt"])

    def test_force_bypasses_weekly_cache(self):
        calls = []

        def fetcher(url, timeout):
            calls.append(url)
            return {"count": 0}

        self.run_verify(fetcher)
        self.run_verify(fetcher, now=self.now + timedelta(days=1), force=True)
        self.assertEqual(2, len(calls))

    def test_strict_mode_fails_on_network_error(self):
        def failing_fetcher(url, timeout):
            raise OSError("offline")

        self.assertEqual(1, self.run_verify(failing_fetcher, strict=True))

    def test_catalog_rejects_non_https_urls(self):
        value = sample_catalog()
        value["sources"][0]["rulesUrl"] = "http://example.invalid/rules"
        with self.assertRaises(ValueError):
            refresh_catalog.validate_catalog(value)

    def test_active_source_must_match_both_platforms(self):
        sources = refresh_catalog.validate_catalog(sample_catalog())
        android = (
            self.root
            / "app/src/main/java/org/ncssar/rid2caltopo/landrestrictions/LandRestrictionRepository.kt"
        )
        apple = self.root / "apple/Sources/R2CCore/OperationalLandRestriction.swift"
        android.parent.mkdir(parents=True)
        apple.parent.mkdir(parents=True)
        matching = 'example https://example.invalid/FeatureServer/0/query'
        android.write_text(matching, encoding="utf-8")
        apple.write_text(matching, encoding="utf-8")
        refresh_catalog.validate_runtime_bindings(sources, self.root)
        apple.write_text("example", encoding="utf-8")
        with self.assertRaises(ValueError):
            refresh_catalog.validate_runtime_bindings(sources, self.root)


if __name__ == "__main__":
    unittest.main()
