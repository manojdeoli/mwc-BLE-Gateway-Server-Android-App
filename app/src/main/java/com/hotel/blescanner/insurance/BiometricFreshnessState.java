package com.hotel.blescanner.insurance;

/**
 * Semantic freshness state for biometric/OS authentication evidence.
 *
 * Used by InsuranceSessionManager to detect state-boundary changes
 * and publish AUTH_FRESHNESS_CHANGED events only when the state transitions.
 *
 * FRESH   : authenticated within the warning threshold (default < 25 min ago).
 * AGEING  : authenticated between warning and expired thresholds (25–30 min ago).
 * EXPIRED : authentication older than the expired threshold (> 30 min ago).
 * UNKNOWN : no authentication has been recorded — timestamp is unavailable.
 *           Must NOT be treated as verified. Do not fabricate a timestamp.
 */
public enum BiometricFreshnessState {
    FRESH,
    AGEING,
    EXPIRED,
    UNKNOWN
}
