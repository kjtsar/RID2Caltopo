package org.ncssar.rid2caltopo.data

import android.app.backup.BackupAgentHelper
import android.app.backup.FileBackupHelper

class AppConfigBackupAgent : BackupAgentHelper() {
    override fun onCreate() {
        addHelper(
            APP_CONFIG_HELPER_KEY,
            FileBackupHelper(this, "datastore/app_config.pb")
        )
    }

    companion object {
        private const val APP_CONFIG_HELPER_KEY = "app_config"
    }
}
