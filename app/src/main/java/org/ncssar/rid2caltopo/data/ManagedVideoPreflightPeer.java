package org.ncssar.rid2caltopo.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-shot WebRTC data-channel peer for the managed-video link estimate.
 *
 * This class deliberately creates no audio or video tracks. Session
 * descriptions are supplied by the organization-scoped tracker exchange.
 */
final class ManagedVideoPreflightPeer implements AutoCloseable {
    private static final String TAG = "ManagedVideoPeer";
    interface Sink {
        void sendAnswer(@NonNull String requestId, @NonNull String sdp);
        void sendResult(
                @NonNull String requestId,
                @NonNull String routeKind,
                long estimatedUplinkBps);
        void onFailure(@NonNull String requestId, @NonNull String reason);
    }

    private static final int CHUNK_BYTES = 16 * 1024;
    private static final long MAX_BUFFERED_BYTES = 2L * 1024L * 1024L;
    private static final long PROBE_DURATION_MS = 4_000L;
    private static final long PROBE_WARMUP_MS = 500L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    private static final long PARTIAL_ICE_ANSWER_DELAY_MS = 2_500L;
    private static final double SAFETY_MARGIN = 0.75;
    @NonNull private final Sink sink;
    @NonNull private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    @Nullable private PeerConnectionFactory factory;
    @Nullable private PeerConnection peer;
    @Nullable private DataChannel channel;
    @NonNull private String requestId = "";
    @NonNull private String activeOfferSdp = "";
    @NonNull private final List<IceCandidate> localIceCandidates = new ArrayList<>();
    @NonNull private final AtomicLong lifecycleGeneration = new AtomicLong();
    private long probeStartedAtMs;
    private int nextSequence;
    @NonNull private final AtomicLong acknowledgedBytes = new AtomicLong();
    @NonNull private final AtomicLong acknowledgedBytesAfterWarmup = new AtomicLong();
    private final AtomicBoolean resultSent = new AtomicBoolean();
    private final AtomicBoolean probeStarted = new AtomicBoolean();
    private volatile boolean answerSent;

    ManagedVideoPreflightPeer(@NonNull Sink sink) {
        this.sink = sink;
    }

    synchronized void start(
            @NonNull Context context,
            @NonNull String nextRequestId,
            @NonNull String offerSdp,
            @Nullable JSONArray iceServers) {
        String normalizedOfferSdp = ManagedVideoSdp.normalizeRemoteOffer(offerSdp);
        if (peer != null
                && requestId.equals(nextRequestId)
                && activeOfferSdp.equals(normalizedOfferSdp)) {
            return;
        }
        retireActivePeerLocked();
        long generation = lifecycleGeneration.incrementAndGet();
        requestId = nextRequestId;
        activeOfferSdp = normalizedOfferSdp;
        answerSent = false;
        resultSent.set(false);
        probeStarted.set(false);
        acknowledgedBytes.set(0L);
        acknowledgedBytesAfterWarmup.set(0L);
        nextSequence = 0;
        localIceCandidates.clear();
        try {
            initializeFactory(context.getApplicationContext());
            List<PeerConnection.IceServer> servers = parseIceServers(iceServers);
            PeerConnection.RTCConfiguration configuration =
                    new PeerConnection.RTCConfiguration(servers);
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            // The browser offer is TURN-only. Allow Android to contribute any
            // reachable candidate; the selected pair still traverses the
            // browser relay, so the bandwidth probe remains a routed test.
            configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;
            peer = factory.createPeerConnection(
                    configuration,
                    new PeerObserver(generation));
            if (peer == null) {
                fail(generation, "Unable to create WebRTC peer.");
                return;
            }
            peer.setRemoteDescription(
                    new DescriptionObserver(generation, "set remote offer") {
                        @Override
                        void onSuccessValue() {
                            createAnswer(generation);
                        }
                    },
                    new SessionDescription(
                            SessionDescription.Type.OFFER,
                            normalizedOfferSdp));
            executor.schedule(
                    () -> failIfConnectionDidNotOpen(generation),
                    CONNECT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            CaltopoClient.CTError(TAG, "Unable to start WebRTC preflight.", exception);
            fail(generation, "Unable to start WebRTC preflight.");
        }
    }

    private void initializeFactory(@NonNull Context context) {
        ManagedWebRtcRuntime.initialize(context);
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    }

    private void createAnswer(long generation) {
        if (generation != lifecycleGeneration.get()) return;
        PeerConnection activePeer = peer;
        if (activePeer == null) return;
        activePeer.createAnswer(
                new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription description) {
                        if (generation != lifecycleGeneration.get()) return;
                        PeerConnection currentPeer = peer;
                        if (currentPeer == null) return;
                        currentPeer.setLocalDescription(
                                new DescriptionObserver(generation, "set local answer") {
                                    @Override
                                    void onSuccessValue() {
                                        maybeSendAnswer(generation, false);
                                        try {
                                            executor.schedule(
                                                    () -> maybeSendAnswer(generation, true),
                                                    PARTIAL_ICE_ANSWER_DELAY_MS,
                                                    TimeUnit.MILLISECONDS);
                                        } catch (RejectedExecutionException ignored) {
                                            // The request was retired while ICE was gathering.
                                        }
                                    }
                                },
                                description);
                    }

                    @Override public void onSetSuccess() { }
                    @Override public void onCreateFailure(String error) {
                        deferFailure(generation, "Unable to create WebRTC answer.");
                    }
                    @Override public void onSetFailure(String error) {
                        deferFailure(generation, "Unable to create WebRTC answer.");
                    }
                },
                new org.webrtc.MediaConstraints());
    }

    private synchronized void maybeSendAnswer(long generation, boolean allowPartialGathering) {
        if (generation != lifecycleGeneration.get()) return;
        PeerConnection activePeer = peer;
        if (
                answerSent
                || activePeer == null
                || (!allowPartialGathering && activePeer.iceGatheringState()
                != PeerConnection.IceGatheringState.COMPLETE)
        ) {
            return;
        }
        SessionDescription description = activePeer.getLocalDescription();
        if (description == null || description.description.trim().isEmpty()) {
            deferFailure(generation, "WebRTC answer was empty.");
            return;
        }
        String completeDescription = ManagedVideoSdp.withIceCandidates(
                description.description,
                new ArrayList<>(localIceCandidates));
        boolean hasRoutableCandidate =
                ManagedVideoSdp.hasRoutableIceCandidate(completeDescription);
        if (!completeDescription.contains("a=candidate:") || !hasRoutableCandidate) {
            if (allowPartialGathering) return;
            deferFailure(generation, "A routed ICE candidate was not available.");
            return;
        }
        answerSent = true;
        sink.sendAnswer(requestId, completeDescription);
    }

    private synchronized void failIfConnectionDidNotOpen(long generation) {
        if (generation != lifecycleGeneration.get() || probeStarted.get()) return;
        fail(generation, "Routed link did not open within 15 seconds.");
    }

    private void attachDataChannel(long generation, @NonNull DataChannel nextChannel) {
        if (generation != lifecycleGeneration.get()) {
            nextChannel.close();
            return;
        }
        channel = nextChannel;
        nextChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) { }

            @Override
            public void onStateChange() {
                if (generation == lifecycleGeneration.get()
                        && nextChannel.state() == DataChannel.State.OPEN) {
                    startProbe(generation, nextChannel);
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (generation != lifecycleGeneration.get() || buffer.binary) return;
                ByteBuffer bytes = buffer.data.slice();
                byte[] encoded = new byte[bytes.remaining()];
                bytes.get(encoded);
                try {
                    JSONObject ack = new JSONObject(
                            new String(encoded, StandardCharsets.UTF_8));
                    if ("ack".equals(ack.optString("type"))) {
                        acknowledgedBytes.accumulateAndGet(
                                ack.optLong("receivedBytes", 0L),
                                Math::max);
                    }
                } catch (Exception ignored) {
                    // Only acknowledgements defined by the probe are accepted.
                }
            }
        });
        if (nextChannel.state() == DataChannel.State.OPEN) {
            startProbe(generation, nextChannel);
        }
    }

    private void startProbe(long generation, @NonNull DataChannel activeChannel) {
        if (generation != lifecycleGeneration.get()) return;
        if (!probeStarted.compareAndSet(false, true)) return;
        probeStartedAtMs = System.currentTimeMillis();
        executor.schedule(
                () -> {
                    if (generation == lifecycleGeneration.get()) {
                        acknowledgedBytesAfterWarmup.set(acknowledgedBytes.get());
                    }
                },
                PROBE_WARMUP_MS,
                TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(
                () -> sendProbeBurst(generation, activeChannel),
                0L,
                2L,
                TimeUnit.MILLISECONDS);
        executor.schedule(
                () -> finishProbe(generation),
                PROBE_DURATION_MS + 250L,
                TimeUnit.MILLISECONDS);
    }

    private void sendProbeBurst(long generation, @NonNull DataChannel activeChannel) {
        if (generation != lifecycleGeneration.get()) return;
        long elapsed = System.currentTimeMillis() - probeStartedAtMs;
        if (
                elapsed >= PROBE_DURATION_MS
                || activeChannel.state() != DataChannel.State.OPEN
        ) {
            return;
        }
        for (
                int burst = 0;
                burst < 16 && activeChannel.bufferedAmount() < MAX_BUFFERED_BYTES;
                burst++
        ) {
            ByteBuffer payload = ByteBuffer.allocateDirect(CHUNK_BYTES);
            payload.putInt(nextSequence++);
            payload.position(CHUNK_BYTES);
            payload.flip();
            if (!activeChannel.send(new DataChannel.Buffer(payload, true))) {
                return;
            }
        }
    }

    private void finishProbe(long generation) {
        if (generation != lifecycleGeneration.get()) return;
        if (!resultSent.compareAndSet(false, true)) return;
        PeerConnection activePeer = peer;
        if (activePeer == null) {
            deferFailure(generation, "WebRTC peer closed before measurement completed.");
            return;
        }
        activePeer.getStats(report -> executeDeferred(
                () -> finishWithStats(generation, report)));
    }

    private void finishWithStats(long generation, @NonNull RTCStatsReport report) {
        if (generation != lifecycleGeneration.get()) return;
        String routeKind = selectedRouteKind(report);
        long bytes = Math.max(
                0L,
                acknowledgedBytes.get() - acknowledgedBytesAfterWarmup.get());
        long elapsedMs = Math.max(
                1L,
                Math.min(
                        PROBE_DURATION_MS - PROBE_WARMUP_MS,
                        System.currentTimeMillis() - probeStartedAtMs - PROBE_WARMUP_MS));
        long rawBitsPerSecond = bytes * 8_000L / elapsedMs;
        long usableBitsPerSecond = (long) (rawBitsPerSecond * SAFETY_MARGIN);
        CaltopoClient.CTDebug(
                TAG,
                "Preflight measurement request=" + requestId
                        + " route=" + routeKind
                        + " acknowledgedBytes=" + bytes
                        + " elapsedMs=" + elapsedMs
                        + " rawBps=" + rawBitsPerSecond
                        + " usableBps=" + usableBitsPerSecond
                        + " bufferedBytes=" + (channel == null ? -1L : channel.bufferedAmount()));
        if (routeKind.isEmpty() || usableBitsPerSecond <= 0L) {
            fail(generation, "WebRTC link measurement did not produce a usable result.");
            return;
        }
        sink.sendResult(requestId, routeKind, usableBitsPerSecond);
        closePeer(generation);
    }

    @NonNull
    private static String selectedRouteKind(@NonNull RTCStatsReport report) {
        Map<String, RTCStats> stats = report.getStatsMap();
        String selectedPairId = "";
        for (RTCStats stat : stats.values()) {
            if ("transport".equals(stat.getType())) {
                Object value = stat.getMembers().get("selectedCandidatePairId");
                if (value != null) selectedPairId = String.valueOf(value);
            }
        }
        RTCStats pair = stats.get(selectedPairId);
        if (pair == null) return "";
        RTCStats local = stats.get(
                String.valueOf(pair.getMembers().get("localCandidateId")));
        RTCStats remote = stats.get(
                String.valueOf(pair.getMembers().get("remoteCandidateId")));
        if (local == null || remote == null) return "";
        String localType = String.valueOf(local.getMembers().get("candidateType"));
        String remoteType = String.valueOf(remote.getMembers().get("candidateType"));
        return "relay".equals(localType) || "relay".equals(remoteType)
                ? "routed"
                : "direct";
    }

    @NonNull
    private static List<PeerConnection.IceServer> parseIceServers(
            @Nullable JSONArray values) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            List<String> urls = new ArrayList<>();
            Object rawUrls = value.opt("urls");
            if (rawUrls instanceof String) {
                urls.add((String) rawUrls);
            } else if (rawUrls instanceof JSONArray) {
                JSONArray array = (JSONArray) rawUrls;
                for (int urlIndex = 0; urlIndex < array.length(); urlIndex++) {
                    String url = array.optString(urlIndex).trim();
                    if (!url.isEmpty()) urls.add(url);
                }
            }
            if (urls.isEmpty()) continue;
            PeerConnection.IceServer.Builder builder =
                    PeerConnection.IceServer.builder(urls);
            String username = value.optString("username").trim();
            String credential = value.optString("credential").trim();
            if (!username.isEmpty() && !credential.isEmpty()) {
                builder.setUsername(username);
                builder.setPassword(credential);
            }
            result.add(builder.createIceServer());
        }
        return result;
    }

    private void deferFailure(long generation, @NonNull String reason) {
        executeDeferred(() -> fail(generation, reason));
    }

    private void executeDeferred(@NonNull Runnable action) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException ignored) {
            // The coordinator is already closed; stale native callbacks are ignored.
        }
    }

    private synchronized void fail(long generation, @NonNull String reason) {
        if (generation != lifecycleGeneration.get()) return;
        if (!requestId.isEmpty()) sink.onFailure(requestId, reason);
        closePeer(generation);
    }

    private synchronized void closePeer(long generation) {
        if (generation != lifecycleGeneration.get()) return;
        lifecycleGeneration.incrementAndGet();
        retireActivePeerLocked();
    }

    private void retireActivePeerLocked() {
        DataChannel activeChannel = channel;
        channel = null;
        if (activeChannel != null) {
            activeChannel.unregisterObserver();
            activeChannel.close();
        }
        PeerConnection activePeer = peer;
        peer = null;
        if (activePeer != null) {
            activePeer.close();
        }
        PeerConnectionFactory activeFactory = factory;
        factory = null;
        requestId = "";
        activeOfferSdp = "";
        localIceCandidates.clear();
        if (activeChannel != null || activePeer != null || activeFactory != null) {
            executor.schedule(() -> {
                if (activeChannel != null) activeChannel.dispose();
                if (activePeer != null) activePeer.dispose();
                if (activeFactory != null) activeFactory.dispose();
            }, 500L, TimeUnit.MILLISECONDS);
        }
    }

    synchronized void cancel() {
        lifecycleGeneration.incrementAndGet();
        retireActivePeerLocked();
    }

    @Override
    public synchronized void close() {
        lifecycleGeneration.incrementAndGet();
        retireActivePeerLocked();
        executor.shutdown();
    }

    private abstract class DescriptionObserver implements SdpObserver {
        private final long generation;
        @NonNull private final String operation;

        DescriptionObserver(long generation, @NonNull String operation) {
            this.generation = generation;
            this.operation = operation;
        }

        abstract void onSuccessValue();

        @Override public void onSetSuccess() {
            if (generation == lifecycleGeneration.get()) onSuccessValue();
        }
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onCreateFailure(String error) {
            CaltopoClient.CTWarn(TAG, "WebRTC failed to " + operation + ": " + error);
            deferFailure(generation, "WebRTC failed to " + operation + ".");
        }
        @Override public void onSetFailure(String error) {
            CaltopoClient.CTWarn(TAG, "WebRTC failed to " + operation + ": " + error);
            deferFailure(generation, "WebRTC failed to " + operation + ".");
        }
    }

    private final class PeerObserver implements PeerConnection.Observer {
        private final long generation;

        PeerObserver(long generation) {
            this.generation = generation;
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            CaltopoClient.CTDebug(
                    TAG,
                    "Preflight ICE connection request=" + requestId + " state=" + state);
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            CaltopoClient.CTDebug(
                    TAG,
                    "Preflight ICE gathering request=" + requestId + " state=" + state);
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                maybeSendAnswer(generation, false);
            }
        }
        @Override public void onIceCandidate(IceCandidate candidate) {
            synchronized (ManagedVideoPreflightPeer.this) {
                if (generation != lifecycleGeneration.get()) return;
                localIceCandidates.add(candidate);
                String candidateType = candidate.sdp.contains(" typ relay ")
                        ? "relay"
                        : candidate.sdp.contains(" typ srflx ") ? "srflx" : "host";
                CaltopoClient.CTDebug(
                        TAG,
                        "Preflight " + candidateType + " candidate gathered request="
                                + requestId + " mline=" + candidate.sdpMLineIndex
                                + " count=" + localIceCandidates.size());
            }
            if (!ManagedVideoSdp.hasRoutableIceCandidate(localIceCandidates)) return;
            try {
                executor.schedule(
                        () -> maybeSendAnswer(generation, true),
                        50L,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // The coordinator was closed while ICE was winding down.
            }
        }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel dataChannel) {
            attachDataChannel(generation, dataChannel);
        }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) { }
    }
}
