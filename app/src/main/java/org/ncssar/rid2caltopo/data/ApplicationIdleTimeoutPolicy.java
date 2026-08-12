/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data;

import java.util.concurrent.TimeUnit;

public final class ApplicationIdleTimeoutPolicy {
    public static final long DISABLED = -1L;

    private ApplicationIdleTimeoutPolicy() {}

    public static long remainingDelayMsec(
            long appStartedAtMsec,
            long lastRidMessageAtMsec,
            long maximumIdleMinutes,
            long nowMsec) {
        if (maximumIdleMinutes <= 0) return DISABLED;

        long timeoutMsec = TimeUnit.MINUTES.toMillis(maximumIdleMinutes);
        long baselineMsec = Math.max(appStartedAtMsec, lastRidMessageAtMsec);
        long elapsedMsec = Math.max(0L, nowMsec - baselineMsec);
        return Math.max(0L, timeoutMsec - elapsedMsec);
    }
}
