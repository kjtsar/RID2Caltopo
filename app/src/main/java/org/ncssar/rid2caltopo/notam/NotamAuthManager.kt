package org.ncssar.rid2caltopo.notam

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal object NotamAuthManager {
    private const val TAG = "NotamAuth"
    private const val DEFAULT_API_BASE_URL = "https://api-nms.aim.faa.gov/nmsapi"
    private const val DEFAULT_TOKEN_URL = "https://api-nms.aim.faa.gov/v1/auth/token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedTokenExpiryEpochMs: Long = 0L

    sealed class NotamAuthException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        class Network(message: String, cause: Throwable? = null) : NotamAuthException(message, cause)
        class Authorization(message: String) : NotamAuthException(message)
        class Service(message: String) : NotamAuthException(message)
    }

    fun isConfigured(): Boolean {
        return resolvedApiBaseUrl().isNotBlank() &&
            resolvedTokenUrl().isNotBlank() &&
            CaltopoClient.GetNotamClientId().isNotBlank() &&
            CaltopoClient.GetNotamClientSecret().isNotBlank()
    }

    fun resolvedApiBaseUrl(): String {
        return CaltopoClient.GetNotamApiBaseUrl().ifBlank { DEFAULT_API_BASE_URL }
    }

    fun resolvedTokenUrl(): String {
        return CaltopoClient.GetNotamTokenUrl().ifBlank { DEFAULT_TOKEN_URL }
    }

    @Synchronized
    fun getBearerToken(): String {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (!existing.isNullOrBlank() && now + 60_000L < cachedTokenExpiryEpochMs) {
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, "Reusing cached bearer token; expiresInMs=${cachedTokenExpiryEpochMs - now}")
            }
            return existing
        }

        if (CTDebugEnabled(TAG)) {
            CTDebug(
                TAG,
                "Requesting bearer token tokenUrl=${resolvedTokenUrl()} clientIdPresent=${CaltopoClient.GetNotamClientId().isNotBlank()} secretPresent=${CaltopoClient.GetNotamClientSecret().isNotBlank()}"
            )
        }

        val request = Request.Builder()
            .url(resolvedTokenUrl())
            .header(
                "Authorization",
                Credentials.basic(CaltopoClient.GetNotamClientId(), CaltopoClient.GetNotamClientSecret())
            )
            .post(
                FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .build()
            )
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (CTDebugEnabled(TAG)) {
                    val preview = body.replace('\n', ' ').take(240)
                    CTDebug(TAG, "Token response http=${response.code} body='$preview'")
                }
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        throw NotamAuthException.Authorization("NOTAM authentication failed (HTTP ${response.code}).")
                    }
                    throw NotamAuthException.Service("NOTAM token request failed with HTTP ${response.code}.")
                }
                val json = JSONObject(body)
                val token = json.optString("access_token")
                if (token.isBlank()) {
                    throw NotamAuthException.Service("NOTAM token response did not include an access token.")
                }
                val expiresInSeconds = json.optLong("expires_in", 1800L)
                cachedToken = token
                cachedTokenExpiryEpochMs = now + (expiresInSeconds.coerceAtLeast(60L) * 1000L)
                if (CTDebugEnabled(TAG)) {
                    CTDebug(TAG, "Retrieved bearer token expiresInSeconds=$expiresInSeconds scope='${json.optString("scope")}'")
                }
                return token
            }
        } catch (e: NotamAuthException) {
            throw e
        } catch (e: UnknownHostException) {
            throw NotamAuthException.Network("NOTAM token request could not resolve host.", e)
        } catch (e: SocketTimeoutException) {
            throw NotamAuthException.Network("NOTAM token request timed out.", e)
        } catch (e: IOException) {
            throw NotamAuthException.Network("NOTAM token request failed due to network error.", e)
        }
    }
}
