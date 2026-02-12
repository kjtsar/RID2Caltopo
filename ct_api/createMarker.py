#!/usr/bin/env python3
import hmac, hashlib, base64, time, requests, os, sys, json, uuid
import logging
import http.client

if False:
    # --- VERBOSE LOGGING SETUP ---
    # This will dump the raw HTTP request/response to your terminal
    http.client.HTTPConnection.debuglevel = 1
    logging.basicConfig()
    logging.getLogger().setLevel(logging.DEBUG)
    requests_log = logging.getLogger("requests.packages.urllib3")
    requests_log.setLevel(logging.DEBUG)
    requests_log.propagate = True
    # -----------------------------

CRED_ID = os.getenv('CTCRED_ID')
CRED_SECRET = os.getenv('CTCRED_SECRET')
TEAM_ID = os.getenv('CTCRED_TEAM') # 8NMQ0E

def caltopo_request(method, path, payload, is_json=True):
    expires = int((time.time() + 60) * 1000)
    
    # Use compact JSON for dicts, raw string for Base64
    if isinstance(payload, dict):
        payload_str = json.dumps(payload, separators=(',', ':'))
    else:
        payload_str = payload

    message = f"{method} {path}\n{expires}\n{payload_str}"
    key = base64.getDecoder().decode(CRED_SECRET) if hasattr(base64, 'getDecoder') else base64.b64decode(CRED_SECRET)
    
    sig = hmac.new(key, message.encode('utf-8'), hashlib.sha256).digest()
    sig_b64 = base64.b64encode(sig).decode('utf-8')

    params = {"id": CRED_ID, "expires": expires, "signature": sig_b64}
    headers = {'Content-Type': 'application/json' if is_json else 'text/plain'}
    
    url = f"https://caltopo.com{path}"
    resp = requests.request(method, url, params=params, data=payload_str, headers=headers)

    
    if resp.status_code not in [200, 201]:
        print(f"Error {resp.status_code} on {path}: {resp.text}")
        sys.exit(1)

    resp_str = resp.json() if resp.text.strip() else {}
    if resp_str:
        resp_pp = json.dumps(resp.json(), indent=4)
    else:
        resp_pp = resp_str
    print(f"Response for {path}:\n{resp_pp}")
    return resp_str

def create_marker(map_id, lat, lng, props_json={}):
    ts_now = int(time.time() * 1000)

def usage():
    desc = """
    Create a marker in <mapId> at <lat>,<lng>.
    if supplied, <props_json> contains a default set of 'properties' for the marker
    sample properties include: {"title": "marker_title", "description": "marker_description"}
"""
    print(f"Usage: {sys.argv[0]} <map_id> <lat> <lng> [<props_json>]{desc}")


if __name__ == "__main__":
    if len(sys.argv) < 5:
        usage()
    else:
        props_json = {}
        if len(sys.argv) > 5:
            props_json = sys.argv[5]
        create_marker(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], props_json)
    
