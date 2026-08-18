package com.hotel.blescanner.insurance;

import com.google.gson.annotations.SerializedName;

/**
 * Typed model for the Insurance telemetry event payload.
 *
 * BACKWARD-COMPATIBILITY CONTRACT:
 *   The following fields are required by the existing Insurance backend and must
 *   never be renamed, removed, or have their types changed:
 *     policyId, phoneNumber, mode, isInitialEvent, auth,
 *     vehicleAssociation, location, currentSpeedMph
 *
 *   New fields (eventId, sessionId, eventType, eventTimestamp, appVersion,
 *   schemaVersion) are additive. If the backend rejects unknown fields, these
 *   can be omitted by setting them to null before serialisation.
 *
 * IMPORTANT DISTINCTION:
 *   - Android DeviceMode.INSURANCE = the Android operating mode
 *   - payload.mode = "observer"    = the Insurance backend's ingestion mode
 *   These are different concepts. The payload always uses "observer".
 *
 * Gson serialises null fields as absent when GsonBuilder.serializeNulls() is NOT set,
 * which is the default. Optional fields are therefore safe to leave null.
 */
public class InsuranceTelemetryEvent {

    // -------------------------------------------------------------------------
    // Required fields — existing backend contract
    // -------------------------------------------------------------------------

    /** Insurance policy identifier. */
    public String policyId;

    /**
     * Canonical phone number in E.164 format.
     * NEVER log this value in full. Use maskPhoneNumber() for diagnostics.
     */
    public String phoneNumber;

    /**
     * Backend ingestion mode. Always "observer" for this integration.
     * NOT the Android DeviceMode — see class-level doc.
     */
    public String mode = "observer";

    /** True only for the first successfully queued event of a session. */
    public boolean isInitialEvent;

    /** Authentication evidence snapshot. */
    public AuthEvidence auth;

    /** Vehicle association evidence. */
    public VehicleAssociationEvidence vehicleAssociation;

    /**
     * Optional device GPS fallback location.
     * Null when GPS is unavailable, permission is denied, or fallback is disabled.
     * Source is always DEVICE_GPS_FALLBACK — never labelled as CAMARA.
     */
    public LocationEvidence location;

    /**
     * Current speed in miles per hour.
     * Null when speed reporting is disabled or speed is unavailable.
     * Conversion: mph = m/s * 2.2369362920544
     */
    public Double currentSpeedMph;

    // -------------------------------------------------------------------------
    // Additive optional fields — backward-compatible
    // -------------------------------------------------------------------------

    /**
     * Stable unique event identifier. Generated once before queuing.
     * Reused on retries — never regenerated per retry attempt.
     * Backend should deduplicate by eventId.
     */
    public String eventId;

    /**
     * Session identifier for the current vehicle-association session.
     * Stable across retries and reconnections within the same session.
     */
    public String sessionId;

    /** Semantic event type. See InsuranceEventType enum. */
    public String eventType;

    /** UTC ISO 8601 timestamp of when the event was created. */
    public String eventTimestamp;

    // -------------------------------------------------------------------------
    // Inner models
    // -------------------------------------------------------------------------

    /**
     * Authentication evidence.
     *
     * biometricVerified: true if a fresh biometric or OS authentication exists.
     * lastVerifiedAt:    UTC ISO 8601 timestamp of the last authentication.
     *                    Null if no authentication has been recorded (UNKNOWN state).
     *
     * IMPORTANT: This field only indicates recent user-to-device authentication.
     * It does NOT claim the user is holding the phone or that the device is in-vehicle.
     * Do NOT transmit fingerprint, face image, or biometric template.
     */
    public static class AuthEvidence {
        public boolean biometricVerified;
        /** UTC ISO 8601. Null when freshness state is UNKNOWN. */
        public String  lastVerifiedAt;
        /** Optional: FRESH, AGEING, EXPIRED, UNKNOWN. Additive field. */
        public String  freshnessState;

        public AuthEvidence(boolean biometricVerified, String lastVerifiedAt, String freshnessState) {
            this.biometricVerified = biometricVerified;
            this.lastVerifiedAt    = lastVerifiedAt;
            this.freshnessState    = freshnessState;
        }
    }

    /**
     * Vehicle association evidence.
     *
     * beaconDetected: true when the configured vehicle beacon is currently detected.
     * beaconId:       the configured beacon identifier (not a BLE MAC address).
     */
    public static class VehicleAssociationEvidence {
        public boolean beaconDetected;
        /** The configured beacon identifier from InsuranceConfig. */
        public String  beaconId;
        /** Optional: UTC ISO 8601 of first detection in this session. Additive. */
        public String  firstSeenAt;
        /** Optional: UTC ISO 8601 of most recent detection. Additive. */
        public String  lastSeenAt;
        /** Optional: current association state name. Additive. */
        public String  associationState;

        public VehicleAssociationEvidence(boolean beaconDetected, String beaconId) {
            this.beaconDetected = beaconDetected;
            this.beaconId       = beaconId;
        }
    }

    /**
     * Device GPS fallback location evidence.
     *
     * Source is always DEVICE_GPS_FALLBACK.
     * NEVER label this as CAMARA location.
     * Do NOT place fake coordinates in production events.
     */
    public static class LocationEvidence {
        public double lat;
        public double lng;
        /** Always "DEVICE_GPS_FALLBACK" — never "CAMARA". */
        public String source = "DEVICE_GPS_FALLBACK";
        /** UTC ISO 8601 of when the GPS fix was captured. */
        public String capturedAt;
        /** Optional: GPS accuracy in metres. Additive. */
        public Double accuracyMetres;

        public LocationEvidence(double lat, double lng, String capturedAt) {
            this.lat         = lat;
            this.lng         = lng;
            this.capturedAt  = capturedAt;
        }
    }
}
