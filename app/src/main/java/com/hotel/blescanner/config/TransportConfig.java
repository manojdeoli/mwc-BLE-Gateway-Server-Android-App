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
 * Barrier beacon names and station SSIDs are stored as pipe-separated strings.
 */
public class TransportConfig {

    private static final String PREFS_NAME = "transport_config";

    // -------------------------------------------------------------------------
    // Compile-time defaults
    // -------------------------------------------------------------------------

    /** Beacons that trigger barrier proximity evaluation (pipe-separated).
     * @deprecated Replaced by BeaconConfigManager.isBarrierBeacon().
     *             BeaconConfigManager is now the single source of truth for barrier
     *             beacon identity. This value is retained as a last-resort fallback
     *             only if BeaconConfigManager is not yet initialised.
     */
    @Deprecated
    public static final String DEFAULT_BARRIER_BEACONS = "HotelGate|StationGate";

    /** Minimum gap (ms) between two consecutive barrier evaluations. */
    public static final long VALIDATION_COOLDOWN_MS = 10_000L;

    /** Revert to HOTEL if no advisory arrives within this window (ms). */
    public static final long ADVISORY_TIMEOUT_MS = 30_000L;

    /**
     * Biometric auth is considered fresh if performed within this window (ms).
     * Default: 30 minutes.
     */
    public static final long BIOMETRIC_MAX_AGE_MS = 30 * 60 * 1000L;

    /**
     * Minimum RSSI (dBm) required to trigger barrier evaluation.
     * Filters far-field detections — only close-proximity qualifies.
     * -65 dBm ≈ ~2–4 metres in typical indoor conditions.
     */
    public static final int BARRIER_RSSI_THRESHOLD = -65;

    /**
     * Transport session timeout (ms).
     * If a session is active but no barrier is detected within this window,
     * the session ends automatically and mode reverts to HOTEL.
     */
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * Advisory stability window (ms).
     * Mode switch is applied only after an advisory has been stable for this
     * duration — prevents mode flipping on unstable WebSocket connections.
     */
    public static final long ADVISORY_STABILITY_WINDOW_MS = 1_000L;

    /**
     * Gap 2.3: BLE-absent fallback timeout (ms).
     * If the transport session is active, userNearStation=true (network confirms
     * the user is at the station), but no BLE barrier beacon is detected within
     * this window, the device broadcasts an exitSignal CLEAR using network-only
     * confidence. Keeps the system functional when BLE beacons are unavailable.
     * Default: 30 seconds.
     */
    public static final long BLE_ABSENT_FALLBACK_MS = 30_000L;

    /**
     * Gap 2.4: Validation method label broadcast in validation events.
     * Default: "NFC" — the current hardware scan path.
     * Overridable to "RFID", "OTHER" for future deployment flexibility.
     * Device broadcasts this label as-is — no internal logic branches on it.
     */
    public static final String DEFAULT_VALIDATION_METHOD = "NFC";

    /**
     * Gap 2.1: Known transport station WiFi SSIDs (pipe-separated).
     * NetworkProximityMonitor compares connected SSID against this list.
     * When a match is found → userNearStation=true → BLE scan activates.
     *
     * Default is empty — no station SSIDs known at compile time.
     * Deployments must configure this via set("STATION_SSIDS", ...) or
     * the backend can send a config advisory.
     *
     * Example: "StationWiFi|TransportNet|PlatformAP"
     */
    public static final String DEFAULT_STATION_SSIDS = "";

    /**
     * Gap 2.1: Minimum WiFi RSSI (dBm) required for a station SSID match
     * to be considered a valid "user at station" signal.
     * Prevents false positives from weak far-range WiFi leakage.
     * -70 dBm is a reasonable indoor threshold.
     */
    public static final int NEAR_STATION_RSSI_THRESHOLD = -70;

    // -------------------------------------------------------------------------
    // SharedPreferences-backed instance
    // -------------------------------------------------------------------------

    private final SharedPreferences prefs;

    public TransportConfig(Context context) {
        this.prefs = (context != null)
            ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            : null;
    }

    /**
     * @deprecated Replaced by BeaconConfigManager.isBarrierBeacon().
     *             Retained as last-resort fallback only.
     */
    @Deprecated
    public String[] getBarrierBeacons() {
        String raw = getString("BARRIER_BEACONS", DEFAULT_BARRIER_BEACONS);
        return raw.split("\\|");
    }

    public long  getValidationCooldownMs()     { return getLong("VALIDATION_COOLDOWN_MS",     VALIDATION_COOLDOWN_MS); }
    public long  getAdvisoryTimeoutMs()         { return getLong("ADVISORY_TIMEOUT_MS",         ADVISORY_TIMEOUT_MS); }
    public long  getBiometricMaxAgeMs()         { return getLong("BIOMETRIC_MAX_AGE_MS",         BIOMETRIC_MAX_AGE_MS); }
    public int   getBarrierRssiThreshold()      { return getInt( "BARRIER_RSSI_THRESHOLD",       BARRIER_RSSI_THRESHOLD); }
    public long  getSessionTimeoutMs()          { return getLong("SESSION_TIMEOUT_MS",           SESSION_TIMEOUT_MS); }
    public long   getAdvisoryStabilityWindowMs() { return getLong(  "ADVISORY_STABILITY_WINDOW_MS", ADVISORY_STABILITY_WINDOW_MS); }
    public long   getBleAbsentFallbackMs()        { return getLong(  "BLE_ABSENT_FALLBACK_MS",       BLE_ABSENT_FALLBACK_MS); }
    public String getValidationMethod()           { return getString("VALIDATION_METHOD",            DEFAULT_VALIDATION_METHOD); }
    public int    getNearStationRssiThreshold()   { return getInt(   "NEAR_STATION_RSSI_THRESHOLD",  NEAR_STATION_RSSI_THRESHOLD); }

    /**
     * Returns the configured station SSIDs as an array.
     * Empty array if no SSIDs are configured.
     */
    public String[] getStationSsids() {
        String raw = getString("STATION_SSIDS", DEFAULT_STATION_SSIDS);
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        return raw.split("\\|");
    }

    // -------------------------------------------------------------------------
    // Setters — persist override to SharedPreferences
    // -------------------------------------------------------------------------

    public void setBarrierBeacons(String[] beacons) { set("BARRIER_BEACONS", String.join("|", beacons)); }
    public void setStationSsids(String[] ssids)      { set("STATION_SSIDS",   String.join("|", ssids)); }

    public void set(String key, String value) { if (prefs != null) prefs.edit().putString(key, value).apply(); }
    public void set(String key, long value)   { if (prefs != null) prefs.edit().putLong(key, value).apply(); }
    public void set(String key, int value)    { if (prefs != null) prefs.edit().putInt(key, value).apply(); }

    public void resetToDefaults() { if (prefs != null) prefs.edit().clear().apply(); }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String getString(String key, String def) { return prefs != null ? prefs.getString(key, def) : def; }
    private long   getLong(String key,   long def)   { return prefs != null ? prefs.getLong(key, def)   : def; }
    private int    getInt(String key,    int def)     { return prefs != null ? prefs.getInt(key, def)    : def; }
}
