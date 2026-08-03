package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

import org.webrtc.IceCandidate;

import java.util.ArrayList;
import java.util.List;

/** Cross-version SDP normalization shared by synthetic probes and approved media. */
final class ManagedVideoSdp {
    private ManagedVideoSdp() { }

    @NonNull
    static String normalizeRemoteOffer(@NonNull String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String cleanLine = line.trim();
            if (cleanLine.isEmpty() || cleanLine.startsWith("a=max-message-size:")) {
                continue;
            }
            result.append(cleanLine).append("\r\n");
        }
        return result.toString();
    }

    /**
     * Return a non-trickle SDP description containing candidates reported by
     * libwebrtc after setLocalDescription(). Android's getLocalDescription()
     * does not reliably fold trickled candidates back into its SDP, so sending
     * that description by itself can produce an answer with no usable route.
     */
    @NonNull
    static String withIceCandidates(
            @NonNull String value,
            @NonNull List<IceCandidate> candidates) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String cleanLine = line.trim();
            if (!cleanLine.isEmpty()) lines.add(cleanLine);
        }
        for (IceCandidate candidate : candidates) {
            String candidateLine = candidate.sdp == null ? "" : candidate.sdp.trim();
            if (candidateLine.isEmpty()) continue;
            if (!candidateLine.startsWith("a=")) candidateLine = "a=" + candidateLine;
            if (lines.contains(candidateLine)) continue;

            int targetMediaLine = Math.max(0, candidate.sdpMLineIndex);
            int currentMediaLine = -1;
            int insertAt = lines.size();
            boolean foundTarget = false;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.startsWith("m=")) {
                    currentMediaLine++;
                    if (foundTarget) {
                        insertAt = index;
                        break;
                    }
                    foundTarget = currentMediaLine == targetMediaLine;
                } else if (foundTarget && line.equals("a=end-of-candidates")) {
                    insertAt = index;
                    break;
                }
            }
            if (foundTarget) lines.add(insertAt, candidateLine);
        }
        StringBuilder result = new StringBuilder();
        for (String line : lines) result.append(line).append("\r\n");
        return result.toString();
    }

    /**
     * A browser relay cannot initiate a check against an Android private-host
     * candidate. Wait for a server-reflexive or relay candidate before sending
     * a non-trickle answer; otherwise later usable candidates are stranded on
     * the tablet after the one-shot answer has already been recorded.
     */
    static boolean hasRoutableIceCandidate(@NonNull List<IceCandidate> candidates) {
        for (IceCandidate candidate : candidates) {
            String value = candidate.sdp == null ? "" : candidate.sdp;
            if (hasRoutableIceCandidate(value)) {
                return true;
            }
        }
        return false;
    }

    /** Inspect the completed local SDP, which is authoritative on Android builds
     * that fold candidates into the description without emitting callbacks. */
    static boolean hasRoutableIceCandidate(@NonNull String sdp) {
        return sdp.contains(" typ srflx ") || sdp.contains(" typ relay ");
    }
}
