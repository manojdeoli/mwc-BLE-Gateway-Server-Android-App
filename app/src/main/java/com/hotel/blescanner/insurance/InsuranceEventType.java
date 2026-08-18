package com.hotel.blescanner.insurance;

/**
 * Semantic event types for Insurance telemetry.
 *
 * Each type maps to a specific publish trigger in InsuranceSessionManager.
 * The string name() is used directly in the outbound JSON eventType field.
 */
public enum InsuranceEventType {

    /**
     * Vehicle association confirmed for the first time in a session.
     * isInitialEvent=true. beaconDetected=true.
     * Trigger: stable beacon detection criteria satisfied.
     */
    VEHICLE_ASSOCIATION_STARTED,

    /**
     * Vehicle association re-confirmed after a degraded period.
     * isInitialEvent=false. beaconDetected=true.
     */
    VEHICLE_ASSOCIATION_CONFIRMED,

    /**
     * Beacon signal degraded (temporary loss within grace period).
     * isInitialEvent=false. beaconDetected=false.
     */
    VEHICLE_ASSOCIATION_DEGRADED,

    /**
     * Beacon absent beyond grace period — vehicle disconnected.
     * isInitialEvent=false. beaconDetected=false.
     */
    VEHICLE_ASSOCIATION_LOST,

    /**
     * Biometric freshness state crossed a meaningful boundary.
     * Published only on state change: UNKNOWN→FRESH, FRESH→AGEING, AGEING→EXPIRED.
     * NOT published on every timer tick.
     */
    AUTH_FRESHNESS_CHANGED,

    /**
     * Significant location or speed change.
     * Published only when configured conditions are met and minimum interval respected.
     */
    LOCATION_MILESTONE,

    /**
     * Speed changed beyond configured threshold.
     * Published only when allowSpeedReporting=true and minimum interval respected.
     */
    SPEED_CHANGED,

    /**
     * Low-frequency periodic verification event.
     * Configurable interval (default 5 minutes). Not a heartbeat.
     */
    PERIODIC_VERIFICATION,

    /**
     * Session ended cleanly.
     * isInitialEvent=false. Final state snapshot.
     */
    SESSION_ENDED
}
