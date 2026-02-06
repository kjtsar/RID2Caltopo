# RID2Caltopo: Implementation Overview

## [R2CActivity.kt](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt)

This module makes sure all required permissions have been requested/granted 
and implements the uppermost U/I functionality which is rendered by [MainScreen.kt](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt)
It then fires-up the ScanningService module to begin collecting Remote ID broadcasts.
Additionally, it captures the app exit request to cleanly shut down the scanning 
service and remove any caltopo markers and archive any tracks that were active. 

## [ScanningService.java](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/app/ScanningService.java)
This module implements the low level scanning service that continues to run in the 
background any time that the app loses focus or the screensaver is activated.  Most 
of this functionality is a direct copy from the OpenDroneId folks’ receiver_android 
project and is now located in this projects [org/opendroneid/android](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/opendroneid/android) 
subdirectory.  The linkage between the scanning service and the rest of the 
rid2caltopo app is located in the [OpenDroneIdDataManager](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/opendroneid/android/bluetooth/OpenDroneIdDataManager.java).updateCaltopo() method.

## [CaltopoClient.java](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoClient.java)
This module maintains app configuration settings as well as a cache of 
CaltopoClients, each of which maps Remote ID strings to more palatable descriptions
of the drone that the Remote ID references.    OpenDroneIdDataManager requests a 
CaltopoClient instance for each incoming Remote ID and sends it a waypoint via its
newWaypoint() method.  The client's CtDroneSpec instance (droneSpec) confirms the
waypoint's coordinates and altitude are valid, then checks the distance and time
since the last waypoint and decides if the waypoint is worth keeping.  If the
waypoint is worth keeping, the client archives the waypoint locally and checks
the app configuration. 

If _useDirectFlag_ isn't set, the assumption is that the user has configured a LiveTrack 
within caltopo and the waypoint is forwarded to Caltopo as a live track with <GroupId>-<DeviceId> 
set to "DRONE-<remoteId>".

If mapId is not empty and _useDirectFlag_ is set, then a map connection
is started and a CaltopoLiveTrack is created to handle the archival of the waypoint
to Caltopo.

## [CaltopoMap.java](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoMap.java)
The creation of a CaltopoMap instance is a bit of a process.   The underlying [CaltopoSession](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoSession.java)
uses the [Caltopo Teams API](https://training.caltopo.com/all_users/team-accounts/teamapi) to 
send asynchronous messages to a Caltopo server and the map must wait for asynchronous 
responses before proceeding.   It checks the list of features returned by the openMap() 
request for our _Drone Tracks_ directory, and creates it if missing.  It then creates a Marker 
for this instance of the app and if _ignoreR2CPeers_ is false, it fires-up an [R2CPeer](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/R2CPeer.java) 
server instance and checks the same directory to see if any peer R2C markers are present.
Finding any R2C peer markers, the CaltopoMap instantiates R2CPeers that attempt to make 
contact with those peers.  After all peer connections are initiated, the map connection 
is deemed to be up.

## [CaltopoLiveTrack.java](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoLiveTrack.java)
When the CaltopoClient is configured to connect to a map and a corresponding CaltopoMap
has been opened, each CaltopoClient then reports its drone's incoming waypoints to a corresponding
CaltopoLiveTrack.   The LiveTrack accumulates the waypoints in a queue and then works with
the R2CPeer class to find out if any other Peer has already claimed ownership over the 
drone's waypoints.   If not, then this LiveTrack waits for it's map connection to be
active before it starts forwarding waypoints on to the Caltopo Map.  The first step of
which is to Create a LiveTrack in caltopo, then forward all subsequent waypoints to that
livetrack.

## [R2CPeer.java](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/R2CPeer.java)
R2CPeer instances are used to connect multiple RID2Caltopo applications together to 
expand the effective drone detection range, enabling coverage in large or mountainous
search assignments. 
Each R2CPeer instance uses websockets, encapsulated in the [WsPipe](https://github.com/kjtsar/RID2Caltopo/blob/main/app/src/main/java/org/ncssar/rid2caltopo/data/WsPipe.java)
class to communicate with peer RID2Caltopo instances.   The Rest-like protocol used between R2CPeer
instances is used to coordinate reporting ownership and share statistics about drones.
The process starts with a simple hello/ack, where an existing R2CPeer shares it's list
of drones with a new R2CPeer.  After that, anytime a new drone is detected, a brief
arbitration handshake is used to determine which R2C instance is closest to the drone
and is granted ownership for the duration of the drone's flight.   Non-owner drones
then provide location updates to the owner to effectively extend it's line of sight.

