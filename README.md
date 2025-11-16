# RID2Caltopo (Remote ID to Caltopo):  Live Drone Tracking for SAR

This android application sniffs Bluetooth and WiFi networks for [ASTM F3411](https://store.astm.org/f3411-22a.html) - compatible Remote ID location updates and records the 
updates as a sequence of track waypoints that are compatible with [Caltopo](https://www.caltopo.com)'s geo-json file format.   
Additionally, if a you have configured caltopo teams credentials properly and your mapid points to an existing map that the credentials have write/update permissions for, 
the app can plot real-time LiveTrack updates into the map. 
This functionality closes the previously open loop between search assignments and actual drone coverage.  
The real-time updates allow an air-boss to keep tabs on all airborne assets.

[Caltopo](https://www.caltopo.com) is the preferred search management platform for many SAR agencies, including the Nevada County Sheriff's Search And Rescue organization from which this project originates.
This project is very thankful for and leverages the network sniffing and decoding work of the [OpenDroneId](https://github.com/opendroneid/receiver_android) project.

Android phones and tablets have limited sensitivity to the Remote ID signalling.   To make this tool work for SAR applications, where drone search segments can span many
thousands of feet, it is recommended that you pair the app with a [Dronescout Bridge](https://www.gearfocus.com/products/new-bluemark-ds100-dronescout-retail-bridge-faa-remote-id-re-rEBYx)
Just power-up the bridge and raise it up a fair bit to optimize coverage:

<img alt="Dronescout Bridge on antenna mast" src="images/DronescoutBridge.jpg" width="257" height="360">

Please note: The user of this application must always visually verify that the received Open Drone ID signal corresponds to an actual drone seen flying in the air, at the position the signal claims it to be.

The settings menu option allows the user to quickly change options that are likely to vary from one invocation to the next.  Support for
more involved or sensitive configuration information is provided by the app's "load config file" menu option, which currently supports two .json configuration
file formats:

## ridmap.json:
Use the ridmap configuration file format to map remoteIDs to more friendly track labels:
<blockquote><code>
{
    "type" : "ct_ridmap",
    "file_version" : "1.0",
    "editor" : "admin <admin@kjt.us>",
    "updated" : "Wed Sep 17 12:42:41 PDT 2025",
    "map" : [
        {
            "remoteId" : "1581F6Z9C24BH0036EJL",
            "mappedId" : "1SAR7mm4p",
            "org" : "NCSSAR",
            "owner" : "NCSSAR",
            "model" : "Mavic Mini 4 Pro"
	    },
        {
            "remoteId" : "1581F67QE239L00A00DE",
            "mappedId" : "1SAR7m3p",
            "org" : "NCSSAR",
            "owner" : "NCSSAR",
            "model" : "Mavic 3 Pro"
        },
    ]
}
</code></blockquote>


## credentials.json:
Use the credentials configuration file format to specify your team's map information and <a href="https://training.caltopo.com/all_users/team-accounts/teamapi#keysids">Caltopo credentials</a>:

<blockquote><code>
{
    "type" : "ct_credentials",
    "file_version" : "1.0",
    "editor" : "admin <admin@kjt.us>",
    "updated" : "Fri Sep 19 08:07:01 PDT 2025",
    "team_id" : "team_id value",
    "credential_id" : "credential id value",
    "credential_secret" : "this is where you enter your credential secret",
    "map_id" : "AH2JKLM",
    "group_id" : "NCSSAR",
    "track_folder" : "DroneTracks"
}
</code></blockquote>


## Support for multiple apps writing to same map at the same time:
You’ll need to run ZeroTier on all your devices if they aren’t already connected to the same subnet.   
To do this, log in to ZeroTier.com and create a free account, then create a public network (I called 
mine RID2Caltopo oddly enough) and copy the network ID. Then download the free ZeroTier app from 
playstore and connect to the network ID copied above.   If you keep the network private, you’ll need 
to approve each device connecting to the network, so recommend making the network public.  After that, 
you just need to make sure that the ZeroTier app is up and running and connected to your network prior 
to starting RID2Caltopo.

## How to build
To build the application, use Android Studio.
Import the project (File -> New -> Import Project, or just Open on newer versions of Android Studio) and point to the root folder.
Then Build -> Make Project.

## Supported interfaces and protocols
Bluetooth 4 (legacy bluetooth), Bluetooth 5 (long range/coded phy), WiFi Beacon, and WiFi NaN are all 
supported by the app. While bluetooth 4 seems to be universally supported, some phones/tablets may not 
support one or more of the other capabilities.

## High level SW Architecture
A KenDraw(tm) view of the class structure can be seen in the figure below:

![T.B.S.](images/RID2Caltopo.png)
