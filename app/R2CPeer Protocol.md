# R2CPeer protocol v1.1

## Overview
Real-world experience in rough terrain demonstrated that there are going
to be scenarios where we just won’t be able to get complete coverage for
all search segments with a single Dronescout Bridge + RID2Caltopo app
(R2C) instances.   This document describes a websockets protocol
used to share Remote ID updates cooperatively between multiple R2C instances.

## Is this really a problem that needs a solution?
There is nothing preventing anyone from running two separate instances
at the same time, but chances are good that no matter how great the
site selection for the R2C instances are, there will be cases where
multiple instances will catch different RID updates from the same
drone.  When this happens, there will be overlap/redundancy resulting
in extra traffic into the caltopo server, which we’d prefer to avoid.
There are also going to be hit-and-miss cases where we end up with
messy lines bisecting the actual path of travel.   So… this should
basically just work, but it won’t be very efficient and it will likely
result in a messier caltopo map that would require interpretation
on behalf of the user.  The extent to which maps will be messy will
be determined by the amount of overlap and the _maxIdleTime_
configuration (configurable w/20 seconds default).   If anytime a
drone stops reporting waypoint updates for _maxIdleTime_
seconds or longer the track will be terminated.  Extra tracks,
especially if they turn out to be short segments, will likely
frustrate anyone trying to understand coverage, so there is value in
eliminating redundant fragmented tracks.  Basic testing with two R2C
instances (v.3.6.1-9Oct2025rc3) feeding off the same bridge appeared
very clean, but then again, they both had all the same inputs… Need
to test with distributed bridges and drones flying between them.  To
test the non-cooperating functionality, just turn off the _Use Peers_
slider in the settings panel.

## NAT & PAT and ZeroTier to the rescue:
Network and Port Address Translation implemented by most ISPs makes
the required peer-to-peer connectivity between R2C instances a
challenge.   Fortunately, a company called ZeroTier provides a
solution: You’ll need to run ZeroTier on all your devices running
RID2Caltopo if they aren’t already connected to the same subnet.
To do this, log in to [ZeroTier.com](https://www.zerotier.com) and create a free account, then
create a public network (I called mine RID2Caltopo oddly enough)
and copy down the network ID. Then download the free ZeroTier app
from the playstore and connect to the network ID copied above.
If you keep the network private, you’ll need to approve each device
connecting to it for the first time.  After that, you just need to
make sure that the ZeroTier app is up and running and connected to
your network prior to starting RID2Caltopo.

To keep latency to a minimum, this protocol uses WebSockets
connections between cooperating R2C instances.

## Implementation
Each time an R2C instance connects to a Caltopo map, it will place a
Marker in the _Drone Tracks_ folder with its current location (the
phone/tablet - not the bridge receiver, but they should be co-located
fairly close by).  That Marker has a default label of the form:
>      R2C: <deviceName>

Where **<deviceName>** is the user assigned name of the device
(as discoverable via bluetooth).  Additionally, that marker will use
the UUID of the device as the ID for the marker (making it easy to
find/delete).  One further tweak to the marker will be to include a
custom “r2c_ipaddrs” parameter that will contain a JSON list of one or
more IP addresses that the device is connected to of the form:
>     [{ “ssid” : “<network_SSID>”,  “ipaddr” : “<ipaddr>”}, ...]

This makes it fairly easy to pick a common subnet that R2C
instances are on.   After placing the marker, fetch any updates
from the map one more time and verify that there are no new R2C
markers listed in the directory.  Turns out we can use the app’s
UUID as the UUID for the marker, greatly simplifying identification
and teardown.

When an R2C instance first connects to a peer instance, it sends
a _hello_ message.   The recipient responds with a _hello-ack_
containing a list of all the drones the recipient is currently
tracking.

Any new remoteid that is detected after this initial _hello_
exchange needs to be negotiated with peers.  When a new drone
is detected, the R2C instance sends _add-drone_ messages to
it's peers to negotiate who gets to serve it based mostly on
the drone's proximity to the R2C instance.  Recipients either
respond with _add-drone-ack_ or _add-drone-nack_ responses.



check with the other R2C instances to see if one of the others
already claims it.   This produces an _add-drone_ message that
might be better described as an “I claim this new drone” message that is broadcast to its
peers and all either acknowledge the assignment or decline it.
The “I claim…” message will include the RID, the drone’s msec
timestamp when it was first detected along with the IP address
of the detecting app and how far it was away from the detecting
R2C instance.


## R2Cv1.1 Protocol:
Implemented over WebSockets with json messages as payload.   Every outbound message
gets a response. Note that response times can vary due to vagaries of the internet
(missing satellite connection, etc…) so delays can occasionally be significant… In
the latest release, all websocket timeouts have been bumped to 60 seconds, but
timeouts are still likely to be a factor.  We will rely on connectivity to the Map
as our source of truth about the state of R2C connectivity as that is where tracks
are being recorded.

So, ultimately, if an R2C instance can connect to the map, but can't establish
or loses connectivity with its peers, it should go ahead and assume ownership of
any drones it sees as if it is independent.

The big change with 1.1 is that the protocol now supports sharing of drone state
periodically between peers.  These status dumps should occur often enough to be
useful, but not so often as to consume significant bandwidth.   Instead of just
passing remoteIds back and forth, the entire dronespec is transferred, allowing
recipients to see how many of each type of message the drone has posted and when
the first message was received.

The Initial hello/ack pairing also adds app start-timestamp and app-version.


## Protocol summary
Network topography looks as follows where R2C1 starts first,
then R2C2 connects to R2C1's server when it comes on-line,
prompting R2C1 to connect to R2C2's server as well. Then
R2C3 starts up and connects to both R2C1 and R2C2 and
receives back connections from each, so 2x N-1 connections,
where N is the number of R2C instances.
>
>      Figure 1: three peer topology:
>          +-------------+
>          | +---------+ |
>          | |         | |
>          V |         V |
>          R2C1←-R2C2←-R2C3
>           |    ^ |    ^
>           |    | |    |
>           +----+-+----+


After R2C1 launches its server, the app runs as normal.  When R2C2 starts, it launches it's
server, then establishes a client connection R2C1s server and sends a basic hello,
requesting the list of drones that R2C1 has adopted.  Similarly, when R3C3 starts, it
queries both R2C1 and R2C2.  There is no notion of numbered R2C instances, this example is
using the designations here to differentiate the order in which they come on-line, otherwise
they are all identical.

R2Cv1 Protocol:
### Hello
 When a new R2C connects to an existing R2C server.
>
>      R2C2->R2C1:  {
>          “type”: “hello”,
>          “my-id”:”<uuid>”,
>          “app-vers”: “1.0.1”,
>          “start-timestamp”: “12456789011222”
>      }
>
Response to hello:
>
>      R2C1->R2C2: {
>          “type” : “hello-ack”,
>          “my-active-dronelist” : [
>            {
>                “remoteId”:“<rid1>”,
>                “mappedId”:”<mappedId1>”,
>                “startTimeInMsec”:”<startTimeInMSec1>”,
>                “BT4”: “<bt4MsgCount>”,
>                “BT5”: “<bt5MsgCount>”,
>                   …
>            },
>          …
>          ],
>         “ct-rtt-msec” : “<avg-rtt-in-msec>”,
>         “my-id”:“<uuid>”
>         “app-vers”: “1.0.1”,
>         “start-timestamp”: “12456789011222”
>     }

Note:  the “ct-rtt-msec” is the running average round trip time for reporting waypoint
position updates to caltopo and will be zero if no drones have been reported to Caltopo
yet.  Similarly, the “my-active-dronelist” will be empty.

### drop-drone
This message is used when a drone hasn’t posted a position update in TF seconds,
indicating the drone is no longer owned/monitored by the reporting R2C instance.

>
>     R2C2->R2C1: {
>         “type” : “drop-drone”,
>         “rid”:“<rid>”,
>         “ct-rtt-msec” : “<avg-caltopo-rtt>”
>     }
>

Response to drop-drone will always be:
>
>    R2C1->R2C2: {“type”: “drop-drone-ack”}
>

### add-drone
This is used when a drone is first detected by an R2C instance and is sent to all
other R2C instances).

>
>    R2C2->R2C1: {
>        “type” : “add-drone”,
>        “rid”:“<rid>”,
>        “drone-timestamp-ms”: “<msecTimestamp>”,
>        “lat”: “<lat>”,
>        “lng”: ”<lng>”,
>        “distance-from-me” : “<distanceInMeters>”,
>        “ct-rtt-msec” : “<avgCaltopoRttInMsec>”
>    }
>

The response to add-drone if a receiving R2C instance hasn’t yet seen it is a simple ack:
R2C1->R2C2: {“type”:”add-drone-ack”, “rid”:”<remoteId>”}

Or - in a scenario where the receiving R2C saw this drone first based on either the drone’s msec timestamp or if that’s a tie, consider the servers distance to the drone and its ct-rtt respectively to break the tie:
R2C1->R2C2: {“type”:”add-drone-nack”, “rid”:<remoteId>”}

This response tells the ‘add-drone’ sender that the receiver has taken provisional ownership of the drone.   Note that this example just shows R2C2 notifying R2C1, but having noticed the same drone, R2C2 would have sent the same message to R2C1, so messages passing by each other on the information superhighway, though one could be following a speedy terrestrial path and the other might be delayed a bit via a detour over Starlink.   Also note that there might be three or more R2C instances at play here, so for an R2C instance to take reporting ownership for a drone, it needs to get acks back from each of the other R2C instances.

An R2C instance that responds with an add-drone-nack assumes provisional ownership and may begin receiving sighting notifications that pile up in its queue until it is granted ownership.   If it turns out that some other R2C claims ultimate ownership, then the provisional owner will need to respond to any subsequent sighting notifications with the following redirect
R2C1->R2C2: {
“type”:“seen-redirect”,
“owner”: “<owner-uuid>”
}

Sighting - allows an R2C instance to report to another instance whenever it sees one of the instances drones.  Think of this as using an extra pair of eyes over the hill to keep tabs on a drone.  To keep from saturating the network, only one outstanding “seen” at a time.  If multiple waypoints accrue before a corresponding “seen-ack”, they are all batched together in the next seen.:
R2C2->R2C1: {
“type”:”seen”,
“rid”:”<rid>”,
[{
“lat”:”<lat>”,
”lng”:”<lng>”,
”ts”:”<drone-msec-timestamp>”,
}, …],
“ct-rtt” : “<rolling-avg-caltopo-rtt-milliseconds>”,
“r2c-rtt” : “<rolling-avg-rtt-of-prev-seen-msgs>”
}

Where <drone-msec-timestamp> is the timestamp reported by the drone in its RID message.  Response from the server is usually a terse {type=seen-ack}”, but may be a “type”:”seen-redirect” as described in the add-drone scenario described above or a “type”:”seen-handoff” as described below.


This process adds an extra bit of delay, potentially a couple trips up to low earth orbit and back via Starlink, but it would be better to have a clean/accurate track than a hole and it is the simplest way to improve coverage w/o duplication of waypoint updates.

Status - allows an R2C instance to report to other instances with their current suite of owned drones:
R2C2->R2C1: {
“type” : ”drone-status”,
“my-active-dronelist”:[
{
“remoteId”:“<rid1>”,
“mappedId”:”<mappedId1>”,
…
“startTimeInMsec”:”<startTimeInMSec1>”,
“BT4”: “<bt4MsgCount>”,
“BT5”: “<bt5MsgCount>”,
…
}, …
]
}



name-change - allows a peer R2C instance to request that the “owner” of a drone change the name of that drone.   The owner being the R2C instance that is writing tracks into caltopo.
R2C2->R2C1: {
“type” : ”name-change”,
“remoteId”:“<rid>”,
“mappedId”:”<mappedId>”
}

        R2C1->R2C2: {
           “type” : “name-change-ack”,
“remoteId”:“<rid>”,
“mappedId”:”<mappedId>”
}
