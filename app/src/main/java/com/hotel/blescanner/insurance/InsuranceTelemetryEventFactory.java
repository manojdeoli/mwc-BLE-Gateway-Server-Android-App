package com.hotel.blescanner.insurance;

import android.util.Log;
import com.hotel.blescanner.transport.BiometricManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Builds backward-compatible InsuranceTelemetryEvent payloads.
 *
 * Responsibilities:
 *   - Assemble all evidence fields from available sources.
 *   - Preserve the existing backend contract exactly.
 *   - Generate stable eventId before queuing (reused on retries).
 *   - Compute biometric freshness state from BiometricManager.
 *   - Convert speed from m/s to mph correctly.
 *   - Use UTC ISO 8601 for all timestamps.
 *   - Redact sensitive data from logs.
 *
 * IMPORTANT DISTINCTION:
 *   payload.mode = "observer" (backend ingestion mode — always this value)
 *   Android DeviceMode.INSURANCE (Android operating mode — not in payload)
 */
public class InsuranceTelemetryEventFactory {

    private static final String TAG = "[INS] EventFactory";

    private static final SimpleDateFormat ISO_FORMAT;
    static {
        ISO_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final InsuranceConfig    config;
    private final BiometricManager   biometricManager;

    public InsuranceTelemetryEventFactory(InsuranceConfig config,
                                          BiometricManager biometricManager) {
        this.config           = config;
        this.biometricManager = biometricManager;
    }

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Builds a complete telemetry event.
     *
     * @param eventType        semantic event type
     * @param sessionId        stable session identifier
     * @param isInitialEvent   true only for the first event of a session
     * @param assocSnapshot    current vehicle association snapshot
     * @param locationProvider optional GPS fallback provider (may be null)
     * @return fully populated event ready for queuing
     */
    public InsuranceTelemetryEvent build(
            InsuranceEventType eventType,
            String sessionId,
            boolean isInitialEvent,
            VehicleAssociationController.AssociationSnapshot assocSnapshot,
            InsuranceLocationProvider locationProvider) {

        InsuranceTelemetryEvent event = new InsuranceTelemetryEvent();

        // Required fields — existing backend contract
        event.policyId        = config.getPolicyId();
        event.phoneNumber     = config.getPhoneNumber();
        event.mode            = "observer";  // backend ingestion mode — always "observer"
        event.isInitialEvent  = isInitialEvent;

        // Additive optional fields
        event.eventId         = UUID.randomUUID().toString();  // stable — caller must preserve for retries
        event.sessionId       = sessionId;
        event.eventType       = eventType.name();
        event.eventTimestamp  = toIso8601(System.currentTimeMillis());

        // Auth evidence
        event.auth = buildAuthEvidence();

        // Vehicle association evidence
        event.vehicleAssociation = buildAssociationEvidence(assocSnapshot);

        // Location evidence — optional GPS fallback only
        if (locationProvider != null) {
            event.location = locationProvider.getLocationEvidence();
        }

        // Speed — optional, only when allowed and available
        if (config.isAllowSpeedReporting() && locationProvider != null) {
            event.currentSpeedMph = locationProvider.getSpeedMph();
        }

        Log.d(TAG, "Built event: type=" + eventType.name()
            + " session=" + maskId(sessionId)
            + " initial=" + isInitialEvent
            + " beacon=" + assocSnapshot.beaconDetected
            + " auth=" + (event.auth != null ? event.auth.freshnessState : "null")
            + " hasLocation=" + (event.location != null)
            + " speedMph=" + event.currentSpeedMph);

        return event;
    }

    // -------------------------------------------------------------------------
    // Auth evidence
    // -------------------------------------------------------------------------

    /**
     * Builds authentication evidence from BiometricManager.
     *
     * biometricVerified = true only when freshness state is FRESH or AGEING.
     * lastVerifiedAt    = null when state is UNKNOWN (no auth recorded).
     *
     * NEVER fabricates a timestamp. NEVER transmits biometric template data.
     */
    public InsuranceTelemetryEvent.AuthEvidence buildAuthEvidence() {
        BiometricFreshnessState freshness = computeFreshnessState();
        boolean verified = (freshness == BiometricFreshnessState.FRESH
                         || freshness == BiometricFreshnessState.AGEING);

        String lastVerifiedAt = null;
        if (freshness != BiometricFreshnessState.UNKNOWN) {
            long lastAuthMs = biometricManager.getMostRecentAuthTimeMs();
            if (lastAuthMs > 0) {
                lastVerifiedAt = toIso8601(lastAuthMs);
            }
        }

        return new InsuranceTelemetryEvent.AuthEvidence(
            verified, lastVerifiedAt, freshness.name());
    }

    /**
     * Computes the current biometric freshness state.
     * Returns UNKNOWN if no authentication has been recorded.
     */
    public BiometricFreshnessState computeFreshnessState() {
        long lastAuthMs = biometricManager.getMostRecentAuthTimeMs();
        if (lastAuthMs == 0L) return BiometricFreshnessState.UNKNOWN;

        long ageMs = System.currentTimeMillis() - lastAuthMs;
        if (ageMs <= config.getBioWarningMs())  return BiometricFreshnessState.FRESH;
        if (ageMs <= config.getBioExpiredMs())  return BiometricFreshnessState.AGEING;
        return BiometricFreshnessState.EXPIRED;
    }

    // -------------------------------------------------------------------------
    // Association evidence
    // -------------------------------------------------------------------------

    private InsuranceTelemetryEvent.VehicleAssociationEvidence buildAssociationEvidence(
            VehicleAssociationController.AssociationSnapshot snapshot) {

        InsuranceTelemetryEvent.VehicleAssociationEvidence evidence =
            new InsuranceTelemetryEvent.VehicleAssociationEvidence(
                snapshot.beaconDetected,
                snapshot.beaconId != null ? snapshot.beaconId : config.getRegisteredVehicleBeaconId());

        if (snapshot.firstSeenAtMs > 0) {
            evidence.firstSeenAt = toIso8601(snapshot.firstSeenAtMs);
        }
        if (snapshot.lastSeenAtMs > 0) {
            evidence.lastSeenAt = toIso8601(snapshot.lastSeenAtMs);
        }
        if (snapshot.state != null) {
            evidence.associationState = snapshot.state.name();
        }
        if (snapshot.lastRssi != Integer.MIN_VALUE) {
            evidence.rssi = snapshot.lastRssi;
        }
        return evidence;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Formats epoch ms as UTC ISO 8601 string. Thread-safe via synchronization. */
    public static synchronized String toIso8601(long epochMs) {
        return ISO_FORMAT.format(new Date(epochMs));
    }

    /**
     * Masks a sensitive identifier for log output.
     * Shows first 4 chars + "..." to aid debugging without exposing full value.
     */
    public static String maskId(String id) {
        if (id == null || id.length() <= 4) return "***";
        return id.substring(0, 4) + "...";
    }

    /**
     * Masks a phone number for log output.
     * Shows country code + last 2 digits only.
     * e.g. "+1234567890" → "+12...90"
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return phone.substring(0, Math.min(3, phone.length()))
            + "..." + phone.substring(phone.length() - 2);
    }
}
