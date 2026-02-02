package org.ncssar.rid2caltopo.data

sealed class DesignatorState {
    data class Green(
        val dronespec: CtDroneSpec
    ) : DesignatorState()

    data class Yellow(
        val candidates: List<CtDroneSpec>
    ) : DesignatorState()

    object Red : DesignatorState()
}
