package com.hotel.blescanner.context;

/**
 * Central configuration for the context scoring model.
 *
 * Provides static final defaults for all weights, thresholds and intervals.
 * At runtime, values can be overridden via {@link ContextConfig} which reads
 * from SharedPreferences — no code rebuild required for tuning.
 *
 * Designed to be replaced by a remote config or ML-derived weights in a
 * future iteration.
 */
public final class ScoringConfig {

    private ScoringConfig() {}

    // -------------------------------------------------------------------------
    // Bluetooth signal weights
    // -------------------------------------------------------------------------

    /** Score added to CAR when a Bluetooth audio device is connected. */
    public static final int BT_CONNECTED_CAR_SCORE = 40;

    /**
     * Penalty subtracted from CAR when Bluetooth is connected but speed is
     * below SPEED_WALK_THRESHOLD — likely stationary with headphones, not driving.
     */
    public static final int BT_SLOW_SPEED_CAR_PENALTY = 15;

    // -------------------------------------------------------------------------
    // Speed thresholds (km/h)
    // -------------------------------------------------------------------------

    /** Speed above which CAR score is applied. */
    public static final float SPEED_CAR_THRESHOLD = 25f;

    /** Speed above which TRANSIT score is applied (and below CAR threshold). */
    public static final float SPEED_TRANSIT_THRESHOLD = 8f;

    /** Speed above which WALKING score is applied (and below TRANSIT threshold). */
    public static final float SPEED_WALK_THRESHOLD = 2f;

    // -------------------------------------------------------------------------
    // Speed score contributions
    // -------------------------------------------------------------------------

    public static final int SPEED_CAR_SCORE     = 30;
    public static final int SPEED_TRANSIT_SCORE = 25;
    public static final int SPEED_WALK_SCORE    = 25;

    // -------------------------------------------------------------------------
    // Motion type score contributions
    // -------------------------------------------------------------------------

    public static final int MOTION_VEHICLE_CAR_SCORE     = 20;
    public static final int MOTION_VEHICLE_TRANSIT_SCORE = 15;
    public static final int MOTION_WALKING_SCORE         = 30;

    // -------------------------------------------------------------------------
    // BLE proximity score contribution
    // -------------------------------------------------------------------------

    /**
     * Score added to TRANSIT when a hotel BLE beacon is in proximity.
     * Rationale: beacon proximity inside a hotel suggests the user is
     * moving through a transit zone (lobby, elevator, gate).
     */
    public static final int BLE_PROXIMITY_TRANSIT_SCORE = 20;

    // -------------------------------------------------------------------------
    // Confidence threshold
    // -------------------------------------------------------------------------

    /**
     * Minimum confidence score required to commit to a mode.
     * Below this value the mode is reported as UNCERTAIN.
     */
    public static final int CONFIDENCE_THRESHOLD = 50;

    // -------------------------------------------------------------------------
    // Motion classifier thresholds
    // These are used by MotionAnalyzer to classify accelerometer variance.
    // -------------------------------------------------------------------------

    /**
     * Accelerometer magnitude variance below this value → STILL.
     * Unit: (m/s²)²
     */
    public static final float MOTION_VARIANCE_STILL_THRESHOLD   = 0.3f;

    /**
     * Accelerometer magnitude variance above this value → VEHICLE.
     * Values between STILL and VEHICLE thresholds → WALKING.
     * Unit: (m/s²)²
     */
    public static final float MOTION_VARIANCE_VEHICLE_THRESHOLD = 3.5f;

    /** Number of accelerometer samples kept in the rolling window. */
    public static final int MOTION_WINDOW_SIZE = 20;

    // -------------------------------------------------------------------------
    // Context publish interval
    // -------------------------------------------------------------------------

    /** How often (ms) the context engine evaluates and broadcasts a ContextEvent. */
    public static final long CONTEXT_INTERVAL_MS = 2000L;

    // -------------------------------------------------------------------------
    // BLE proximity decay window
    // -------------------------------------------------------------------------

    /**
     * Duration (ms) after the last beacon detection within which BLE proximity
     * is considered active. Once this window expires without a new detection,
     * bleProximity is treated as false automatically.
     *
     * Set to 3× the context evaluation interval so proximity persists across
     * at least one full evaluation cycle after the beacon disappears.
     */
    public static final long BLE_PROXIMITY_WINDOW_MS = 6000L;

    // -------------------------------------------------------------------------
    // GPS staleness threshold
    // -------------------------------------------------------------------------

    /**
     * Maximum age (ms) of a GPS fix before it is considered stale.
     * Stale fixes are ignored and speed is reported as unavailable rather
     * than returning a potentially misleading cached value.
     */
    public static final long GPS_MAX_AGE_MS = 10_000L;
}
