package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/** Shared contract for the tracker tablet link published in a CalTopo device marker. */
public final class TrackerTabletLink {
    private static final int CODE_DIGEST_BYTES = 4;

    private TrackerTabletLink() {
    }

    @Nullable
    public static String organizationDesignator(@Nullable String trackerUrlPrefix) {
        String value = trackerUrlPrefix == null ? "" : trackerUrlPrefix.trim();
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] segments = path.split("/");
            for (int index = segments.length - 1; index >= 0; index--) {
                String segment = segments[index].trim();
                if (!segment.isEmpty()) return segment.toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    public static String shortUrl(
            @Nullable String trackerUrlPrefix,
            @Nullable String tabletName
    ) {
        String organization = organizationDesignator(trackerUrlPrefix);
        String tablet = normalize(tabletName);
        if (organization == null || tablet.isEmpty()) return null;
        try {
            URI tracker = URI.create(trackerUrlPrefix.trim());
            if (tracker.getScheme() == null || tracker.getRawAuthority() == null) return null;
            String code = code(organization, tablet);
            return new URI(
                    tracker.getScheme(),
                    tracker.getRawAuthority(),
                    "/t/" + code,
                    null,
                    null
            ).toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    public static String markerDescription(
            @Nullable String trackerUrlPrefix,
            @Nullable String tabletName
    ) {
        return markerDescription(trackerUrlPrefix, tabletName, true);
    }

    @NonNull
    public static String markerDescription(
            @Nullable String trackerUrlPrefix,
            @Nullable String tabletName,
            boolean trackerConnected
    ) {
        if (!trackerConnected) return "";
        String url = shortUrl(trackerUrlPrefix, tabletName);
        return url == null ? "" : url;
    }

    @Nullable
    public static String streamShortUrl(
            @Nullable String trackerUrlPrefix,
            @Nullable String tabletName,
            @Nullable String videoStream
    ) {
        String organization = organizationDesignator(trackerUrlPrefix);
        String tablet = normalize(tabletName);
        String stream = normalize(videoStream);
        if (organization == null || tablet.isEmpty() || stream.isEmpty()) return null;
        try {
            URI tracker = URI.create(trackerUrlPrefix.trim());
            if (tracker.getScheme() == null || tracker.getRawAuthority() == null) return null;
            return new URI(
                    tracker.getScheme(),
                    tracker.getRawAuthority(),
                    "/s/" + streamCode(organization, tablet, stream),
                    null,
                    null
            ).toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static String recordingShortUrl(
            @Nullable String trackerUrlPrefix,
            @Nullable String tabletName,
            @Nullable String sessionId
    ) {
        String organization = organizationDesignator(trackerUrlPrefix);
        String tablet = normalize(tabletName);
        String session = normalize(sessionId);
        if (organization == null || tablet.isEmpty() || session.isEmpty()) return null;
        try {
            URI tracker = URI.create(trackerUrlPrefix.trim());
            if (tracker.getScheme() == null || tracker.getRawAuthority() == null) return null;
            return new URI(
                    tracker.getScheme(),
                    tracker.getRawAuthority(),
                    "/v/" + recordingCode(organization, tablet, session),
                    null,
                    null
            ).toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static String thumbnailUrl(
            @Nullable String trackerUrlPrefix,
            @Nullable String tabletName,
            @Nullable String streamSessionId
    ) {
        String organization = organizationDesignator(trackerUrlPrefix);
        String tablet = normalize(tabletName);
        String sessionId = streamSessionId == null ? "" : streamSessionId.trim();
        if (organization == null || tablet.isEmpty() || sessionId.isEmpty()) return null;
        try {
            URI tracker = URI.create(trackerUrlPrefix.trim());
            if (tracker.getScheme() == null || tracker.getRawAuthority() == null) return null;
            return new URI(
                    tracker.getScheme(),
                    tracker.getRawAuthority(),
                    "/r2c-thumbnail/" + code(organization, tablet) + "/" + sessionId + ".jpg",
                    null,
                    null
            ).toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    static String code(
            @NonNull String organization,
            @NonNull String tabletName
    ) throws Exception {
        String material = "/" + normalize(organization)
                + "/streams/" + normalize(tabletName);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        byte[] prefix = new byte[CODE_DIGEST_BYTES];
        System.arraycopy(digest, 0, prefix, 0, CODE_DIGEST_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(prefix);
    }

    @NonNull
    static String streamCode(
            @NonNull String organization,
            @NonNull String tabletName,
            @NonNull String videoStream
    ) throws Exception {
        String material = "/" + normalize(organization)
                + "/streams/" + normalize(tabletName)
                + "/" + normalize(videoStream);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        byte[] prefix = new byte[CODE_DIGEST_BYTES];
        System.arraycopy(digest, 0, prefix, 0, CODE_DIGEST_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(prefix);
    }

    @NonNull
    static String recordingCode(
            @NonNull String organization,
            @NonNull String tabletName,
            @NonNull String sessionId
    ) throws Exception {
        String material = "/" + normalize(organization)
                + "/streams/" + normalize(tabletName)
                + "/session/" + normalize(sessionId);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        byte[] prefix = new byte[CODE_DIGEST_BYTES];
        System.arraycopy(digest, 0, prefix, 0, CODE_DIGEST_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(prefix);
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
