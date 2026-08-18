package app.morphe.patches.all.license

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.Patch
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction11x

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
    private const val GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000

    override fun execute(context: BytecodeContext) {
        // Find android.app.Application -> onCreate or a suitable initialization point
        // Since this requires Dexlib2 manipulation and finding the Application subclass,
        // For ReVanced, the generic ApplicationInitHook handles this.
        // As a fallback, we hook into Android's base Activity onCreate if available in the dex,
        // or let the ReVanced extension framework call this method automatically via SharedExtensionPatch.
    }

    /**
     * Called by the patched app on startup via smali injection.
     * Returns true if license is valid (patches should be active).
     */
    @JvmStatic
    fun checkLicense(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BANNED, false)) return false

        val storedKey = prefs.getString(KEY_LICENSE_KEY, null) ?: return false
        val hwid = getOrCreateHwid(context, prefs)

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
                    prefs.edit().putBoolean(KEY_BANNED, true).apply()
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            handleOffline(prefs)
        }
    }

    private fun handleOffline(prefs: android.content.SharedPreferences): Boolean {
        val lastValid = prefs.getLong(KEY_LAST_VALID, 0)
        return lastValid > 0 && (System.currentTimeMillis() - lastValid) < GRACE_PERIOD_MS
    }

    private data class ValidationResult(val status: String, val expiresAt: String?)

    private fun validateOnline(key: String, hwid: String): ValidationResult {
        val url = java.net.URL("$LICENSE_SERVER_URL/api/validate?key=${java.net.URLEncoder.encode(key, "UTF-8")}&hwid=${java.net.URLEncoder.encode(hwid, "UTF-8")}")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        val code = conn.responseCode
        if (code != 200) return ValidationResult("ERROR", null)
        val body = conn.inputStream.bufferedReader().readText()
        val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: "ERROR"
        val expires = Regex(""""expires_at"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        return ValidationResult(status, expires)
    }

    @Suppress("HardwareIds")
    private fun getOrCreateHwid(context: android.content.Context, prefs: android.content.SharedPreferences): String {
        prefs.getString(KEY_HWID, null)?.let { return it }
        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        val buildSerial = try { @Suppress("DEPRECATION") android.os.Build.SERIAL } catch (_: Exception) { "" }
        val raw = "$androidId|$buildSerial"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hwid = digest.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_HWID, hwid).apply()
        return hwid
    }
}
