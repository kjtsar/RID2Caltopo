#!/usr/bin/env python3
import hmac, hashlib, base64, time, requests, os, sys, json, uuid

debug=True
debug_http=False
if debug_http:
    import logging
    import http.client
    # --- VERBOSE LOGGING SETUP ---
    # This will dump the raw HTTP request/response to your terminal
    http.client.HTTPConnection.debuglevel = 1
    logging.basicConfig()
    logging.getLogger().setLevel(logging.DEBUG)
    requests_log = logging.getLogger("requests.packages.urllib3")
    requests_log.setLevel(logging.DEBUG)
    requests_log.propagate = True
    # -----------------------------

def caltopo_request(method, path, payload):
    expires = int((time.time() + 60) * 1000)
    
    if isinstance(payload, dict):
        payload_str = json.dumps(payload, separators=(',', ':'))
    else:
        payload_str = ""

    message = f"{method} {path}\n{expires}\n{payload_str}"
    key = base64.b64decode(CRED_SECRET)
    
    sig = hmac.new(key, message.encode('utf-8'), hashlib.sha256).digest()
    sig_b64 = base64.getEncoder().encodeToString(sig) if hasattr(base64, 'getEncoder') else base64.b64encode(sig).decode('utf-8')

    form_data = {
        "id": CRED_ID,
        "expires": expires,
        "signature": sig_b64,
        "json": payload_str
    }
    headers = {'User-Agent': '{sys.argv[0]}'}
    print(f"Method: '{method}', message:{message}")
    url = f"https://caltopo.com{path}"
    resp = requests.request(method, url, data=form_data, headers=headers)
    
    if resp.status_code not in [200, 201]:
        print(f"Error {resp.status_code} on {path}: {resp.text}")
        sys.exit(1)

    resp_str = resp.json() if resp.text.strip() else {}
    if debug:
        try:
            resp_pp = json.dumps(resp_str, indent=4)
        except:
            resp_pp = {}
        try:
            body = json.dumps(payload, indent=4)
        except:
            body = {}
            
        print(f"The request:{path} with payload:\n{body}\n\nProduced:\n{resp_pp}")
    return resp_str


def create_folder(map_id, title, props_json_str="{}"):
    # Parse the input JSON string if it exists
    input_props = {}
    if props_json_str:
        try:
            input_props = json.loads(props_json_str)
        except json.JSONDecodeError:
            print("Warning: Could not parse props_json. Using defaults.")

    # Construct the GeoJSON Feature
    payload = {
        "properties": {
            "title": input_props.get("title", "New Folder"),
            "visible": input_props.get("visible", True),
            "labelVisible": input_props.get("labelVisible", True),
        }
    }

    # CalTopo markers are created by POSTing to /api/v1/map/<id>/Marker
    path = f"/api/v1/map/{map_id}/Folder"
    return caltopo_request("POST", path, payload)

def usage():
    desc = """
    Create a folder in <mapId> with <title>
    <props_json> is an optional JSON string of properties.
    Example: '{"visible": False}'
"""
    print(f"Usage: {sys.argv[0]} <map_id> <title>")
    print(desc)

if __name__ == "__main__":
    # Load Environment
    CRED_ID = os.getenv('CTCRED_ID')
    CRED_SECRET = os.getenv('CTCRED_SECRET')
    TEAM_ID = os.getenv('CTCRED_TEAM')

    if not CRED_ID or not CRED_SECRET:
        print("Error: CTCRED_ID or CTCRED_SECRET not set in environment.")
        sys.exit(1)

    if len(sys.argv) < 3:
        usage()
    else:
        m_id = sys.argv[1]
        m_title = sys.argv[2]
        m_props = sys.argv[3] if len(sys.argv) > 3 else "{}"
        
        create_folder(m_id, m_title, m_props)
        
