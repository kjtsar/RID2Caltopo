package org.opendroneid.android.bluetooth

import java.util.Locale

/** Metadata inserted by DroneScout Bridge into a relayed aircraft's Self ID message. */
data class DroneScoutRelayMetadata(
    val droneToBridgeRssiDbm: Int,
    val receptionMode: String,
    val sourceKind: String?,
) {
    companion object {
        private val relayPattern = Regex(
            pattern = "(?:^|\\s)DS\\s+(WIFI\\s+B|BT5|WIB)\\s+(-?\\d{1,3})(?:\\s*dBm)?(?:\\s+(drone|addon|grounded))?",
            option = RegexOption.IGNORE_CASE,
        )

        @JvmStatic
        fun parse(description: String?): DroneScoutRelayMetadata? {
            val match = relayPattern.find(description?.trim().orEmpty()) ?: return null
            val rssi = match.groupValues[2].toIntOrNull()?.takeIf { it in -127..-1 }
                ?: return null
            return DroneScoutRelayMetadata(
                droneToBridgeRssiDbm = rssi,
                receptionMode = match.groupValues[1]
                    .replace(Regex("\\s+"), " ")
                    .uppercase(Locale.US),
                sourceKind = match.groupValues[3]
                    .takeIf(String::isNotEmpty)
                    ?.lowercase(Locale.US),
            )
        }
    }
}
