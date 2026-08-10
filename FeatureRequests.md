* Jennifer and Harry 11Jun2026: - implemented 12Jun2026
 - Unique colors for drone tracks to differentiate from other drones in the search segment.  Click on drone marker to specify unique active (default:blue) and archive (default: magenta) colors for a drone... Should this be for local only or should the colors propagate to Caltopo?
 - Bearing line to marker.  Click on drone icon and toggle Bearing to On.  This should produce both Bearing degrees text and extend the little bearing stub forward to the edge of the map.


* Ken: Clue snapshot should display USNG coordinates in addition to lat,lng. - implemented 13Jun2026.
* Gus 13Jun2026: Map in Stream, Stream in map for better context awareness - implemented 14Jun2026.
* Gus? Identify assigned search segment and provide audio/visual feedback when drone is outside search segment.

* Ken: In StreamTile view, change "long press to match telemetry" to a chip within the stream label that is displayed within a focused stream as "No Telemetry".  User clicks on the chip to establish telemetry connection.  When connected to a telemetry stream, the chip then displays the stream telemetry.  When user clicks on the stream telemetry chip the reassign telemetry panel pops up.  In both cases, the chip is just a border with transparent (except for contrast font) background. - implemented 13Jun2026

* ??? Several users have requested a full-screen mode for the focused StreamTile or Map Pane (w/optional PiP inset).   I'd like to be able to invoke this mode by single-tapping any open space on the top-bar that currently isn't occupied.  Then, when in full-screen mode, add a chip in the top right corner using contrasting font and transparent background that simply says "Exit FS". - implemented 13Jun2026

* Codex/Ken: Have the map automatically pan to follow the drone location is working very nicely.  It would be a great option to have for the default split pane view as well.   Let's add a "Follow Focused Drone" toggle to the Map Management settings within the map pane.  That toggle would affect the follow behavior both in the full-sized map and the PiP view when a focused drone that is streaming has associated telemetry.- implemented 13Jun2026.



Bug Fixes:
* After clicking on a clue it's pic is displayed, but there doesn't seem to be any way to dismiss the pic. - implemented 13Jun2026.
* Clue waypoints moved from the "Drone Tracks" folder to a folder whose contents are not displayed continue to be displayed on the map.   Tried reloading map with no change. - implemented 14Jun2026

I'd like us to make sure we're increasing the salience score of any ME-contradicting & AD-identified targets that persist over time in the same ME directed location and decrement the score of targets that are transitory.


* Fix DroneItem alignment under R2CView header. implemented 16Jun2026
* ReleaseNotes menu item not displaying current release notes. implemented 16Jun2026
