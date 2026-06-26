package com.hotel.blescanner.transport;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a backend advisory message received over WebSocket.
 *
 * Expected JSON format (new advisory contract — riskLevel optional):
 * {
 *   "validationRequired":    true,
 *   "rfDetectionRequired":   true,
 *   "stage":                 "EXIT",
 *   "journeyId":             "journey-20240418-001",
 *   "correlationConfidence": "HIGH",
 *   "simulation": {
 *     "simulateBarrier": true,
 *     "simulateMotion": {
 *       "enabled": true,
 *       "mode": "VEHICLE",
 *       "speedKmph": 50.0
 *     }
 *   }
 * }
 *
 * Key semantics:
 *
 *   stage = "ENTRY" → barrier always clear, no BLE activation, no session.
 *   stage = "EXIT"  → normal transport flow applies.
 *   stage absent    → treated as ENTRY (safe default — always clear).
 *
 *   validationRequired = output of backend correlation.
 *     false → journey clear     → exitSignal CLEAR
 *     true  → journey ambiguous → exitSignal AMBIGUOUS → require scan
 *
 *   rfDetectionRequired → controls whether TRANSPORT mode is activated.
 *     true  → switch to TRANSPORT mode (EXIT stage only)
 *     false → revert to HOTEL mode
 *
 *   simulation (optional — absent in production, present for demo only):
 *     simulateBarrier: true  → triggers barrier proximity (only when validationRequired=true)
 *     simulateMotion         → overrides MotionAnalyzer state (only in TRANSPORT mode)
 *     If absent → normal real-world behaviour, no simulation.
 *
 *   correlationScore (0–100, informational only):
 *     Device logs this but NEVER branches on it.
 *
 *   correlationConfidence ("HIGH" / "MEDIUM" / "LOW", informational only):
 *     Device logs it, never branches on it.
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

    /** Risk level — LOW, MEDIUM, or HIGH. Optional in new advisory contract. */
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
     * Backend correlation ambiguity score (0 = certain, 100 = ambiguous).
     * Informational only — device logs it, never branches on it.
     */
    public int correlationScore;

    /**
     * Backend correlation confidence tier: "HIGH" | "MEDIUM" | "LOW".
     * Informational only — device logs it, never branches on it.
     */
    public String correlationConfidence;

    /**
     * Optional simulation instructions from backend/React.
     * Absent in production — only present for demo scenarios.
     * When present, Android executes the simulation after committing the advisory.
     */
    public SimulationConfig simulation;

    /**
     * Simulation configuration block.
     * Both fields are optional — absent fields are treated as disabled.
     */
    public static class SimulationConfig {
        /**
         * When true, triggers a synthetic barrier proximity event.
         * Only fires when validationRequired=true (review comment 2.1).
         */
        public boolean simulateBarrier;

        /**
         * When present, overrides MotionAnalyzer state.
         * Only applied in TRANSPORT mode (review comment 2.5).
         */
        public MotionSimulation simulateMotion;
    }

    /** Motion simulation parameters. */
    public static class MotionSimulation {
        /** true = apply simulation; false = disable and restore real sensors. */
        public boolean enabled;

        /** Target motion state: "VEHICLE" | "WALKING" | "STATIONARY". Defaults to VEHICLE. */
        public String mode;

        /** Simulated speed in km/h. Used when enabled=true. */
        public float speedKmph;
    }

    /**
     * Accepts both old-style advisories (riskLevel present) and new advisory
     * contract (journeyId or simulation present, riskLevel absent).
     * Rejects garbage JSON that Gson deserialised without throwing.
     */
    public boolean isValid() {
        // Old-style: riskLevel must be a known value
        if (riskLevel != null) return VALID_RISK_LEVELS.contains(riskLevel.toUpperCase());
        // New-style: journeyId or simulation field is sufficient
        return journeyId != null || simulation != null;
    }

    /** Returns true when this advisory applies to the EXIT stage. */
    public boolean isExitStage() {
        return "EXIT".equalsIgnoreCase(stage);
    }
}
