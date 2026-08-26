/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchDisclaimerTest {
    @Test
    fun disclaimerUsesApprovedSafetyLanguage() {
        var hash = 14695981039346656037uL
        LAUNCH_DISCLAIMER_TEXT.encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * 1099511628211uL
        }

        assertEquals(0xf4ee5506e5afb21auL, hash)
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("accept full responsibility"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("hold harmless Ken Taylor"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("California Civil Code section 1542"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("expressly waive all rights and benefits"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("unknown or unsuspected"))
    }
}
