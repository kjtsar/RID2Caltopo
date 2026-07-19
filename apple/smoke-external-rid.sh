#!/bin/zsh
set -euo pipefail

device="${1:-booted}"
bundle_id="${R2C_APP_BUNDLE_ID:-org.ncssar.RID2CaltopoApple}"
remote_id="SIMRID$(date +%H%M%S)"

container="$(xcrun simctl get_app_container "$device" "$bundle_id" data)"
log_root="$container/Documents/RID2Caltopo/Logs"

if [[ ! -d "$log_root" ]]; then
    echo "RID2Caltopo logs were not found. Install and launch the app first." >&2
    exit 1
fi

python3 -c '
import json
import socket
import sys
import time

remote_id = sys.argv[1]
payload = {
    "aircraft_id": remote_id,
    "source": "wifiNan",
    "timestamp_ms": int(time.time() * 1000),
    "latitude": 39.7392,
    "longitude": -104.9903,
    "altitude_m": 1620.5,
    "heading_deg": 92.0,
    "speed_mps": 11.5,
    "operator_latitude": 39.7400,
    "operator_longitude": -104.9900,
    "rssi_dbm": -61,
}
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.sendto(json.dumps(payload).encode("utf-8"), ("127.0.0.1", 7654))
' "$remote_id"

for _ in {1..20}; do
    newest_log="$(find "$log_root" -type f -name 'Log_*.txt' -print0 | xargs -0 ls -t | head -1)"
    if [[ -n "$newest_log" ]] && grep -q "rid_rx remoteId=$remote_id " "$newest_log"; then
        grep "rid_rx remoteId=$remote_id " "$newest_log"
        echo "External Remote ID smoke test passed: $remote_id"
        exit 0
    fi
    sleep 0.25
done

echo "No accepted rid_rx record for $remote_id was found in $log_root" >&2
exit 1
