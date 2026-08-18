package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;

@SuppressWarnings("unused")
public final class SzabyLicensePatch {

    private static final String LICENSE_SERVER = "https://szaby-license-server.onrender.com";
    private static final String PREFS_NAME = "szaby_license";
    private static final long GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000;

    private static volatile Boolean licenseValid = null;

    // Called by the settings framework when the license key setting changes.
    public static void onLicenseKeyChanged(Context context) {
        // Run validation in background to avoid blocking UI.
        new Thread(() -> {
            try {
                boolean result = validateLicense(context);
                licenseValid = result;
                Logger.printInfo(() -> "Szaby license validation result: " + result);
            } catch (Exception e) {
                Logger.printException(() -> "Szaby license validation failed", e);
            }
        }).start();
    }

    /**
     * Called on app startup to check if the license is valid.
     * Returns true if patches should be active.
     */
    public static boolean isLicenseValid(Context context) {
        if (licenseValid != null) return licenseValid;

        try {
            licenseValid = validateLicense(context);
        } catch (Exception e) {
            licenseValid = handleOffline(context);
        }

        return licenseValid;
    }

    private static boolean validateLicense(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Check permanent ban
        if (prefs.getBoolean("is_banned", false)) return false;

        // Get stored key from Szaby settings
        String key = Setting.preferences.getString("szaby_license_key", null);
        if (key == null || key.isEmpty()) return false;

        String hwid = getOrCreateHwid(context, prefs);

        String urlStr = LICENSE_SERVER + "/api/validate?key="
                + URLEncoder.encode(key, "UTF-8")
                + "&hwid=" + URLEncoder.encode(hwid, "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int code = conn.getResponseCode();
        if (code != 200) return false;

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        String body = sb.toString();

        // Parse status
        Pattern p = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(body);
        String status = m.find() ? m.group(1) : "ERROR";

        // Parse expires_at
        Pattern pe = Pattern.compile("\"expires_at\"\\s*:\\s*\"([^\"]+)\"");
        Matcher me = pe.matcher(body);
        String expiresAt = me.find() ? me.group(1) : "";

        switch (status) {
            case "VALID":
            case "ACTIVATED":
                prefs.edit()
                        .putLong("last_valid_ts", System.currentTimeMillis())
                        .putString("expires_at", expiresAt)
                        .apply();

                // Update status summary in settings
                updateStatusSummary(context, "Aktiv - Lejar: " + expiresAt.split("T")[0]);
                return true;

            case "BANNED":
                prefs.edit().putBoolean("is_banned", true).apply();
                updateStatusSummary(context, "Ez az eszkoz ki lett tiltva.");
                return false;

            case "EXPIRED":
                updateStatusSummary(context, "Lejart kulcs. Kerj ujat!");
                return false;

            default:
                updateStatusSummary(context, "Ervenytelen kulcs.");
                return false;
        }
    }

    private static boolean handleOffline(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastValid = prefs.getLong("last_valid_ts", 0);
        if (lastValid == 0) return false;
        boolean inGrace = (System.currentTimeMillis() - lastValid) < GRACE_PERIOD_MS;
        if (inGrace) {
            updateStatusSummary(context, "Offline mod - meg " +
                    ((GRACE_PERIOD_MS - (System.currentTimeMillis() - lastValid)) / (24*60*60*1000)) + " napig ervenyes");
        }
        return inGrace;
    }

    private static void updateStatusSummary(Context context, String status) {
        try {
            Setting.preferences.edit().putString("szaby_license_status_summary", status).apply();
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("HardwareIds")
    private static String getOrCreateHwid(Context context, SharedPreferences prefs) {
        String existing = prefs.getString("device_hwid", null);
        if (existing != null) return existing;

        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "";

        String serial;
        try { serial = Build.SERIAL; } catch (Exception e) { serial = ""; }

        String cpuAbi = String.join(",", Build.SUPPORTED_ABIS);
        String raw = androidId + "|" + serial + "|" + cpuAbi + "|" + Build.BOARD + "|" + Build.HARDWARE;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            String hwid = sb.toString();
            prefs.edit().putString("device_hwid", hwid).apply();
            return hwid;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
