#!/usr/bin/env python3
"""Exercise CalTopo's LiveTrack position-report endpoint in isolation.

Required environment variables: CTCRED_ID, CTCRED_SECRET, CTCRED_TEAM.
The Teams credentials are used only to create and remove a temporary LiveTrack;
position reports themselves use the custom-integration device ID.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

import requests


def required_environment(name):
    value = os.getenv(name)
    if not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def compact_json(value):
    return json.dumps(value, separators=(",", ":"), sort_keys=True)


def signed_request(method, domain, path, payload, credential_id, credential_secret):
    expires = int((time.time() + 120) * 1000)
    message = f"{method} {path}\n{expires}\n{payload}"
    signature = base64.b64encode(
        hmac.new(
            base64.b64decode(credential_secret),
            message.encode("utf-8"),
            hashlib.sha256,
        ).digest()
    ).decode("ascii")
    authentication = {
        "id": credential_id,
        "expires": expires,
        "signature": signature,
    }
    if method == "POST":
        return requests.request(
            method,
            f"https://{domain}{path}",
            data={**authentication, "json": payload},
            timeout=20,
        )
    return requests.request(
        method,
        f"https://{domain}{path}",
        params=authentication,
        data=payload,
        timeout=20,
    )


def summarize(response):
    body = " ".join(response.text.split())
    if len(body) > 240:
        body = body[:240] + "..."
    return f"HTTP {response.status_code}" + (f": {body}" if body else "")


def find_object_with_id(value, object_id):
    if isinstance(value, dict):
        if value.get("id") == object_id:
            return value
        for child in value.values():
            found = find_object_with_id(child, object_id)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = find_object_with_id(child, object_id)
            if found is not None:
                return found
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("map_id")
    parser.add_argument("--domain", default="caltopo.com")
    parser.add_argument("--group", default="DRONE")
    parser.add_argument("--lat", type=float, default=39.15308)
    parser.add_argument("--lng", type=float, default=-121.13284)
    parser.add_argument("--elevation", type=int, default=525)
    parser.add_argument(
        "--hold-seconds",
        type=int,
        default=0,
        help="seconds to leave the test track visible before removing it",
    )
    parser.add_argument(
        "--settle-seconds",
        type=float,
        default=5,
        help="seconds to poll dumpMap for asynchronously applied geometry",
    )
    parser.add_argument(
        "--only",
        choices=("colon-query", "grouped-query", "get-body", "post-body"),
        help="run only one request variant",
    )
    args = parser.parse_args()
    if args.hold_seconds < 0:
        parser.error("--hold-seconds must be zero or greater")
    if args.settle_seconds < 0:
        parser.error("--settle-seconds must be zero or greater")

    credential_id = required_environment("CTCRED_ID")
    credential_secret = required_environment("CTCRED_SECRET")
    required_environment("CTCRED_TEAM")

    live_track_path = f"/api/v1/map/{args.map_id}/LiveTrack"
    position_url = f"https://{args.domain}/api/v1/position/report/{args.group}"
    aircraft = {"gs": 12.5, "track": 90.0}
    camera = {
        "external_url": "https://example.com/rid2c-api-test",
        "thumbnail_url": "https://caltopo.com/favicon.ico",
    }
    nested_payload = compact_json({"aircraft": aircraft, "camera": camera})
    variants = [
        (
            "colon-query",
            "GET with colon-qualified aircraft and camera query parameters",
            "GET",
            "colon-query",
        ),
        (
            "grouped-query",
            "GET with grouped aircraft and camera JSON query parameters",
            "GET",
            "grouped-query",
        ),
        (
            "get-body",
            "GET with form body json=<nested payload>",
            "GET",
            "body",
        ),
        (
            "post-body",
            "POST with form body json=<nested payload>",
            "POST",
            "body",
        ),
    ]
    if args.only:
        variants = [variant for variant in variants if variant[0] == args.only]

    failures = 0
    for index, (_, label, method, parameter_location) in enumerate(variants):
        device_id = f"R2C-API-TEST-{index + 1}-{uuid.uuid4().hex[:8].upper()}"
        print(f"\n[{index + 1}] {label}")
        print(f"test call sign: {args.group}-{device_id}")
        start_payload = compact_json({
            "type": "Feature",
            "properties": {
                "class": "LiveTrack",
                "title": f"RID2C isolated API test {index + 1}",
                "deviceId": f"FLEET:{args.group}-{device_id}",
            },
        })
        start = signed_request(
            "POST", args.domain, live_track_path, start_payload,
            credential_id, credential_secret,
        )
        print(f"create LiveTrack: {summarize(start)}")
        if start.status_code not in (200, 201):
            failures += 1
            continue
        try:
            live_track_id = start.json()["result"]["id"]
        except (ValueError, KeyError, TypeError):
            print("Create response did not contain result.id", file=sys.stderr)
            failures += 1
            continue

        base_params = {
            "id": device_id,
            "lat": f"{args.lat:.7f}",
            "lng": f"{args.lng + index * 0.00005:.7f}",
            "elevation": str(args.elevation),
        }
        if parameter_location == "colon-query":
            position_params = {
                **base_params,
                "aircraft:gs": str(aircraft["gs"]),
                "aircraft:track": str(aircraft["track"]),
                "camera:external_url": camera["external_url"],
                "camera:thumbnail_url": camera["thumbnail_url"],
            }
            position_data = None
        elif parameter_location == "grouped-query":
            position_params = {
                **base_params,
                "aircraft": compact_json(aircraft),
                "camera": compact_json(camera),
            }
            position_data = None
        else:
            position_params = base_params
            position_data = {"json": nested_payload}

        try:
            before_snapshot = signed_request(
                "GET",
                args.domain,
                f"/api/v1/map/{args.map_id}/since/0",
                "",
                credential_id,
                credential_secret,
            )
            try:
                before_dump = before_snapshot.json() if before_snapshot.status_code == 200 else None
            except ValueError:
                before_dump = None
            # CalTopo rounds LiveTrack update times to whole seconds. Keep the
            # create and position report in different seconds so dumpMap can
            # prove whether the report actually touched this feature.
            time.sleep(1.2)
            response = requests.request(
                method,
                position_url,
                params=position_params,
                data=position_data,
                headers={"User-Agent": "RID2Caltopo/ct_api testLiveTrack.py"},
                timeout=20,
            )
            print(f"position report: {summarize(response)}")
            live_track = None
            after_dump = None
            deadline = time.monotonic() + args.settle_seconds
            while True:
                snapshot = signed_request(
                    "GET",
                    args.domain,
                    f"/api/v1/map/{args.map_id}/since/0",
                    "",
                    credential_id,
                    credential_secret,
                )
                if snapshot.status_code == 200:
                    try:
                        after_dump = snapshot.json()
                        live_track = find_object_with_id(after_dump, live_track_id)
                    except ValueError:
                        pass
                if live_track is not None and live_track.get("geometry") is not None:
                    break
                if time.monotonic() >= deadline:
                    break
                time.sleep(0.5)
            before_live_track = (
                find_object_with_id(before_dump, live_track_id)
                if before_dump is not None else None
            )
            print(f"complete dump changed: {before_dump != after_dump}")
            print(f"LiveTrack object changed: {before_live_track != live_track}")
            print("dumpMap LiveTrack object:")
            print(json.dumps(live_track, indent=2, sort_keys=True))
            if args.hold_seconds:
                print(f"holding LiveTrack for {args.hold_seconds} second(s)")
                time.sleep(args.hold_seconds)
        finally:
            stop_path = f"{live_track_path}/{live_track_id}"
            stop = signed_request(
                "DELETE", args.domain, stop_path, "", credential_id, credential_secret,
            )
            print(f"remove LiveTrack: {summarize(stop)}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
