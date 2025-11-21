If the user never configures the app to connect to a caltopo map, their device location
is never used or shared in any way.

If the user configures the app to connect to a Caltopo map via their [Teams Account]
(https://training.caltopo.com/all_users/team-accounts/teamapi),
this app will collect the device's location and place a marker on the map representing the
location of this device.   The device location is not saved or published anywhere else.
The marker placed in Caltopo contains the device's UUID to ensure that only one marker is 
active for each device from one invocation of the app to the next.

The Caltopo Teams credentials are entered by loading from a .json file (see the apps [README.md]
(https://github.com/kjtsar/RID2Caltopo/blob/main/README.md) file which is referenced within the 
application's "Help" menu item).  After loading, the credentials and other app configuration 
parameters are saved locally to the app's filesystem which is encrypted and backed-up by the
android account service.

If the user ever wants to remove/reset this information they can delete the applications
data (under App settings) or simply uninstall the app.