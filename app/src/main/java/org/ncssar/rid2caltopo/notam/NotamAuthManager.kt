package org.ncssar.rid2caltopo.notam

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.IOException

internal object NotamAuthManager {
    enum class CredentialSource {
        NONE,
        ORGANIZATION_CONFIG_CREDENTIAL,
        MANAGED_DEVICE_ENROLLMENT
    }

    private data class ProxyConfiguration(
        val url: HttpUrl,
        val token: String
    )

    sealed class NotamAuthException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        class Network(message: String, cause: Throwable? = null) : NotamAuthException(message, cause)
        class Authorization(message: String) : NotamAuthException(message)
        class Service(message: String) : NotamAuthException(message)
    }

    fun isConfigured(): Boolean {
        return configurationOrNull() != null
    }

    fun credentialSource(): CredentialSource {
        if (configurationOrNull() == null) return CredentialSource.NONE
        return if (CaltopoClient.GetHomeTrackerApiKey().trim().startsWith("r2c_dev_")) {
            CredentialSource.MANAGED_DEVICE_ENROLLMENT
        } else {
            CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL
        }
    }

    fun authorizationFailureMessage(httpCode: Int): String = when (credentialSource()) {
        CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL -> {
            val organization = CaltopoClient.GetHomeOrgName().ifBlank { "organization" }
            "$organization configuration is loaded, but its tracker credential was rejected " +
                "by the FAA proxy (HTTP $httpCode). If $organization is hosted on r2c-tracker, " +
                "this is an enrollment or provisioning mismatch; scan a current $organization " +
                "device-enrollment QR or contact the organization administrator."
        }
        CredentialSource.MANAGED_DEVICE_ENROLLMENT ->
            "The managed device credential was rejected by the FAA proxy (HTTP $httpCode). " +
                "Create a new Drone-team enrollment QR or re-enroll this device."
        CredentialSource.NONE ->
            "FAA proxy access is not enrolled (HTTP $httpCode). Scan a Drone-team enrollment QR from r2c-tracker."
    }

    fun resolvedNotamUrl(): String {
        return configurationOrNull()?.url?.toString()
            ?: throw NotamAuthException.Service(
                "FAA NOTAM proxy access requires an r2c-tracker organization QR code."
            )
    }

    fun proxyToken(): String {
        return configurationOrNull()?.token
            ?: throw NotamAuthException.Authorization(
                "FAA NOTAM proxy access requires an r2c-tracker organization QR code."
            )
    }

    private fun configurationOrNull(): ProxyConfiguration? {
        val trackerUrl = CaltopoClient.GetHomeTrackerUrlPfx().trim().toHttpUrlOrNull() ?: return null
        val token = CaltopoClient.GetHomeTrackerApiKey().trim()
        if (token.isEmpty() || !trackerUrl.isHttps || !trustedHost(trackerUrl.host)) return null

        val configuredProxy = CaltopoClient.GetTrackerFaaProxyUrl().trim()
        val proxyUrl = if (configuredProxy.isBlank()) {
            // Organization bundles created before tracker enrollment included the
            // tracker host and device token but not this derived endpoint.
            trackerUrl.newBuilder()
                .encodedPath("/faa/notams")
                .query(null)
                .fragment(null)
                .build()
        } else {
            configuredProxy.toHttpUrlOrNull() ?: return null
        }
        if (token.isEmpty() ||
            !proxyUrl.isHttps ||
            !trustedHost(proxyUrl.host) ||
            proxyUrl.host != trackerUrl.host ||
            proxyUrl.port != trackerUrl.port ||
            proxyUrl.encodedPath != "/faa/notams"
        ) {
            return null
        }
        return ProxyConfiguration(proxyUrl, token)
    }

    private fun trustedHost(host: String): Boolean =
        host.equals("r2c-tracker.com", ignoreCase = true) ||
            host.endsWith(".r2c-tracker.com", ignoreCase = true)
}
