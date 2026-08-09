package com.example.messagesender

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Client-side licensing. A token entered by the user is validated against the
 * license server, which returns a short-lived lease signed with its private key.
 * The lease is verified here with the embedded public key and cached with its
 * expiry. The app only operates while a non-expired lease is present, so it
 * stops working when a token is disabled server-side (revocation) or the device
 * stays offline past the lease lifetime.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS = "license"
    private const val KEY_TOKEN = "token"
    private const val KEY_LEASE_EXP = "lease_exp"
    private const val KEY_LAST_VALIDATED = "last_validated"
    private const val KEY_REVOKED = "revoked"

    /** Skew tolerance so a device clock slightly behind the server still works. */
    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    /**
     * Grace period: keep working this long after the last successful validation,
     * even if the server is unreachable — so a server outage does not lock
     * everyone out. An explicit revocation (403) locks immediately regardless.
     */
    private const val GRACE_MS = 7L * 24 * 60 * 60 * 1000

    enum class Outcome { VALID, INVALID_OR_DISABLED, NETWORK_ERROR, BAD_SIGNATURE, CONFIG_ERROR }

    data class Result(val outcome: Outcome, val leaseExp: Long = 0L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun leaseExp(context: Context): Long = prefs(context).getLong(KEY_LEASE_EXP, 0L)

    fun lastValidated(context: Context): Long =
        prefs(context).getLong(KEY_LAST_VALIDATED, 0L)

    fun isRevoked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REVOKED, false)

    /**
     * Valid while not explicitly revoked AND within the grace window since the
     * last successful validation. A short server outage therefore keeps working;
     * a disabled token (revoked) locks immediately.
     */
    fun hasValidLease(context: Context): Boolean {
        if (isRevoked(context)) return false
        val last = lastValidated(context)
        if (last <= 0L) return false
        return System.currentTimeMillis() - last < GRACE_MS
    }

    /** Clears the cached lease (but keeps the token so the user can retry). */
    fun clearLease(context: Context) {
        prefs(context).edit().remove(KEY_LEASE_EXP).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    @Suppress("HardwareIds")
    fun deviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }

    /**
     * Contacts the server to validate [token]. Blocking — call off the main
     * thread. On success the lease and token are cached.
     */
    fun validate(context: Context, token: String): Result {
        // Debug/test builds: accept any non-empty token locally (no server needed)
        // so the token-gated flow can be demonstrated on a phone.
        if (!BuildConfig.LICENSE_ENFORCED) {
            val exp = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
            prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_LEASE_EXP, exp)
                .putLong(KEY_LAST_VALIDATED, System.currentTimeMillis())
                .putBoolean(KEY_REVOKED, false)
                .apply()
            return Result(Outcome.VALID, exp)
        }

        if (BuildConfig.SERVER_URL.contains("your-server.example.com") ||
            BuildConfig.LICENSE_PUBLIC_KEY.startsWith("PASTE_")
        ) {
            Log.e(TAG, "SERVER_URL / LICENSE_PUBLIC_KEY not configured in build.gradle.kts")
            return Result(Outcome.CONFIG_ERROR)
        }

        val deviceId = deviceId(context)
        val response = try {
            postValidate(token, deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "Validation network error", e)
            return Result(Outcome.NETWORK_ERROR)
        }

        if (response.code == 403) {
            // Explicit revocation: lock immediately, bypassing the grace window.
            prefs(context).edit().putBoolean(KEY_REVOKED, true).apply()
            return Result(Outcome.INVALID_OR_DISABLED)
        }
        if (response.code != 200 || response.body.isNullOrBlank()) {
            return Result(Outcome.NETWORK_ERROR)
        }

        return try {
            val json = JSONObject(response.body)
            val payload = json.getString("payload")
            val sig = json.getString("sig")

            if (!verifySignature(payload, sig)) {
                Log.w(TAG, "Lease signature verification failed")
                return Result(Outcome.BAD_SIGNATURE)
            }

            val decoded = JSONObject(
                String(decodeB64Url(payload), Charsets.UTF_8)
            )
            val leaseToken = decoded.getString("token")
            val leaseDevice = decoded.getString("deviceId")
            val exp = decoded.getLong("exp")

            // Ensure the lease was really issued for this token and device.
            if (leaseToken != token || leaseDevice != deviceId) {
                Log.w(TAG, "Lease does not match token/device")
                return Result(Outcome.BAD_SIGNATURE)
            }
            if (exp <= System.currentTimeMillis() - CLOCK_SKEW_MS) {
                return Result(Outcome.INVALID_OR_DISABLED)
            }

            prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_LEASE_EXP, exp)
                .putLong(KEY_LAST_VALIDATED, System.currentTimeMillis())
                .putBoolean(KEY_REVOKED, false)
                .apply()

            Result(Outcome.VALID, exp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse lease", e)
            Result(Outcome.NETWORK_ERROR)
        }
    }

    private data class HttpResponse(val code: Int, val body: String?)

    private fun postValidate(token: String, deviceId: String): HttpResponse {
        val url = URL(BuildConfig.SERVER_URL.trimEnd('/') + "/api/validate")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val payload = JSONObject()
                .put("token", token)
                .put("deviceId", deviceId)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .toString()

            conn.outputStream.use { os: OutputStream ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            HttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun verifySignature(payloadStr: String, sigB64Url: String): Boolean {
        return try {
            val keyBytes = Base64.decode(BuildConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(payloadStr.toByteArray(Charsets.UTF_8))
            signature.verify(decodeB64Url(sigB64Url))
        } catch (e: Exception) {
            Log.w(TAG, "Signature check error", e)
            false
        }
    }

    private fun decodeB64Url(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
