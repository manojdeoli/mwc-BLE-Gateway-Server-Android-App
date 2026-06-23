package com.hotel.blescanner.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Runtime-tunable configuration for all Transport mode behaviour.
 *
 * Compile-time defaults are defined as static finals.
 * Any value can be overridden at runtime via SharedPreferences without a rebuild —
 * same pattern as ContextConfig / ScoringConfig.
 *
 * Barrier beacon names are stored as a pipe-separated string:
 *   "HotelGate|StationGate|PlatformGate"
 */
public class TransportConfig {

    private static final String PREFS_NAME = "transport_config";

    // -------------------------------------------------------------------------
    // Compile-time defaults
    // -------------------------------------------------------------------------

    /** Beacons that trigger barrier proximity validation (pipe-separated). */
    public static final String DEFAULT_BARRIER_BEACONS = "HotelGate|StationGate";

    /** Minimum gap (ms) between two consecutive validation triggers. */
    public static final long VALIDATION_COOLDOWN_MS = 10_000L;

    /**
     * Revert to HOTEL if no advisory arrives within this window (ms).
     * Fail-safe — prevents permanent TRANSPORT lock.
     */
    public static final long ADVISORY_TIMEOUT_MS = 30_000L;

    /**
     * Biometric auth is considered fresh if performed within this window (ms).
     * Default: 30 minutes.
     */
    public static final long BIOMETRIC_MAX_AGE_MS = 30 * 60 * 1000L;

    /**
     * Minimum RSSI (dBm) required to trigger barrier validation.
     * Filters out far-field false positives — only close-proximity detections qualify.
     * -65 dBm ≈ ~2–4 metres in typical indoor conditions.
     * Fix 3.3: configurable per deployment environment.
     */
    public static final int BARRIER_RSSI_THRESHOLD = -65;

    /**
     * Transport session timeout (ms). If a session is active but no barrier
     * is detected within this window, the session is automatically ended.
     * Fix 3.4: explicit session awareness.
     * Default: 5 minutes.
     */
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * Advisory stability window (ms). A mode switch is only applied after
     * an advisory has been held consistently for this duration without contradiction.
     * Fix 3.5: prevents rapid flipping on unstable WebSocket connections.
     * Default: 1 second.
     */
    public static final long ADVISORY_STABILITY_WINDOW_MS = 1_000L;

    // -------------------------------------------------------------------------
    // SharedPreferences-backed instance
    // -------------------------------------------------------------------------

    private final SharedPreferences prefs;

    public TransportConfig(Context context) {
        this.prefs = (context != null)
            ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            : null;
    }

    public String[] getBarrierBeacons() {
        String raw = getString("BARRIER_BEACONS", DEFAULT_BARRIER_BEACONS);
        return raw.split("\\|");
    }

    public long  getValidationCooldownMs()      { return getLong("VALIDATION_COOLDOWN_MS",      VALIDATION_COOLDOWN_MS); }
    public long  getAdvisoryTimeoutMs()          { return getLong("ADVISORY_TIMEOUT_MS",          ADVISORY_TIMEOUT_MS); }
    public long  getBiometricMaxAgeMs()          { return getLong("BIOMETRIC_MAX_AGE_MS",          BIOMETRIC_MAX_AGE_MS); }
    public int   getBarrierRssiThreshold()       { return getInt( "BARRIER_RSSI_THRESHOLD",        BARRIER_RSSI_THRESHOLD); }
    public long  getSessionTimeoutMs()           { return getLong("SESSION_TIMEOUT_MS",            SESSION_TIMEOUT_MS); }
    public long  getAdvisoryStabilityWindowMs()  { return getLong("ADVISORY_STABILITY_WINDOW_MS",  ADVISORY_STABILITY_WINDOW_MS); }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    public void setBarrierBeacons(String[] beacons) {
        set("BARRIER_BEACONS", String.join("|", beacons));
    }

    public void set(String key, String value) { if (prefs != null) prefs.edit().putString(key, value).apply(); }
    public void set(String key, long value)   { if (prefs != null) prefs.edit().putLong(key, value).apply(); }
    public void set(String key, int value)    { if (prefs != null) prefs.edit().putInt(key, value).apply(); }

    public void resetToDefaults() { if (prefs != null) prefs.edit().clear().apply(); }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String getString(String key, String def) { return prefs != null ? prefs.getString(key, def)     : def; }
    private long   getLong(String key,   long def)   { return prefs != null ? prefs.getLong(key, def)       : def; }
    private int    getInt(String key,    int def)     { return prefs != null ? prefs.getInt(key, def)        : def; }
}
