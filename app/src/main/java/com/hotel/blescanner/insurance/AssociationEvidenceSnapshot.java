package com.hotel.blescanner.insurance;

/**
 * Lightweight diagnostic snapshot of all local association evidence.
 *
 * Used for diagnostics only — NOT transmitted to the backend.
 * The backend remains the confidence authority.
 *
 * Populated on demand by InsuranceSessionManager for /health and debug UI.
 */
public class AssociationEvidenceSnapshot {

    // Biometric
    public final BiometricFreshnessState biometricState;
    /** Age of last biometric auth in milliseconds, or -1 if unknown. */
    public final long                    biometricAgeMs;

    // Beacon
    public final InsuranceSessionState   beaconState;
    public final int                     advertisementCount;
    public final int                     confirmationWindowHits;
    public final double                  averageRssi;
    public final long                    associationDurationMs;
    public final long                    lastBeaconSeenAtMs;

    // Location
    public final boolean                 locationAvailable;
    public final boolean                 speedAvailable;
    public final String                  speedSource;   // "GPS" or null
    public final long                    lastSpeedUpdateMs;

    // Session
    public final InsuranceSessionState   sessionState;

    public AssociationEvidenceSnapshot(
            BiometricFreshnessState biometricState,
            long biometricAgeMs,
            InsuranceSessionState beaconState,
            int advertisementCount,
            int confirmationWindowHits,
            double averageRssi,
            long associationDurationMs,
            long lastBeaconSeenAtMs,
            boolean locationAvailable,
            boolean speedAvailable,
            String speedSource,
            long lastSpeedUpdateMs,
            InsuranceSessionState sessionState) {
        this.biometricState         = biometricState;
        this.biometricAgeMs         = biometricAgeMs;
        this.beaconState            = beaconState;
        this.advertisementCount     = advertisementCount;
        this.confirmationWindowHits = confirmationWindowHits;
        this.averageRssi            = averageRssi;
        this.associationDurationMs  = associationDurationMs;
        this.lastBeaconSeenAtMs     = lastBeaconSeenAtMs;
        this.locationAvailable      = locationAvailable;
        this.speedAvailable         = speedAvailable;
        this.speedSource            = speedSource;
        this.lastSpeedUpdateMs      = lastSpeedUpdateMs;
        this.sessionState           = sessionState;
    }
}
