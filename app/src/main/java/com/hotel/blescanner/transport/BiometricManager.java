package com.hotel.blescanner.transport;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Manages biometric authentication state for Transport mode validation.
 *
 * Freshness is checked from two sources (whichever is most recent):
 *   1. App-internal: lastAuthMs stored in SharedPreferences after our own prompt
 *   2. OS-level: BiometricManager.getLastAuthenticationTime() — API 35 (Android 15)
 *      This means unlocking the phone with fingerprint counts as fresh,
 *      so the biometric prompt is skipped if the user recently unlocked.
 */
public class BiometricManager {

    private static final String TAG        = "BiometricManager";
    private static final String PREFS_NAME = "biometric_state";
    private static final String KEY_LAST_AUTH_MS = "last_auth_ms";

    private final SharedPreferences prefs;
    private final Context           context;

    public BiometricManager(Context context) {
        this.prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.context = context;
    }

    /**
     * Returns true if a successful biometric authentication occurred within
     * the given threshold window (ms).
     *
     * Checks both app-internal auth time and OS-level biometric auth time
     * (Android 15+). Whichever is most recent is used.
     *
     * @param thresholdMs maximum acceptable age of the last auth in milliseconds
     */
    public boolean isBiometricFresh(long thresholdMs) {
        long now        = System.currentTimeMillis();
        long lastAuthMs = getMostRecentAuthTimeMs();

        if (lastAuthMs == 0L) {
            Log.d(TAG, "Biometric freshness: no auth recorded — stale");
            return false;
        }
        long ageMs = now - lastAuthMs;
        boolean fresh = ageMs <= thresholdMs;
        Log.d(TAG, "Biometric freshness: ageMs=" + ageMs
            + " threshold=" + thresholdMs + " fresh=" + fresh);
        return fresh;
    }

    /**
     * Returns the most recent authentication time from either:
     *   - App-internal SharedPreferences (our own BiometricPrompt)
     *   - OS-level BiometricManager (Android 15+: device unlock, other apps)
     * Returns 0 if no authentication has been recorded.
     */
    private long getMostRecentAuthTimeMs() {
        long appAuthMs = prefs.getLong(KEY_LAST_AUTH_MS, 0L);
        long osAuthMs  = getOsLastAuthTimeMs();
        return Math.max(appAuthMs, osAuthMs);
    }

    /**
     * Reads the OS-level last biometric authentication time.
     * Uses BiometricManager.getLastAuthenticationTime() available on API 35+.
     * Returns 0 on older Android versions or if no OS auth has been recorded.
     */
    private long getOsLastAuthTimeMs() {
        if (Build.VERSION.SDK_INT < 35) return 0L;
        try {
            android.hardware.biometrics.BiometricManager bm =
                (android.hardware.biometrics.BiometricManager)
                    context.getSystemService(Context.BIOMETRIC_SERVICE);
            if (bm == null) return 0L;
            // BIOMETRIC_STRONG covers fingerprint + face on supported devices
            long lastAuth = bm.getLastAuthenticationTime(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
            if (lastAuth > 0) {
                Log.d(TAG, "OS biometric last auth: " + lastAuth
                    + " (age " + (System.currentTimeMillis() - lastAuth) + "ms)");
            }
            return lastAuth > 0 ? lastAuth : 0L;
        } catch (Exception e) {
            Log.w(TAG, "Could not read OS biometric auth time: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Records the current time as the last successful biometric authentication.
     * Called by MainActivity immediately after BiometricPrompt reports success.
     */
    public void recordAuthTime() {
        prefs.edit().putLong(KEY_LAST_AUTH_MS, System.currentTimeMillis()).apply();
        Log.d(TAG, "Biometric auth time recorded");
    }

    /**
     * Clears the stored authentication time.
     */
    public void clearAuthTime() {
        prefs.edit().remove(KEY_LAST_AUTH_MS).apply();
        Log.d(TAG, "Biometric auth time cleared");
    }

    /** Returns the epoch ms of the last recorded app-internal authentication, or 0 if none. */
    public long getLastAuthTimeMs() {
        return prefs.getLong(KEY_LAST_AUTH_MS, 0L);
    }
}
