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
            "RID2Caltopo is provided on a best-effort, \"as is,\" and \"as available\" basis, " +
                "with no express or implied warranties or guarantees, including merchantability, " +
                "fitness for a particular purpose, non-infringement, suitability, reliability, " +
                "availability, accuracy, or completeness. Features and information may be unavailable, " +
                "inaccurate, incomplete, or delayed. This app provides supplemental situational awareness " +
                "only and must not be used as the sole source for navigation, flight safety, communications, " +
                "or incident-command decisions. RID2Caltopo is an independent project and is not affiliated " +
                "with or endorsed by CalTopo. It uses the CalTopo Teams API. The RID2Caltopo developer thanks " +
                "the CalTopo team for its excellent product and support of the Teams API. By selecting I agree, " +
                "I accept responsibility for safe use " +
                "and for independently verifying safety-critical information.",
            LAUNCH_DISCLAIMER_TEXT,
        )
    }
}
