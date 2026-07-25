#!/usr/bin/env python3
"""Rate-limited release verification for protected-land source metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable


DEFAULT_MAX_AGE_DAYS = 7
USER_AGENT = "RID2Caltopo release catalog verifier (contact: kjtsar@kjt.us)"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def format_time(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def catalog_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_catalog(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    if catalog.get("schemaVersion") != 1:
        raise ValueError("protected-land catalog schemaVersion must be 1")
    policy = catalog.get("refreshPolicy")
    if not isinstance(policy, dict) or policy.get("automaticMaximumFrequencyDays") != 7:
        raise ValueError("protected-land catalog must declare a seven-day automatic refresh policy")
    sources = catalog.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("protected-land catalog must contain at least one source")
    seen: set[str] = set()
    for index, source in enumerate(sources):
        if not isinstance(source, dict):
            raise ValueError(f"source {index} must be an object")
        source_id = source.get("id")
        if not isinstance(source_id, str) or not source_id:
            raise ValueError(f"source {index} is missing id")
        if source_id in seen:
            raise ValueError(f"duplicate protected-land source id: {source_id}")
        seen.add(source_id)
        for field in ("scope", "jurisdiction", "agency", "authority", "runtimeStatus"):
            if not isinstance(source.get(field), str) or not source[field]:
                raise ValueError(f"source {source_id} is missing {field}")
        for field in ("boundaryQueryUrl", "rulesUrl"):
            url = source.get(field)
            parsed = urllib.parse.urlparse(url if isinstance(url, str) else "")
            if parsed.scheme != "https" or not parsed.netloc:
                raise ValueError(f"source {source_id} has invalid {field}")
    return sources


def validate_runtime_bindings(sources: list[dict[str, Any]], repo_root: Path) -> None:
    runtime_files = [
        repo_root
        / "app/src/main/java/org/ncssar/rid2caltopo/landrestrictions/LandRestrictionRepository.kt",
        repo_root / "apple/Sources/R2CCore/OperationalLandRestriction.swift",
    ]
    contents: dict[Path, str] = {}
    for path in runtime_files:
        if not path.is_file():
            raise ValueError(f"protected-land runtime source is missing: {path}")
        contents[path] = path.read_text(encoding="utf-8")
    for source in sources:
        if source["runtimeStatus"] != "active":
            continue
        for path, content in contents.items():
            if source["id"] not in content or source["boundaryQueryUrl"] not in content:
                raise ValueError(
                    f"active catalog source {source['id']} is not synchronized with {path}"
                )


def boundary_probe_url(query_url: str) -> str:
    components = urllib.parse.urlsplit(query_url)
    query = urllib.parse.parse_qsl(components.query, keep_blank_values=True)
    query.extend(
        [
            ("where", "1=0"),
            ("returnCountOnly", "true"),
            ("f", "json"),
        ]
    )
    return urllib.parse.urlunsplit(
        (components.scheme, components.netloc, components.path, urllib.parse.urlencode(query), "")
    )


def fetch_json(url: str, timeout: float) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = response.read(1_000_001)
        if len(payload) > 1_000_000:
            raise ValueError("probe response exceeded 1 MB")
    value = json.loads(payload)
    if not isinstance(value, dict):
        raise ValueError("probe response was not a JSON object")
    if "error" in value:
        error = value["error"]
        message = error.get("message") if isinstance(error, dict) else str(error)
        raise ValueError(message or "ArcGIS service returned an error")
    if not isinstance(value.get("count"), int):
        raise ValueError("ArcGIS count probe did not return a count")
    return value


def should_skip(state: dict[str, Any], digest: str, now: datetime, max_age_days: int) -> bool:
    last_attempt = parse_time(state.get("lastAttemptAt"))
    return bool(
        last_attempt
        and state.get("catalogSha256") == digest
        and now - last_attempt < timedelta(days=max_age_days)
    )


def verify(
    catalog_path: Path,
    state_path: Path,
    *,
    force: bool,
    strict: bool,
    max_age_days: int,
    timeout: float,
    now: datetime,
    fetcher: Callable[[str, float], dict[str, Any]] = fetch_json,
    runtime_root: Path | None = None,
) -> int:
    catalog = load_json(catalog_path)
    sources = validate_catalog(catalog)
    if runtime_root is not None:
        validate_runtime_bindings(sources, runtime_root)
    digest = catalog_digest(catalog_path)
    state = load_json(state_path) if state_path.exists() else {}

    if not force and should_skip(state, digest, now, max_age_days):
        last_result = state.get("lastResult", "unknown")
        print(
            f"Protected-land catalog: network verification skipped; last attempt "
            f"{state.get('lastAttemptAt', 'unknown')} ({last_result})."
        )
        if last_result != "success":
            print(
                "WARNING: The most recent protected-land catalog verification failed; "
                "the checked-in last-known-good catalog is being retained.",
                file=sys.stderr,
            )
        return 1 if strict and last_result != "success" else 0

    results: list[dict[str, str]] = []
    for source in sources:
        source_id = source["id"]
        try:
            fetcher(boundary_probe_url(source["boundaryQueryUrl"]), timeout)
            results.append({"id": source_id, "status": "ok"})
            print(f"Protected-land catalog: {source_id} boundary service verified.")
        except Exception as error:  # Network and schema failures are reported uniformly.
            results.append({"id": source_id, "status": "error", "message": str(error)})
            print(f"WARNING: {source_id} boundary verification failed: {error}", file=sys.stderr)

    failed = [result for result in results if result["status"] != "ok"]
    state.update(
        {
            "schemaVersion": 1,
            "catalogSha256": digest,
            "lastAttemptAt": format_time(now),
            "lastResult": "failure" if failed else "success",
            "results": results,
        }
    )
    if not failed:
        state["lastSuccessfulAt"] = format_time(now)
    atomic_write_json(state_path, state)

    if failed:
        print(
            "WARNING: Protected-land source refresh was not fully successful. "
            "The checked-in last-known-good catalog remains in use; automatic network verification "
            f"will not retry for {max_age_days} days unless --force is supplied.",
            file=sys.stderr,
        )
        return 1 if strict else 0

    print(f"Protected-land catalog: all {len(results)} boundary services verified.")
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=repo_root / "shared" / "protected-land-source-catalog.json",
    )
    parser.add_argument(
        "--state",
        type=Path,
        default=repo_root / ".release-state" / "protected-land-catalog-verification.json",
    )
    parser.add_argument("--max-age-days", type=int, default=DEFAULT_MAX_AGE_DAYS)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--force", action="store_true", help="ignore the weekly attempt cache")
    parser.add_argument("--strict", action="store_true", help="fail instead of warning when verification fails")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    repo_root = Path(__file__).resolve().parents[2]
    if args.max_age_days < 1:
        print("--max-age-days must be at least 1", file=sys.stderr)
        return 2
    try:
        return verify(
            args.catalog.resolve(),
            args.state.resolve(),
            force=args.force,
            strict=args.strict,
            max_age_days=args.max_age_days,
            timeout=args.timeout,
            now=utc_now(),
            runtime_root=repo_root,
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Protected-land catalog configuration error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
