package com.hotel.blescanner.insurance;

import android.content.Context;
import android.content.SharedPreferences;
import android.bluetooth.le.ScanSettings;
import android.util.Log;

/**
 * Runtime-tunable configuration for INSURANCE mode.
 *
 * Follows the same SharedPreferences pattern as TransportConfig and ContextConfig.
 * All compile-time defaults are clearly marked as DEMO values.
 *
 * IMPORTANT DISTINCTION:
 *   Android DeviceMode = INSURANCE
 *   Insurance backend ingestion mode field = "observer"
 *   These are two different concepts. The "mode" field in the outbound JSON payload
 *   is always "observer" (the backend's ingestion mode). DeviceMode.INSURANCE is the
 *   Android operating mode that controls which subsystems are active.
 *
 * Configuration changes do not affect HOTEL beacon configuration.
 * backendBaseUrl must be a reachable address — physical Android devices cannot
 * use localhost. Use the local network IP or a configured development tunnel URL.
 */
public class InsuranceConfig {

    private static final String TAG        = "InsuranceConfig";
    private static final String PREFS_NAME = "insurance_config";

    // -------------------------------------------------------------------------
    // Compile-time DEMO defaults — clearly identified, never hard-coded in logic
    // -------------------------------------------------------------------------

    /** DEMO: default policy ID. Must be overridden via config for real deployments. */
    public static final String  DEMO_POLICY_ID                    = "POLICY-DEMO-001";
    /** DEMO: default phone number. Must be overridden via config. */
    public static final String  DEMO_PHONE_NUMBER                 = "+1234567890";
    /** DEMO: default vehicle beacon ID. Must be overridden via config. */
    public static final String  DEMO_VEHICLE_BEACON_ID            = "DEMO-CAR-BEACON-001";
    /** Physical hardware beacon ID mapped to the vehicle beacon in INSURANCE mode.
     *  ER26B00003 is the real beacon used for consultant testing. */
    public static final String  DEMO_PHYSICAL_BEACON_ID           = "ER26B00003";
    /** DEMO: default backend base URL. Physical devices cannot use localhost. */
    public static final String  DEMO_BACKEND_BASE_URL             = "";

    // -------------------------------------------------------------------------
    // Compile-time operational defaults
    // -------------------------------------------------------------------------

    /** Insurance mode disabled by default — must be explicitly enabled. */
    public static final boolean DEFAULT_ENABLED                   = false;

    /** Biometric freshness warning threshold: 25 minutes. */
    public static final int     DEFAULT_BIO_WARNING_MINUTES       = 25;
    /** Biometric freshness expired threshold: 30 minutes. */
    public static final int     DEFAULT_BIO_EXPIRED_MINUTES       = 30;

    /** Grace period before beacon loss is treated as vehicle disconnection: 30 seconds. */
    public static final int     DEFAULT_BEACON_LOSS_GRACE_SECS    = 30;
    /** Window to confirm stable vehicle association: 10 seconds. */
    public static final int     DEFAULT_ASSOC_CONFIRM_WINDOW_SECS = 10;
    /** Minimum BLE advertisements required within the confirmation window. */
    public static final int     DEFAULT_MIN_ADV_COUNT             = 3;
    /** Minimum RSSI (dBm) for a vehicle beacon to qualify for association. -80 = ~5–8m. */
    public static final int     DEFAULT_MIN_RSSI                  = -80;

    /**
     * BLE scan mode used during vehicle-entry detection (IDLE / CANDIDATE states).
     * LOW_LATENCY for a bounded detection window; transitions to BALANCED after association.
     */
    public static final int     DEFAULT_VERIFICATION_SCAN_MODE    = ScanSettings.SCAN_MODE_LOW_LATENCY;
    /** BLE scan mode used once vehicle is ASSOCIATED. */
    public static final int     DEFAULT_CONNECTED_SCAN_MODE       = ScanSettings.SCAN_MODE_BALANCED;

    /** Periodic verification interval: 5 minutes. Not a heartbeat. */
    public static final int     DEFAULT_PERIODIC_VERIFY_MINUTES   = 5;
    /** Minimum interval between published events: 10 seconds. */
    public static final int     DEFAULT_MIN_PUBLISH_INTERVAL_SECS = 10;

    /** HTTP connect timeout: 10 seconds. */
    public static final int     DEFAULT_CONNECT_TIMEOUT_SECS      = 10;
    /** HTTP read timeout: 15 seconds. */
    public static final int     DEFAULT_READ_TIMEOUT_SECS         = 15;

    /** Allow device GPS as fallback location source. */
    public static final boolean DEFAULT_ALLOW_GPS_FALLBACK        = true;
    /** Allow speed reporting in telemetry events. */
    public static final boolean DEFAULT_ALLOW_SPEED_REPORTING     = true;
    /** Retain pending events when backend is unreachable. */
    public static final boolean DEFAULT_RETAIN_PENDING            = true;
    /** Maximum pending events in the bounded queue. */
    public static final int     DEFAULT_MAX_PENDING_EVENTS        = 20;

    // -------------------------------------------------------------------------
    // SharedPreferences-backed instance
    // -------------------------------------------------------------------------

    private final SharedPreferences prefs;

    public InsuranceConfig(Context context) {
        this.prefs = (context != null)
            ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            : null;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public boolean isEnabled()                  { return getBool("ENABLED",                   DEFAULT_ENABLED); }
    public String  getBackendBaseUrl()          { return getString("BACKEND_BASE_URL",         DEMO_BACKEND_BASE_URL); }
    public String  getPolicyId()                { return getString("POLICY_ID",                DEMO_POLICY_ID); }
    public String  getPhoneNumber()             { return getString("PHONE_NUMBER",             DEMO_PHONE_NUMBER); }
    public String  getRegisteredVehicleBeaconId() { return getString("VEHICLE_BEACON_ID",      DEMO_VEHICLE_BEACON_ID); }
    /** Returns the physical hardware beacon ID that maps to the vehicle beacon.
     *  Allows real beacons (e.g. ER26B00003) to be used in place of the logical ID. */
    public String  getPhysicalBeaconId()          { return getString("PHYSICAL_BEACON_ID",     DEMO_PHYSICAL_BEACON_ID); }

    public int     getBioWarningMinutes()       { return getInt("BIO_WARNING_MINUTES",         DEFAULT_BIO_WARNING_MINUTES); }
    public int     getBioExpiredMinutes()       { return getInt("BIO_EXPIRED_MINUTES",         DEFAULT_BIO_EXPIRED_MINUTES); }
    public long    getBioWarningMs()            { return getBioWarningMinutes() * 60_000L; }
    public long    getBioExpiredMs()            { return getBioExpiredMinutes() * 60_000L; }

    public int     getBeaconLossGraceSecs()     { return getInt("BEACON_LOSS_GRACE_SECS",      DEFAULT_BEACON_LOSS_GRACE_SECS); }
    public long    getBeaconLossGraceMs()       { return getBeaconLossGraceSecs() * 1000L; }
    public int     getAssocConfirmWindowSecs()  { return getInt("ASSOC_CONFIRM_WINDOW_SECS",   DEFAULT_ASSOC_CONFIRM_WINDOW_SECS); }
    public long    getAssocConfirmWindowMs()    { return getAssocConfirmWindowSecs() * 1000L; }
    public int     getMinAdvCount()             { return getInt("MIN_ADV_COUNT",               DEFAULT_MIN_ADV_COUNT); }
    public int     getMinRssi()                 { return getInt("MIN_RSSI",                    DEFAULT_MIN_RSSI); }

    public int     getVerificationScanMode()    { return getInt("VERIFICATION_SCAN_MODE",      DEFAULT_VERIFICATION_SCAN_MODE); }
    public int     getConnectedScanMode()       { return getInt("CONNECTED_SCAN_MODE",         DEFAULT_CONNECTED_SCAN_MODE); }

    public int     getPeriodicVerifyMinutes()   { return getInt("PERIODIC_VERIFY_MINUTES",     DEFAULT_PERIODIC_VERIFY_MINUTES); }
    public long    getPeriodicVerifyMs()        { return getPeriodicVerifyMinutes() * 60_000L; }
    public int     getMinPublishIntervalSecs()  { return getInt("MIN_PUBLISH_INTERVAL_SECS",   DEFAULT_MIN_PUBLISH_INTERVAL_SECS); }
    public long    getMinPublishIntervalMs()    { return getMinPublishIntervalSecs() * 1000L; }

    public int     getConnectTimeoutSecs()      { return getInt("CONNECT_TIMEOUT_SECS",        DEFAULT_CONNECT_TIMEOUT_SECS); }
    public int     getReadTimeoutSecs()         { return getInt("READ_TIMEOUT_SECS",           DEFAULT_READ_TIMEOUT_SECS); }

    public boolean isAllowGpsFallback()         { return getBool("ALLOW_GPS_FALLBACK",         DEFAULT_ALLOW_GPS_FALLBACK); }
    public boolean isAllowSpeedReporting()      { return getBool("ALLOW_SPEED_REPORTING",      DEFAULT_ALLOW_SPEED_REPORTING); }
    public boolean isRetainPending()            { return getBool("RETAIN_PENDING",             DEFAULT_RETAIN_PENDING); }
    public int     getMaxPendingEvents()        { return getInt("MAX_PENDING_EVENTS",          DEFAULT_MAX_PENDING_EVENTS); }

    // -------------------------------------------------------------------------
    // Setters — persist to SharedPreferences
    // -------------------------------------------------------------------------

    public void setEnabled(boolean v)               { set("ENABLED",                v); }
    public void setBackendBaseUrl(String v)         { set("BACKEND_BASE_URL",       v); }
    public void setPolicyId(String v)               { set("POLICY_ID",              v); }
    public void setPhoneNumber(String v)            { set("PHONE_NUMBER",           v); }
    public void setRegisteredVehicleBeaconId(String v) { set("VEHICLE_BEACON_ID",  v); }
    public void setPhysicalBeaconId(String v)           { set("PHYSICAL_BEACON_ID", v); }

    public void set(String key, String value)  { if (prefs != null) prefs.edit().putString(key, value).apply(); }
    public void set(String key, int value)     { if (prefs != null) prefs.edit().putInt(key, value).apply(); }
    public void set(String key, boolean value) { if (prefs != null) prefs.edit().putBoolean(key, value).apply(); }

    public void resetToDefaults() { if (prefs != null) prefs.edit().clear().apply(); }

    /**
     * Populates config from a JSON string received via WebSocket.
     * Only recognised keys are applied; unknown keys are silently ignored.
     * Example: {"backendBaseUrl":"http://192.168.1.50:3000","policyId":"P-001",
     *           "phoneNumber":"+447700900000","vehicleBeaconId":"CAR-BLE-001"}
     */
    public void loadFromJson(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            if (obj.has("backendBaseUrl"))   setBackendBaseUrl(obj.getString("backendBaseUrl"));
            if (obj.has("policyId"))         setPolicyId(obj.getString("policyId"));
            if (obj.has("phoneNumber"))      setPhoneNumber(obj.getString("phoneNumber"));
            if (obj.has("vehicleBeaconId"))  setRegisteredVehicleBeaconId(obj.getString("vehicleBeaconId"));
            if (obj.has("physicalBeaconId"))  setPhysicalBeaconId(obj.getString("physicalBeaconId"));
            if (obj.has("enabled"))          setEnabled(obj.getBoolean("enabled"));
            if (obj.has("bioWarningMinutes"))  set("BIO_WARNING_MINUTES",  obj.getInt("bioWarningMinutes"));
            if (obj.has("bioExpiredMinutes"))  set("BIO_EXPIRED_MINUTES",  obj.getInt("bioExpiredMinutes"));
            if (obj.has("beaconLossGraceSecs")) set("BEACON_LOSS_GRACE_SECS", obj.getInt("beaconLossGraceSecs"));
            if (obj.has("periodicVerifyMinutes")) set("PERIODIC_VERIFY_MINUTES", obj.getInt("periodicVerifyMinutes"));
            if (obj.has("minRssi"))          set("MIN_RSSI", obj.getInt("minRssi"));
            Log.d(TAG, "[INSURANCE] Config loaded from JSON — backendBaseUrl='" + getBackendBaseUrl()
                + "' phone='" + getPhoneNumber()
                + "' beacon='" + getRegisteredVehicleBeaconId() + "'");
        } catch (Exception e) {
            Log.e(TAG, "[INSURANCE] Failed to parse insurance config JSON", e);
        }
    }

    // -------------------------------------------------------------------------
    // Validation — called before starting INSURANCE mode
    // -------------------------------------------------------------------------

    /**
     * Returns true if the minimum required fields are present and non-empty.
     * Logs a warning (without exposing sensitive values) for each missing field.
     */
    public boolean isValid() {
        boolean ok = true;
        String url = getBackendBaseUrl();
        if (url == null || url.trim().isEmpty()) {
            Log.w(TAG, "[INSURANCE] Config invalid: backendBaseUrl is missing");
            ok = false;
        } else if (url.contains("localhost") || url.contains("127.0.0.1")) {
            Log.w(TAG, "[INSURANCE] Config warning: backendBaseUrl points to localhost — "
                + "physical devices cannot reach the development machine via localhost. "
                + "Use the local network IP or a configured tunnel URL.");
            // Not a hard failure — allow for emulator use
        }
        if (getPolicyId() == null || getPolicyId().trim().isEmpty()) {
            Log.w(TAG, "[INSURANCE] Config invalid: policyId is missing");
            ok = false;
        }
        if (getPhoneNumber() == null || getPhoneNumber().trim().isEmpty()) {
            Log.w(TAG, "[INSURANCE] Config invalid: phoneNumber is missing");
            ok = false;
        }
        if (getRegisteredVehicleBeaconId() == null || getRegisteredVehicleBeaconId().trim().isEmpty()) {
            Log.w(TAG, "[INSURANCE] Config invalid: registeredVehicleBeaconId is missing");
            ok = false;
        }
        return ok;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String  getString(String key, String def)  { return prefs != null ? prefs.getString(key, def)  : def; }
    private int     getInt(String key, int def)        { return prefs != null ? prefs.getInt(key, def)     : def; }
    private boolean getBool(String key, boolean def)   { return prefs != null ? prefs.getBoolean(key, def) : def; }
}
