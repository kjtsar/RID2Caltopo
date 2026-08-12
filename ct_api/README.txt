
This directory contains a bunch of quick and dirty python fragments to verify various
caltopo Teams interactions.

All require the following environment variables be set: CTCRED_ID, CTCRED_SECRET, CTCRED_TEAM

* dumpMap.py <mapId>
       Where <mapId> is an existing map located under the Caltopo teams account.
       
* dumpAcct.py
       Dumps the caltopo teams account data (warning - lots o output).

* createMarker.py <mapId> <lat> <lng> [<props_json>]
       Create a marker in <mapId> at <lat>,<lng>.
       if supplied, <props_json> contains a default set of 'properties' for the marker
       sample properties include: {"title": "marker_title", "description": "marker_description"}

* createFolder.py <map_id> <title> [<props_json>]
    Create a folder in <mapId>
    <props_json> is an optional JSON string of properties.
    Example: '{"visible": False}'

* uploadImage.py <map_id> <image_file>

* testLiveTrack.py <map_id> [--lat <lat>] [--lng <lng>] [--elevation <meters>]
                   [--hold-seconds <seconds>] [--settle-seconds <seconds>]
                   [--only <variant>]
       Creates a temporary LiveTrack, compares the supported position-report request
       shapes, verifies each HTTP response, optionally leaves it visible for inspection,
       and removes the temporary LiveTrack.

FIXME:  Not all of these are guaranteed to work... some were experiments.  createFolder.py
 demonstrates better argument checking and more user friendliness than most, which all should
 probably be brought up to the same level of completeness some day...
