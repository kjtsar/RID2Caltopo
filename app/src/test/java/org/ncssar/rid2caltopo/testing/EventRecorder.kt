package org.ncssar.rid2caltopo.testing

class EventRecorder<T> {
    private val _events = mutableListOf<T>()
    val events: List<T> get() = _events

    fun record(event: T) {
        _events.add(event)
    }
}
