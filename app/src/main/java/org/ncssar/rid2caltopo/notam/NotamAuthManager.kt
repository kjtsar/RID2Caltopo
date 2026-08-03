package org.ncssar.rid2caltopo.notam

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.IOException

internal object NotamAuthManager {
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
        val proxyUrl = CaltopoClient.GetTrackerFaaProxyUrl().trim().toHttpUrlOrNull() ?: return null
        val trackerUrl = CaltopoClient.GetHomeTrackerUrlPfx().trim().toHttpUrlOrNull() ?: return null
        val token = CaltopoClient.GetHomeTrackerApiKey().trim()
        if (token.isEmpty() ||
            !proxyUrl.isHttps ||
            !trackerUrl.isHttps ||
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
