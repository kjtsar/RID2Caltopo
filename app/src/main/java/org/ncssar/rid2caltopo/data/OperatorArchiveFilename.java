/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared Android counterpart to Apple's operator archive filename contract. */
public final class OperatorArchiveFilename {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("ddMMMyyyy-HHmmss-zZ", Locale.US);

    private OperatorArchiveFilename() {}

    @NonNull
    public static String timestamp(long epochMilliseconds) {
        return timestamp(epochMilliseconds, ZoneId.systemDefault());
    }

    @NonNull
    static String timestamp(long epochMilliseconds, @NonNull ZoneId zoneId) {
        return Instant.ofEpochMilli(epochMilliseconds)
                .atZone(zoneId)
                .format(TIMESTAMP_FORMATTER);
    }

    @NonNull
    public static String log(long epochMilliseconds) {
        return log(epochMilliseconds, ZoneId.systemDefault());
    }

    @NonNull
    static String log(long epochMilliseconds, @NonNull ZoneId zoneId) {
        return "Log_" + timestamp(epochMilliseconds, zoneId) + ".txt";
    }

    @NonNull
    public static String track(
            @NonNull String aircraftID,
            long epochMilliseconds
    ) {
        return track(aircraftID, epochMilliseconds, ZoneId.systemDefault());
    }

    @NonNull
    static String track(
            @NonNull String aircraftID,
            long epochMilliseconds,
            @NonNull ZoneId zoneId
    ) {
        return base(aircraftID, epochMilliseconds, zoneId) + ".json";
    }

    @NonNull
    public static String clueReport(
            @NonNull String aircraftID,
            long epochMilliseconds
    ) {
        return clueReport(aircraftID, epochMilliseconds, ZoneId.systemDefault());
    }

    @NonNull
    static String clueReport(
            @NonNull String aircraftID,
            long epochMilliseconds,
            @NonNull ZoneId zoneId
    ) {
        return base(aircraftID, epochMilliseconds, zoneId) + ".kmz";
    }

    @NonNull
    static String base(
            @NonNull String aircraftID,
            long epochMilliseconds,
            @NonNull ZoneId zoneId
    ) {
        return aircraftID + "-" + timestamp(epochMilliseconds, zoneId);
    }
}
