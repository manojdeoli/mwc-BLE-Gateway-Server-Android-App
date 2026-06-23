package com.hotel.blescanner.transport;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Manages biometric authentication state for Transport mode validation.
 *
 * This class contains NO UI code — it only reads and writes the last
 * authentication timestamp. The BiometricPrompt is launched exclusively
 * by MainActivity via {@link BiometricCallback} (Fix 3.4).
 *
 * Usage:
 *   // In service / ValidationController (no Activity needed):
 *   boolean fresh = biometricManager.isBiometricFresh(config.getBiometricMaxAgeMs());
 *
 *   // In MainActivity after successful BiometricPrompt:
 *   biometricManager.recordAuthTime();
 *   validationController.onBiometricSuccess();
 */
public class BiometricManager {

    private static final String TAG        = "BiometricManager";
    private static final String PREFS_NAME = "biometric_state";
    private static final String KEY_LAST_AUTH_MS = "last_auth_ms";

    private final SharedPreferences prefs;

    public BiometricManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns true if a successful biometric authentication occurred within
     * the given threshold window (ms).
     *
     * @param thresholdMs maximum acceptable age of the last auth in milliseconds
     */
    public boolean isBiometricFresh(long thresholdMs) {
        long lastAuthMs = prefs.getLong(KEY_LAST_AUTH_MS, 0L);
        if (lastAuthMs == 0L) return false;
        long ageMs = System.currentTimeMillis() - lastAuthMs;
        boolean fresh = ageMs <= thresholdMs;
        Log.d(TAG, "Biometric freshness check: ageMs=" + ageMs + " threshold=" + thresholdMs + " fresh=" + fresh);
        return fresh;
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
     * Useful when the session ends or the user logs out.
     */
    public void clearAuthTime() {
        prefs.edit().remove(KEY_LAST_AUTH_MS).apply();
        Log.d(TAG, "Biometric auth time cleared");
    }

    /** Returns the epoch ms of the last recorded authentication, or 0 if none. */
    public long getLastAuthTimeMs() {
        return prefs.getLong(KEY_LAST_AUTH_MS, 0L);
    }
}
