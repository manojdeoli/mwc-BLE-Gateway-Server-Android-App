package com.hotel.blescanner.transport;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a backend advisory message received over WebSocket.
 *
 * Expected JSON format:
 * {
 *   "validationRequired":  true,
 *   "rfDetectionRequired": true,
 *   "riskLevel":           "MEDIUM"
 * }
 *
 * Gson deserialises this directly from the raw WebSocket message.
 * {@link #isValid()} guards against garbage JSON that deserialises without
 * throwing but contains nonsensical field values.
 */
public class BackendAdvisory {

    private static final List<String> VALID_RISK_LEVELS =
        Arrays.asList("LOW", "MEDIUM", "HIGH");

    public boolean validationRequired;
    public boolean rfDetectionRequired;
    public String  riskLevel;

    /**
     * Optional journey identifier sent by the backend.
     * Used to correlate NFC and biometric validation events for the same
     * barrier passage. Falls back to beaconName in ValidationController
     * when absent.
     */
    public String journeyId;

    /**
     * Returns true if this advisory contains a recognised riskLevel value.
     * Rejects objects that Gson produced from unrelated JSON messages.
     */
    public boolean isValid() {
        return riskLevel != null && VALID_RISK_LEVELS.contains(riskLevel.toUpperCase());
    }
}
