package com.hotel.blescanner.insurance;

/**
 * States for the Insurance vehicle-association session lifecycle.
 *
 * Transitions (see InsuranceSessionManager for full state machine):
 *
 *   IDLE
 *     → mode activated
 *     → WAITING_FOR_VEHICLE (no session ID, no timers beyond lightweight monitoring)
 *
 *   WAITING_FOR_VEHICLE
 *     → qualifying configured vehicle beacon detected
 *     → CANDIDATE_VEHICLE_DETECTED
 *
 *   CANDIDATE_VEHICLE_DETECTED
 *     → stable detection criteria satisfied (min count + window)
 *     → VEHICLE_ASSOCIATED
 *
 *   CANDIDATE_VEHICLE_DETECTED
 *     → beacon no longer qualifies (RSSI too weak / absent)
 *     → IDLE
 *
 *   VEHICLE_ASSOCIATED
 *     → temporary beacon loss within grace period
 *     → ASSOCIATION_DEGRADED
 *
 *   ASSOCIATION_DEGRADED
 *     → beacon re-detected within grace period
 *     → VEHICLE_ASSOCIATED
 *
 *   VEHICLE_ASSOCIATED / ASSOCIATION_DEGRADED
 *     → beacon absent beyond grace period
 *     → VEHICLE_DISCONNECTED
 *
 *   VEHICLE_DISCONNECTED
 *     → publish VEHICLE_ASSOCIATION_LOST + SESSION_ENDED
 *     → SESSION_ENDED
 *     → cleanly return to IDLE
 */
public enum InsuranceSessionState {
    /** Mode active, no vehicle beacon seen yet. No session ID exists. */
    IDLE,
    /** Mode active, waiting for first qualifying beacon advertisement. */
    WAITING_FOR_VEHICLE,
    CANDIDATE_VEHICLE_DETECTED,
    VEHICLE_ASSOCIATED,
    ASSOCIATION_DEGRADED,
    VEHICLE_DISCONNECTED,
    SESSION_ENDED
}
