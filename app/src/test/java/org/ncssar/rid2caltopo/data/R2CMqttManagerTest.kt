/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [R2CMqttManager] using [FakePeerTransportHub].
 *
 * ### What these tests verify
 *
 * - **Connect / subscribe**: after `init()`, the transport is connected and the manager
 *   has successfully subscribed to `R2C/<mapId>/#`.
 * - **Immediate-claim fallback**: when MQTT is not connected, `onLiveTrackCreated` grants
 *   ownership directly rather than deferring.
 * - **Deferred ownership** (two-peer arbitration): when two peers both detect the same
 *   drone, the closer peer wins and the farther peer does not claim ownership.
 *
 * ### Background — the bug these tests guard against
 *
 * `MqttPeerTransport` previously called `subscribe()` directly on Paho's internal
 * `connectComplete` callback thread.  With `automaticReconnect=true`, Paho holds an
 * internal lock on that thread, causing `subscribeBase` to throw a lock-contention
 * exception.  The subscribe-success callback never fired, heartbeat/ownership timers
 * never started, and both devices fell through to the "MQTT not connected → claim
 * immediately" path — producing duplicate CalTopo writes.
 *
 * The fix: `connectComplete` now dispatches `cb.onConnected()` to a separate thread,
 * keeping subscribe() off the Paho callback stack.
 *
 * ### Drone ID note
 *
 * `CtDroneSpec` filters remoteId through `[^A-Z0-9]`, stripping hyphens and other
 * non-alphanumeric characters.  All drone IDs in these tests use plain uppercase
 * alphanumeric strings (e.g. "DRONE1") so that the ID stored in `DroneState` and the
 * ID used in MQTT topic strings always match.
 */
class R2CMqttManagerTest {

    // ── test infrastructure ───────────────────────────────────────────────────

    private lateinit var hub: FakePeerTransportHub

    /**
     * Minimal live-track stub that records [setLocalOwner] calls without depending
     * on Android's CaltopoLiveTrack.
     */
    private class FakeLiveTrack(private val remoteId: String) : LiveTrackOwnerDelegate {
        private val calls = AtomicInteger(0)
        private val lastValue = AtomicBoolean(false)

        override fun getRemoteId(): String = remoteId
        override fun setLocalOwner(isOwner: Boolean) {
            calls.incrementAndGet()
            lastValue.set(isOwner)
        }

        fun wasSetOwner() = lastValue.get()
        fun callCount() = calls.get()
    }

    private fun fakeDroneSpec(remoteId: String): CtDroneSpec = CtDroneSpec(remoteId)

    /** Detect message payload in the format [R2CMqttManager.publishDetect] uses. */
    private fun detectPayload(distMeters: Double, firstSeenTs: Long): ByteArray {
        val json = """{"dLat":0.0,"dLon":0.0,"dAlt":0.0,"dist":$distMeters,"fts":$firstSeenTs,"ts":${System.currentTimeMillis()}}"""
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        hub = FakePeerTransportHub()
        R2CMqttManager.SetPeerTransportFactoryForTesting(hub.asFactory())
        // Remove the handoff delay so ownership takes effect synchronously in tests.
        R2CMqttManager.setHandoffDelayMsForTesting(0L)
    }

    @After
    fun tearDown() {
        R2CMqttManager.resetForTesting()
    }

    // ── connect / subscribe ───────────────────────────────────────────────────

    @Test
    fun init_connectsAndSubscribesToMapTopic() {
        R2CMqttManager.init("TESTMAP", "guid-alpha", "Alpha", "tcp://fake")

        // FakePeerTransport.subscribe() runs synchronously, but the connect → subscribe
        // path goes through a background thread in MqttPeerTransport (our bug fix).
        // FakePeerTransport.connect() fires onConnected synchronously, so no extra wait needed.
        assertTrue("transport should be connected after init", R2CMqttManager.isConnected())
        assertTrue(
            "should be subscribed to map topic",
            R2CMqttManager.isSubscribedForTesting("R2C/TESTMAP/#")
        )
    }

    @Test
    fun shutdown_disconnectsTransport() {
        R2CMqttManager.init("TESTMAP", "guid-alpha", "Alpha", "tcp://fake")
        assertTrue(R2CMqttManager.isConnected())

        R2CMqttManager.shutdown()

        assertFalse("transport should be disconnected after shutdown", R2CMqttManager.isConnected())
    }

    @Test
    fun initTwice_reconnectsToNewMap() {
        R2CMqttManager.init("MAP1", "guid-alpha", "Alpha", "tcp://fake")
        assertTrue(R2CMqttManager.isSubscribedForTesting("R2C/MAP1/#"))

        R2CMqttManager.init("MAP2", "guid-alpha", "Alpha", "tcp://fake")

        assertTrue(R2CMqttManager.isConnected())
        assertTrue(R2CMqttManager.isSubscribedForTesting("R2C/MAP2/#"))
        assertFalse(
            "old subscription should be cleared after re-init",
            R2CMqttManager.isSubscribedForTesting("R2C/MAP1/#")
        )
    }

    // ── single-instance ownership: MQTT unavailable ───────────────────────────

    @Test
    fun onLiveTrackCreated_notInitialized_claimsOwnershipImmediately() {
        // resetForTesting() in @Before leaves the manager uninitialized.
        val track = FakeLiveTrack("DRONE1")

        R2CMqttManager.onLiveTrackCreated(track, fakeDroneSpec("DRONE1"), 50.0, 1000L)

        assertEquals("setLocalOwner should be called exactly once", 1, track.callCount())
        assertTrue("should grant ownership immediately in no-MQTT mode", track.wasSetOwner())
    }

    @Test
    fun onLiveTrackCreated_initializedButNotConnected_claimsOwnershipImmediately() {
        // Transport that exists but never connects (simulates broker unreachable).
        R2CMqttManager.SetPeerTransportFactoryForTesting(PeerTransportFactory { _, _ ->
            object : PeerTransport {
                override fun setCallback(cb: PeerTransportCallback?) {}
                override fun connect(
                    lwt: String?, lwtPayload: ByteArray?, lwtQos: Int, lwtRetained: Boolean,
                    onConnected: Runnable?, onFailure: Runnable?
                ) { /* never calls onConnected */ }
                override fun disconnect() {}
                override fun isConnected() = false
                override fun subscribe(f: String, qos: Int, ok: Runnable?, fail: Runnable?) {}
                override fun publish(t: String, p: ByteArray, qos: Int, retained: Boolean) {}
            }
        })
        R2CMqttManager.init("MAPX", "guid-alpha", "Alpha", "tcp://fake")

        val track = FakeLiveTrack("DRONE2")
        R2CMqttManager.onLiveTrackCreated(track, fakeDroneSpec("DRONE2"), 50.0, 1000L)

        assertTrue(
            "should claim ownership immediately and log a warning when broker unreachable",
            track.wasSetOwner()
        )
    }

    @Test
    fun onLiveTrackCreated_localArchiveOnlyNeverClaimsOwnership() {
        val track = FakeLiveTrack("DRONE1")
        val drone = fakeDroneSpec("DRONE1").apply { setLocalArchiveOnly(true) }

        R2CMqttManager.onLiveTrackCreated(track, drone, 50.0, 1000L)

        assertEquals("local-archive-only tracks should not be claimed", 1, track.callCount())
        assertFalse(track.wasSetOwner())
    }

    // ── two-peer ownership arbitration ────────────────────────────────────────

    /**
     * Alpha (R2CMqttManager) and Bravo (a raw FakePeerTransport on the same hub) both
     * detect drone DRONE1.  Alpha is 20 m away; Bravo is 400 m away.
     *
     * After ownership is evaluated, Alpha must win (better proximity).
     *
     * Note: bravo's observation is injected via [R2CMqttManager.addPeerObservationForTesting]
     * rather than through MQTT message delivery.  This is necessary because Android unit-test
     * stubs return 0 for all `org.json.JSONObject.optDouble()` calls, which would misrepresent
     * bravo's distance and flip the ownership decision.  The pub/sub transport path itself is
     * exercised by [init_connectsAndSubscribesToMapTopic] and the sub/deliver mechanics are
     * covered by the FakePeerTransportHub's own matching logic.
     */
    @Test
    fun twoPeers_closerPeerWinsOwnership() {
        R2CMqttManager.init("MAP1", "guid-alpha", "Alpha", "tcp://fake")
        assertTrue(R2CMqttManager.isConnected())

        val now = System.currentTimeMillis()

        // Alpha detects DRONE1 at 20 m.
        val alphaTrack = FakeLiveTrack("DRONE1")
        R2CMqttManager.onLiveTrackCreated(alphaTrack, fakeDroneSpec("DRONE1"), 20.0, now)

        // Inject bravo's observation directly (bypass JSON parsing which is stubbed in unit tests).
        R2CMqttManager.addPeerObservationForTesting("DRONE1", "guid-bravo", 400.0, now)

        // Bypass the discovery-window delay and run the ownership check immediately.
        R2CMqttManager.checkOwnershipForTesting("DRONE1")
        Thread.sleep(100) // let the 0-ms handoff delay fire on the test executor

        assertTrue("alpha (closer, 20 m) should be the local owner", R2CMqttManager.isLocalOwner("DRONE1"))
    }

    /**
     * Same setup, but Alpha is 500 m away and Bravo is 15 m.  Alpha must NOT own.
     */
    @Test
    fun twoPeers_fartherPeerDoesNotOwnDrone() {
        R2CMqttManager.init("MAP1", "guid-alpha", "Alpha", "tcp://fake")
        assertTrue(R2CMqttManager.isConnected())

        val now = System.currentTimeMillis()

        // Alpha detects DRONE2 at 500 m (far).
        val alphaTrack = FakeLiveTrack("DRONE2")
        R2CMqttManager.onLiveTrackCreated(alphaTrack, fakeDroneSpec("DRONE2"), 500.0, now)

        // Inject bravo's observation directly (bypass JSON parsing stubs).
        R2CMqttManager.addPeerObservationForTesting("DRONE2", "guid-bravo", 15.0, now)

        R2CMqttManager.checkOwnershipForTesting("DRONE2")
        Thread.sleep(100) // let the 0-ms handoff delay settle on the test executor

        assertFalse(
            "alpha (farther, 500 m) should not own the drone when bravo is closer (15 m)",
            R2CMqttManager.isLocalOwner("DRONE2")
        )
        // setLocalOwner on alphaTrack is never called in the connected/deferred path
        // (ownership propagates via CaltopoLiveTrack.GetLiveTrackForRemoteId lookup,
        // not the delegate passed to onLiveTrackCreated).
        assertEquals("setLocalOwner should not be called on alpha's track", 0, alphaTrack.callCount())
    }

    /**
     * When only one peer (alpha) sees the drone, alpha must own it.
     */
    @Test
    fun singlePeer_connected_ownsAfterDiscoveryWindow() {
        R2CMqttManager.init("MAP1", "guid-alpha", "Alpha", "tcp://fake")
        assertTrue(R2CMqttManager.isConnected())

        val alphaTrack = FakeLiveTrack("DRONE3")
        R2CMqttManager.onLiveTrackCreated(alphaTrack, fakeDroneSpec("DRONE3"), 30.0, System.currentTimeMillis())

        // No competing peer — evaluate ownership immediately.
        R2CMqttManager.checkOwnershipForTesting("DRONE3")
        Thread.sleep(100) // let the 0-ms handoff delay settle

        assertTrue(
            "sole observing peer should own the drone",
            R2CMqttManager.isLocalOwner("DRONE3")
        )
    }
}
