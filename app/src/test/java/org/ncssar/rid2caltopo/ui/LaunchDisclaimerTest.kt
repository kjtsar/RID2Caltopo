/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchDisclaimerTest {
    @Test
    fun disclaimerUsesApprovedSafetyLanguage() {
        assertEquals(
            "This app provides supplemental situational awareness that may be unavailable or " +
                "contain incomplete or delayed information and must not be used as the sole source " +
                "for navigation, flight safety, communications, or incident-command decisions.  " +
                "I am responsible for safe use and independently verifying safety-critical information.",
            LAUNCH_DISCLAIMER_TEXT,
        )
    }
}
