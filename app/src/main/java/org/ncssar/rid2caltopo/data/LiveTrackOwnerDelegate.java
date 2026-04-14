/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

/**
 * Minimal seam used by {@link R2CMqttManager} to notify a live track of ownership changes.
 *
 * <p>Production code: {@link CaltopoLiveTrack} implements this interface.
 * Test code: a simple lambda-style fake can implement it without depending on Android.</p>
 */
public interface LiveTrackOwnerDelegate {
    /** The remote-ID of the drone this track represents. */
    @NonNull String getRemoteId();

    /** Called when MQTT arbitration has determined whether this instance owns the drone. */
    void setLocalOwner(boolean isOwner);
}
