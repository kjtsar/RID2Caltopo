# RID2Caltopo (Remote ID to Caltopo):  Live Drone Tracking for SAR

This android application sniffs Bluetooth and WiFi networks for [ASTM F3411](https://store.astm.org/f3411-22a.html) - compatible Remote ID location updates and records the 
updates as a sequence of track waypoints that are compatible with [Caltopo](https://www.caltopo.com)'s geo-json file format.   
Additionally, if a valid Caltopo map URL is configured, that the user has write/update permissions for, 
the app can plot real-time LiveTrack updates into the map. 
This functionality closes the previously open loop between search assignments and actual drone coverage.  The real-time updates allow an air-boss to keep tabs on all airborne assets.

[Caltopo](https://www.caltopo.com) is the preferred search management platform for many SAR agencies, including the Nevada County Sheriff's Search And Rescue organization from which this project originates.
This project is very thankful for and leverages the network sniffing and decoding work of the [OpenDroneId](https://github.com/opendroneid/receiver_android) project.

Android phones and tablets have limited sensitivity to the Remote ID signalling.   To make this tool work for SAR applications, where drone search segments can span many
thousands of feet, it is recommended that you pair you app with a [Dronescout Bridge](https://www.gearfocus.com/products/new-bluemark-ds100-dronescout-retail-bridge-faa-remote-id-re-rEBYx)
Just power-up the bridge and raise it up a fair bit to optimize coverage:

<img alt="Dronescout Bridge on antenna mast" src="images/DronescoutBridge.jpg" width="257" height="360">

Please note: The user of this receiver application must always visually verify that the received Open Drone ID signal corresponds to an actual drone seen flying in the air, at the position the signal claims it to be.

The settings menu option allows the user to quickly change options that are likely to vary from one invocation to the next.  Support for
more involved or sensitive configuration information, the app's "load config file" menu option currently supports two .json configuration
file formats:

## ridmap.json:
Use the ridmap file to map remoteIDs to more friendly track labels:
`
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
`

## credentials.json:
Use the credentials file to map specify your team's <a href="https://training.caltopo.com/all_users/team-accounts/teamapi#keysids">Caltopo credentials</a>:
`
    {
    "type" : "ct_credentials",
    "file_version" : "1.0",
    "editor" : "admin <admin@kjt.us>",
    "updated" : "Fri Sep 19 08:07:01 PDT 2025",
    "team_id" : "team_id value",
    "credential_id" : "credential id value",
    "credential_secret" : "this is where you enter your credential secret",
    "map_id" : "AH2JKLM",
    "group_id" : "NCSSAR"
    }
`
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
