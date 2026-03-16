package org.ncssar.rid2caltopo.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object AppConfigSerializer : Serializer<AppConfig> {
    override val defaultValue: AppConfig = AppConfig.newBuilder()
        .setSchemaVersion(AppConfigStore.SCHEMA_VERSION)
        .setMinDistanceFeet(CaltopoClient.MIN_DISTANCE_IN_FEET)
        .setNewTrackDelaySeconds(30)
        .setMaxIdleTimeMinutes(120)
        .setDebugLevel(-1)
        .setCoordinateDisplayFormat("decimal")
        .setCaptureVideoStreams(false)
        .setUsePeers(false)
        .setCaltopoTrackFolder("Drone Tracks")
        .setCaltopoDomainAndPort("caltopo.com")
        .setIncident("Training")
        .setOpPeriod("1")
        .build()

    override suspend fun readFrom(input: InputStream): AppConfig {
        try {
            return AppConfig.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Unable to read app config proto.", e)
        }
    }

    override suspend fun writeTo(t: AppConfig, output: OutputStream) {
        t.writeTo(output)
    }
}
