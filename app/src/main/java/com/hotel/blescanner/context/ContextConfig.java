package com.hotel.blescanner.context;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Runtime-tunable configuration layer for the context scoring model.
 *
 * All values default to the compile-time constants in {@link ScoringConfig}.
 * Any value can be overridden at runtime via SharedPreferences without a
 * code rebuild — useful for tuning at different demo venues or RF environments.
 *
 * Usage:
 *   ContextConfig config = new ContextConfig(context);
 *   int carScore = config.getBtConnectedCarScore();
 *
 * To override a value (e.g. via a /config HTTP endpoint or adb shell):
 *   config.set("BT_CONNECTED_CAR_SCORE", 50);
 *
 * To reset all overrides back to defaults:
 *   config.resetToDefaults();
 */
public class ContextConfig {

    private static final String PREFS_NAME = "context_scoring_config";

    private final SharedPreferences prefs;

    public ContextConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------------------------
    // Getters — return override if present, else ScoringConfig default
    // -------------------------------------------------------------------------

    public int   getBtConnectedCarScore()        { return getInt("BT_CONNECTED_CAR_SCORE",          ScoringConfig.BT_CONNECTED_CAR_SCORE); }
    public int   getBtSlowSpeedCarPenalty()      { return getInt("BT_SLOW_SPEED_CAR_PENALTY",        ScoringConfig.BT_SLOW_SPEED_CAR_PENALTY); }
    public float getSpeedCarThreshold()          { return getFloat("SPEED_CAR_THRESHOLD",            ScoringConfig.SPEED_CAR_THRESHOLD); }
    public float getSpeedTransitThreshold()      { return getFloat("SPEED_TRANSIT_THRESHOLD",        ScoringConfig.SPEED_TRANSIT_THRESHOLD); }
    public float getSpeedWalkThreshold()         { return getFloat("SPEED_WALK_THRESHOLD",           ScoringConfig.SPEED_WALK_THRESHOLD); }
    public int   getSpeedCarScore()              { return getInt("SPEED_CAR_SCORE",                  ScoringConfig.SPEED_CAR_SCORE); }
    public int   getSpeedTransitScore()          { return getInt("SPEED_TRANSIT_SCORE",              ScoringConfig.SPEED_TRANSIT_SCORE); }
    public int   getSpeedWalkScore()             { return getInt("SPEED_WALK_SCORE",                 ScoringConfig.SPEED_WALK_SCORE); }
    public int   getMotionVehicleCarScore()      { return getInt("MOTION_VEHICLE_CAR_SCORE",         ScoringConfig.MOTION_VEHICLE_CAR_SCORE); }
    public int   getMotionVehicleTransitScore()  { return getInt("MOTION_VEHICLE_TRANSIT_SCORE",     ScoringConfig.MOTION_VEHICLE_TRANSIT_SCORE); }
    public int   getMotionWalkingScore()         { return getInt("MOTION_WALKING_SCORE",             ScoringConfig.MOTION_WALKING_SCORE); }
    public int   getBleProximityTransitScore()   { return getInt("BLE_PROXIMITY_TRANSIT_SCORE",      ScoringConfig.BLE_PROXIMITY_TRANSIT_SCORE); }
    public int   getConfidenceThreshold()        { return getInt("CONFIDENCE_THRESHOLD",             ScoringConfig.CONFIDENCE_THRESHOLD); }
    public float getMotionVarianceStill()        { return getFloat("MOTION_VARIANCE_STILL",          ScoringConfig.MOTION_VARIANCE_STILL_THRESHOLD); }
    public float getMotionVarianceVehicle()      { return getFloat("MOTION_VARIANCE_VEHICLE",        ScoringConfig.MOTION_VARIANCE_VEHICLE_THRESHOLD); }
    public int   getMotionWindowSize()           { return getInt("MOTION_WINDOW_SIZE",               ScoringConfig.MOTION_WINDOW_SIZE); }
    public long  getContextIntervalMs()          { return getLong("CONTEXT_INTERVAL_MS",             ScoringConfig.CONTEXT_INTERVAL_MS); }
    public long  getBleProximityWindowMs()       { return getLong("BLE_PROXIMITY_WINDOW_MS",         ScoringConfig.BLE_PROXIMITY_WINDOW_MS); }
    public long  getGpsMaxAgeMs()                { return getLong("GPS_MAX_AGE_MS",                  ScoringConfig.GPS_MAX_AGE_MS); }

    // -------------------------------------------------------------------------
    // Setters — persist override to SharedPreferences
    // -------------------------------------------------------------------------

    public void set(String key, int value)   { prefs.edit().putInt(key, value).apply(); }
    public void set(String key, float value) { prefs.edit().putFloat(key, value).apply(); }
    public void set(String key, long value)  { prefs.edit().putLong(key, value).apply(); }

    /** Removes all overrides — all getters will return ScoringConfig defaults. */
    public void resetToDefaults() {
        prefs.edit().clear().apply();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private int   getInt(String key, int def)     { return prefs.getInt(key, def); }
    private float getFloat(String key, float def) { return prefs.getFloat(key, def); }
    private long  getLong(String key, long def)   { return prefs.getLong(key, def); }
}
