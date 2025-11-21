
##RID2Caltopo Privacy Policy##

If the user never configures the app to connect to a caltopo map, their device location
is never used or shared in any way.

If the user configures the app to connect to a Caltopo map via their 
[Teams Account](https://training.caltopo.com/all_users/team-accounts/teamapi), this app will collect 
the device's location and place a marker on the map representing the location of this device.   
The device location is not saved or published anywhere else.

Internal to the Caltopo map implementation, the marker placed in Caltopo contains the device's 
[UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier) to ensure that only one marker 
is active for each device from one invocation of the app to the next. The only way to read the 
UUID is with the Caltopo Teams API using their secure credentials.  Upon a normal exit, the 
app will attempt to remove it's marker from the map.  For network connectivity problems or other
scenarios where the app doesn't get to perform this clean-up, just restart the app when network
connectivity returns, connect to the map, then quit the app.  Alternatively, go into the Caltopo
app directly and remove the RID2Caltopo marker.

The Caltopo Teams credentials are entered by loading from a .json file (see the apps 
[README.md](https://github.com/kjtsar/RID2Caltopo/blob/main/README.md) file which is referenced 
within the application's "Help" menu item).  After loading, the credentials and other app 
configuration parameters are saved locally to the app's filesystem which is encrypted and saved 
by the android account backup service.

If the user ever wants to remove/reset this information they can delete the applications
data (under App settings) or simply uninstall the app.