package org.ncssar.rid2caltopo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class TrackerEnrollmentResult(
    val organization: String,
    val trackerBaseUrl: String,
    val deviceToken: String,
    val faaProxyUrl: String,
    val enrollmentUrl: String
)

object TrackerEnrollmentClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun isEnrollmentUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps &&
            trustedHost(url.host) &&
            url.encodedPath.endsWith("/enroll") &&
            !url.queryParameter("token").isNullOrBlank()
    }

    fun enrollmentOrganization(value: String): String? {
        val url = value.trim().toHttpUrlOrNull()?.takeIf { isEnrollmentUrl(value) } ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        return segments.takeIf { it.size >= 2 }?.get(segments.lastIndex - 1)
    }

    suspend fun redeem(context: Context, value: String): TrackerEnrollmentResult = withContext(Dispatchers.IO) {
        redeemBlocking(context, value)
    }

    fun redeemBlocking(context: Context, value: String): TrackerEnrollmentResult {
        val enrollmentUrl = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enrollment QR is not a valid URL.")
        if (!isEnrollmentUrl(value)) {
            throw IllegalArgumentException("Enrollment QR is not an r2c-tracker.com enrollment URL.")
        }
        val token = enrollmentUrl.queryParameter("token")
            ?: throw IllegalArgumentException("Enrollment QR has no token.")
        val redeemUrl = enrollmentUrl.newBuilder()
            .encodedPath("/api/v1/device-enrollment/redeem")
            .query(null)
            .fragment(null)
            .build()
        val payload = JSONObject()
            .put("token", token)
            .put("device_name", AndroidDeviceIdentity.displayName(context))
            .put("platform", "android")
            .toString()
        val request = Request.Builder()
            .url(redeemUrl)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        return CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.ifBlank { null } ?: "Enrollment failed (${response.code}).")
            }
            val root = JSONObject(body)
            val organization = root.getJSONObject("organization")
            val tracker = root.getJSONObject("tracker")
            TrackerEnrollmentResult(
                organization = organization.optString("designator")
                    .ifBlank { organization.getString("name") },
                trackerBaseUrl = tracker.getString("base_url"),
                deviceToken = tracker.getString("api_key"),
                faaProxyUrl = tracker.getString("faa_proxy_url"),
                enrollmentUrl = value.trim()
            )
        }
    }

    fun apply(result: TrackerEnrollmentResult) {
        CaltopoClient.SetHomeOrgName(result.organization)
        CaltopoClient.SetHomeTrackerCredentials(
            result.trackerBaseUrl,
            result.deviceToken
        )
        CaltopoClient.SetUsePeers(true)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoClient.SetTrackerFaaProxyUrl(result.faaProxyUrl)
        CaltopoClient.SetTrackerEnrollmentUrl(result.enrollmentUrl)
        CaltopoClient.SetNotamEnabled(true)
    }

    private fun trustedHost(host: String): Boolean =
        host.equals("r2c-tracker.com", ignoreCase = true) ||
            host.endsWith(".r2c-tracker.com", ignoreCase = true)
}
