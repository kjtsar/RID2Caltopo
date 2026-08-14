package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

public final class RecordingDownloadRequest {
    @NonNull public final String requestId;
    @NonNull public final String requesterEmail;
    @NonNull public final String streamSessionId;
    @NonNull public final String droneDesignator;
    @NonNull public final String uploadPath;
    @NonNull public final String expiresAt;
    public final boolean consentRequired;

    public RecordingDownloadRequest(
            @NonNull String requestId, @NonNull String requesterEmail,
            @NonNull String streamSessionId, @NonNull String droneDesignator,
            @NonNull String uploadPath, @NonNull String expiresAt,
            boolean consentRequired) {
        this.requestId = requestId;
        this.requesterEmail = requesterEmail;
        this.streamSessionId = streamSessionId;
        this.droneDesignator = droneDesignator;
        this.uploadPath = uploadPath;
        this.expiresAt = expiresAt;
        this.consentRequired = consentRequired;
    }
}
