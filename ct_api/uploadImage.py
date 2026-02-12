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

CRED_ID = os.getenv('CTCRED_ID')
CRED_SECRET = os.getenv('CTCRED_SECRET')
TEAM_ID = os.getenv('CTCRED_TEAM') # 8NMQ0E

def caltopo_request(method, path, payload, is_raw_data=False):
    expires = int((time.time() + 60) * 1000)
    
    if is_raw_data:
        payload_str = payload
    else:
        payload_str = json.dumps(payload, separators=(',', ':'))

    message = f"{method} {path}\n{expires}\n{payload_str}"
    key = base64.b64decode(CRED_SECRET)
    
    sig = hmac.new(key, message.encode('utf-8'), hashlib.sha256).digest()
    sig_b64 = base64.getEncoder().encodeToString(sig) if hasattr(base64, 'getEncoder') else base64.b64encode(sig).decode('utf-8')

    url = f"https://caltopo.com{path}"
    headers = {'User-Agent': f"{sys.argv[0]}"}
    if is_raw_data:
        print(f"DEBUG: Method: '{method}', url:'{url}', data:'{payload_str}, headers:'{headers}'")
        params = {"id": CRED_ID, "expires": expires, "signature": sig_b64}
        resp = requests.request(method, url, params=params, data=payload_str, headers=headers)
    else:
        form_data = {
            "id": CRED_ID,
            "expires": expires,
            "signature": sig_b64,
            "json": payload_str
        }
        print(f"DEBUG: Method: '{method}', url:'{url}', data:'{payload_str}, headers:'{headers}'")
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


def upload_photo(map_id, photo_path):
    media_id = str(uuid.uuid4())
    marker_id = str(uuid.uuid4())

    print(f"Step 1: Creating backend media object '{media_id} for {TEAM_ID}")
    media_metadata_payload = { "properties": { "creator": TEAM_ID } }
    caltopo_request("POST", f"/api/v1/media/{media_id}", media_metadata_payload)

    
    print("Step 2: Uploading Media Data...")
    with open(photo_path, "rb") as img_file:
        b64_data = base64.b64encode(img_file.read()).decode()
    media_data_payload = {"creator": f"{TEAM_ID}", "data":b64_data}
    
    # Pass the raw string directly
    caltopo_request("POST", f"/api/v1/media/{media_id}/data", media_data_payload)

    
    print("Step 3: Creating Marker {marker_id}...")
    marker_payload = {
        "id": marker_id,
        "type": "Feature",
        "geometry": {
            "type": "Point",
            "coordinates": [-121.19, 39.26, 0, 0]
        },
        "properties": {
            "title": "Drone Photo Location",
            "marker-symbol": "Drone",
            "class": "Marker",
            "created": int(time.time()*1000)
        }
    }
    caltopo_request("POST", f"/api/v1/map/{map_id}/Marker/{marker_id}", marker_payload)


    print("Step 4: Linking image to marker...")
    media_obj_payload = {
        "type": "Feature",
        "properties": {
            "title": os.path.basename(photo_path),
            "parentId": f"Marker:{marker_id}",
            "backendMediaId": media_id,
            "marker-symbol": "aperture",
            "class": "MapMediaObject",
            "created": int(time.time()*1000)
        }
    }
    caltopo_request("POST", f"/api/v1/map/{map_id}/MapMediaObject", media_obj_payload)
    print("SUCCESS! Check the map.")

if __name__ == "__main__":
    upload_photo(sys.argv[1], sys.argv[2])
    
