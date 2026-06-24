package com.hotel.blescanner.transport;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a backend advisory message received over WebSocket.
 *
 * Expected JSON format:
 * {
 *   "validationRequired":    false,
 *   "rfDetectionRequired":   true,
 *   "riskLevel":             "LOW",
 *   "stage":                 "EXIT",
 *   "journeyId":             "journey-20240418-001",
 *   "correlationScore":      15,
 *   "correlationConfidence": "HIGH"
 * }
 *
 * Key semantics:
 *
 *   stage = "ENTRY" → barrier always clear, no BLE activation, no session.
 *   stage = "EXIT"  → normal transport flow applies.
 *   stage absent    → treated as ENTRY (safe default — always clear).
 *
 *   validationRequired = output of backend Google Maps correlation.
 *     false → journey clear     → exitSignal CLEAR
 *     true  → journey ambiguous → exitSignal AMBIGUOUS → require scan
 *
 *   rfDetectionRequired → controls whether TRANSPORT mode is activated.
 *     true  → switch to TRANSPORT mode (EXIT stage only)
 *     false → revert to HOTEL mode
 *
 *   correlationScore (0–100, informational only):
 *     The backend's raw ambiguity measure derived from Google Maps correlation.
 *     Device logs this but NEVER branches on it — all decisions come from
 *     validationRequired which the backend derives from this score.
 *
 *   correlationConfidence ("HIGH" / "MEDIUM" / "LOW", informational only):
 *     Human-readable confidence tier derived by the backend from correlationScore.
 *     Strengthens explainability and trust — device logs it, never branches on it.
 *     Backend is the single source of truth for this classification.
 *
 * Gson deserialises this directly from the raw WebSocket message.
 * {@link #isValid()} guards against garbage JSON.
 */
public class BackendAdvisory {

    private static final List<String> VALID_RISK_LEVELS =
        Arrays.asList("LOW", "MEDIUM", "HIGH");

    /** Whether the backend's journey correlation is ambiguous → scan required at EXIT. */
    public boolean validationRequired;

    /** Whether TRANSPORT mode should be active (EXIT stage only). */
    public boolean rfDetectionRequired;

    /** Risk level — must be LOW, MEDIUM, or HIGH. Used by isValid() only. */
    public String riskLevel;

    /**
     * Journey stage: "ENTRY" or "EXIT".
     * Absent / null is treated as ENTRY — barriers always clear, no scanning.
     * Only EXIT stage activates transport session and BLE scanning.
     */
    public String stage;

    /**
     * Optional journey identifier.
     * Correlates scan validation events back to this specific advisory.
     * Falls back to beaconName in ValidationController when absent.
     */
    public String journeyId;

    /**
     * Backend Google Maps correlation ambiguity score (0 = certain, 100 = ambiguous).
     * Informational only — device logs it, never branches on it.
     * All barrier decisions come from validationRequired.
     * Gap 2.2: exposed to strengthen explainability and trust in the system.
     */
    public int correlationScore;

    /**
     * Gap 2.2: Backend correlation confidence tier derived from correlationScore.
     * Values: "HIGH" | "MEDIUM" | "LOW"
     *
     * Backend is the single source of truth — it derives this from the
     * Google Maps correlation result. Device logs it for transparency and
     * audit purposes, but NEVER branches on it.
     *
     * Example mapping (backend responsibility, not device):
     *   correlationScore 0–30  → "HIGH"   confidence (journey is clear)
     *   correlationScore 31–65 → "MEDIUM" confidence
     *   correlationScore 66–100→ "LOW"    confidence (journey is ambiguous)
     */
    public String correlationConfidence;

    /**
     * Returns true if this advisory contains a recognised riskLevel.
     * Rejects garbage JSON that Gson deserialised without throwing.
     */
    public boolean isValid() {
        return riskLevel != null && VALID_RISK_LEVELS.contains(riskLevel.toUpperCase());
    }

    /** Returns true when this advisory applies to the EXIT stage. */
    public boolean isExitStage() {
        return "EXIT".equalsIgnoreCase(stage);
    }
}
