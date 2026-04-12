package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Locale;

/**
 * Pure ownership scoring helper extracted from {@link R2CMqttManager}.
 *
 * Keeping this logic free of MQTT/session state makes it easy to reuse from a
 * future runtime-owned coordinator and easy to validate in deterministic tests.
 */
public final class PeerOwnershipEngine {

    public static final double DEFAULT_DIST_SCALE_M = 200.0;
    public static final double DEFAULT_RTT_SCALE_MS = 1_000.0;
    public static final double DEFAULT_WEIGHT_PROXIMITY = 0.75;
    public static final double DEFAULT_WEIGHT_RTT = 0.25;

    public interface ObservationView {
        @NonNull String getObserverGuid();
        double getDistMeters();
        long getFirstSeenTs();
        boolean isExpired(long nowMs);
    }

    public interface PeerMetricsLookup {
        long lookupCaltopoRttMs(@NonNull String observerGuid);
    }

    public static final class ScoreResult {
        @Nullable public final String ownerGuid;
        public final double score;
        public final long firstSeenTs;

        ScoreResult(@Nullable String ownerGuid, double score, long firstSeenTs) {
            this.ownerGuid = ownerGuid;
            this.score = score;
            this.firstSeenTs = firstSeenTs;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.US, "owner=%s score=%.5f firstSeen=%d",
                    ownerGuid, score, firstSeenTs);
        }
    }

    private final double distScaleM;
    private final double rttScaleMs;
    private final double weightProximity;
    private final double weightRtt;

    public PeerOwnershipEngine() {
        this(DEFAULT_DIST_SCALE_M, DEFAULT_RTT_SCALE_MS,
                DEFAULT_WEIGHT_PROXIMITY, DEFAULT_WEIGHT_RTT);
    }

    public PeerOwnershipEngine(double distScaleM, double rttScaleMs,
                               double weightProximity, double weightRtt) {
        this.distScaleM = distScaleM;
        this.rttScaleMs = rttScaleMs;
        this.weightProximity = weightProximity;
        this.weightRtt = weightRtt;
    }

    public double computeScore(@NonNull ObservationView observation,
                               @NonNull PeerMetricsLookup metricsLookup) {
        double proxScore = 1.0 / (1.0 + observation.getDistMeters() / distScaleM);
        double ctRtt = metricsLookup.lookupCaltopoRttMs(observation.getObserverGuid());
        double rttScore = 1.0 / (1.0 + ctRtt / rttScaleMs);
        return weightProximity * proxScore + weightRtt * rttScore;
    }

    @NonNull
    public ScoreResult selectOwner(@NonNull Collection<? extends ObservationView> observations,
                                   @NonNull PeerMetricsLookup metricsLookup,
                                   long nowMs) {
        String bestGuid = null;
        double bestScore = -1;
        long bestFirstSeenTs = Long.MAX_VALUE;

        for (ObservationView observation : observations) {
            if (observation.isExpired(nowMs)) continue;
            double score = computeScore(observation, metricsLookup);
            String observerGuid = observation.getObserverGuid();
            long firstSeenTs = observation.getFirstSeenTs();
            boolean better =
                    score > bestScore ||
                    (score == bestScore && firstSeenTs < bestFirstSeenTs) ||
                    (score == bestScore && firstSeenTs == bestFirstSeenTs &&
                            (bestGuid == null || observerGuid.compareTo(bestGuid) < 0));
            if (better) {
                bestGuid = observerGuid;
                bestScore = score;
                bestFirstSeenTs = firstSeenTs;
            }
        }

        return new ScoreResult(bestGuid, bestScore, bestFirstSeenTs);
    }
}
