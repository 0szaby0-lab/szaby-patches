package app.morphe.patches.all.license

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.Patch

@Patch(
    name = "Szaby License Check",
    description = "Validates Szaby license key. Without a valid key, all Szaby patches are disabled.",
    use = true
)
@Suppress("unused")
object LicenseCheckPatch : BytecodePatch(emptySet()) {

    // ¦¦¦ Config ¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦¦
    private const val LICENSE_SERVER_URL = "https://szaby-license-server.onrender.com"
    private const val PREFS_NAME = "szaby_license"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_HWID = "device_hwid"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_BANNED = "is_banned"
    private const val KEY_LAST_VALID = "last_valid_ts"
    // Grace period: 7 days offline tolerance
    private const val GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000

    override fun execute(context: BytecodeContext) {
        // The actual license logic is injected as a smali helper class.
        // This patch registers it; the real logic lives in LicenseManager.kt (extension).
    }

    /**
     * Called by the patched app on startup via smali injection.
     * Returns true if license is valid (patches should be active).
     */
    @JvmStatic
    fun checkLicense(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

        // Hard banned flag - set locally and never cleared
        if (prefs.getBoolean(KEY_BANNED, false)) return false

        val storedKey = prefs.getString(KEY_LICENSE_KEY, null)
            ?: return false // No key entered yet

        val hwid = getOrCreateHwid(context, prefs)

        // Try network validation
        return try {
            val result = validateOnline(storedKey, hwid)
            when (result.status) {
                "VALID", "ACTIVATED" -> {
                    prefs.edit()
                        .putLong(KEY_LAST_VALID, System.currentTimeMillis())
                        .putString(KEY_EXPIRES, result.expiresAt)
                        .apply()
                    true
                }
                "BANNED" -> {
                    // HWID banned - set permanent local flag
                    prefs.edit().putBoolean(KEY_BANNED, true).apply()
                    false
                }
                else -> false
            }
        } catch (e: java.net.UnknownHostException) {
            // No internet at all - use grace period
            handleOffline(prefs)
        } catch (e: java.io.IOException) {
            // Connection refused / timeout - could be bypass attempt
            val lastValid = prefs.getLong(KEY_LAST_VALID, 0)
            if (lastValid > 0 && System.currentTimeMillis() - lastValid < 30_000) {
                // Connected very recently, server is just slow - grace period
                handleOffline(prefs)
            } else {
                // Suspicious: was fine, now can't reach server - report bypass
                reportBypass(storedKey, hwid)
                prefs.edit().putBoolean(KEY_BANNED, true).apply()
                false
            }
        }
    }

    private fun handleOffline(prefs: android.content.SharedPreferences): Boolean {
        val lastValid = prefs.getLong(KEY_LAST_VALID, 0)
        if (lastValid == 0L) return false
        val elapsed = System.currentTimeMillis() - lastValid
        return elapsed < GRACE_PERIOD_MS
    }

    private data class ValidationResult(val status: String, val expiresAt: String?)

    private fun validateOnline(key: String, hwid: String): ValidationResult {
        val url = java.net.URL("$LICENSE_SERVER_URL/api/validate?key=${encode(key)}&hwid=${encode(hwid)}")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        if (code != 200) return ValidationResult("ERROR", null)
        val body = conn.inputStream.bufferedReader().readText()
        // Parse minimal JSON manually (no Gson in patch context)
        val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: "ERROR"
        val expires = Regex(""""expires_at"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        return ValidationResult(status, expires)
    }

    private fun reportBypass(key: String, hwid: String) {
        try {
            val url = java.net.URL("$LICENSE_SERVER_URL/api/report-bypass")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"key":"$key","hwid":"$hwid"}"""
            conn.outputStream.write(payload.toByteArray())
            conn.responseCode // fire and forget
        } catch (_: Exception) { }
    }

    /**
     * Builds a stable hardware fingerprint (HWID) from device identifiers.
     * SHA-256 hashed so no raw personal data leaves the device.
     */
    @Suppress("HardwareIds")
    private fun getOrCreateHwid(context: android.content.Context, prefs: android.content.SharedPreferences): String {
        prefs.getString(KEY_HWID, null)?.let { return it }

        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        val buildSerial = try { @Suppress("DEPRECATION") android.os.Build.SERIAL } catch (_: Exception) { "" }
        val cpuAbi = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        val board = android.os.Build.BOARD
        val hardware = android.os.Build.HARDWARE
        val fingerprint = android.os.Build.FINGERPRINT

        val raw = "$androidId|$buildSerial|$cpuAbi|$board|$hardware|$fingerprint"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hwid = digest.joinToString("") { "%02x".format(it) }

        prefs.edit().putString(KEY_HWID, hwid).apply()
        return hwid
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
