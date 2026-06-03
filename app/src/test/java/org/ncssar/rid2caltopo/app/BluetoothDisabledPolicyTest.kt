package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDisabledPolicyTest {
    @Test
    fun disabledAdapterShowsBluetoothDisabledPanel() {
        assertTrue(
            shouldShowBluetoothDisabledPanel(
                adapterPresent = true,
                bluetoothEnabled = false
            )
        )
    }

    @Test
    fun enabledAdapterDoesNotShowBluetoothDisabledPanel() {
        assertFalse(
            shouldShowBluetoothDisabledPanel(
                adapterPresent = true,
                bluetoothEnabled = true
            )
        )
    }

    @Test
    fun missingAdapterDoesNotUseBluetoothDisabledPanel() {
        assertFalse(
            shouldShowBluetoothDisabledPanel(
                adapterPresent = false,
                bluetoothEnabled = false
            )
        )
    }
}
