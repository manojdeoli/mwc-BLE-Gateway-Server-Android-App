package com.hotel.blescanner.insurance;

import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Determines local vehicle-association state from BLE beacon evidence.
 *
 * This class is INSURANCE-mode only. It must never be called from HOTEL or
 * TRANSPORT paths.
 *
 * Rules (deterministic, configurable — no ML):
 *   1. Only the configured vehicle beacon ID is accepted.
 *   2. RSSI must meet the configured minimum threshold.
 *   3. Multiple qualifying advertisements within the confirmation window
 *      are required before association is confirmed (hysteresis).
 *   4. A configurable grace period prevents rapid connect/disconnect cycling.
 *   5. Motion state may strengthen contextual evidence but does NOT replace
 *      the configured beacon match.
 *
 * The backend remains responsible for definitive vehicle-association confidence
 * and combining it with other evidence (CAMARA, SIM Swap, etc.).
 *
 * Thread safety: all state fields are volatile; advCount is AtomicInteger.
 * Methods may be called from the BLE scan callback thread.
 */
public class VehicleAssociationController {

    private static final String TAG = "[VEH] VehicleAssociationController";

    private final InsuranceConfig config;

    // -------------------------------------------------------------------------
    // Association state
    // -------------------------------------------------------------------------

    private volatile InsuranceSessionState state                  = InsuranceSessionState.IDLE;
    private volatile String                beaconId               = null;
    private volatile long                  firstSeenAtMs          = 0L;
    private volatile long                  lastSeenAtMs           = 0L;
    private volatile int                   lastRssi               = Integer.MIN_VALUE;
    private volatile long                  windowStartMs          = 0L;
    private final    AtomicInteger         advCount               = new AtomicInteger(0);

    // GAP #5 — richer local evidence for diagnostics (not used for pricing/confidence)
    private volatile long                  associationStartMs     = 0L;
    private volatile int                   confirmationWindowHits = 0;
    private volatile double                rssiSum                = 0.0;
    private volatile int                   rssiSampleCount        = 0;
    /** Current scan profile name — set by InsuranceSessionManager. */
    private volatile String                currentScanProfile     = "IDLE";
    private volatile String                scanTransitionReason   = null;
    private volatile long                  scanProfileStartMs     = 0L;

    // -------------------------------------------------------------------------
    // Snapshot for telemetry
    // -------------------------------------------------------------------------

    public static class AssociationSnapshot {
        public final boolean               beaconDetected;
        public final String                beaconId;
        public final InsuranceSessionState state;
        public final long                  firstSeenAtMs;
        public final long                  lastSeenAtMs;
        public final int                   lastRssi;
        public final String                transitionReason;

        AssociationSnapshot(boolean beaconDetected, String beaconId,
                            InsuranceSessionState state,
                            long firstSeenAtMs, long lastSeenAtMs,
                            int lastRssi, String transitionReason) {
            this.beaconDetected   = beaconDetected;
            this.beaconId         = beaconId;
            this.state            = state;
            this.firstSeenAtMs    = firstSeenAtMs;
            this.lastSeenAtMs     = lastSeenAtMs;
            this.lastRssi         = lastRssi;
            this.transitionReason = transitionReason;
        }
    }

    // -------------------------------------------------------------------------
    // Callback
    // -------------------------------------------------------------------------

    public interface AssociationListener {
        /**
         * Called when the association state changes.
         * Always called on the thread that invoked onBeaconDetected() or onBeaconAbsent().
         */
        void onAssociationStateChanged(InsuranceSessionState newState,
                                       InsuranceSessionState previousState,
                                       AssociationSnapshot snapshot);
    }

    private volatile AssociationListener listener;

    public VehicleAssociationController(InsuranceConfig config) {
        this.config = config;
    }

    public void setListener(AssociationListener listener) {
        this.listener = listener;
    }

    // -------------------------------------------------------------------------
    // BLE input
    // -------------------------------------------------------------------------

    /**
     * Called by InsuranceSessionManager when a BLE advertisement is received.
     *
     * @param rawDeviceName  advertised BLE device name
     * @param rssi           signal strength in dBm
     */
    public void onBeaconDetected(String rawDeviceName, int rssi) {
        String configuredId = config.getRegisteredVehicleBeaconId();
        if (configuredId == null || configuredId.trim().isEmpty()) return;
        if (!configuredId.equals(rawDeviceName)) return;  // not the configured vehicle beacon

        if (rssi < config.getMinRssi()) {
            Log.d(TAG, "Beacon " + rawDeviceName + " RSSI too weak: " + rssi
                + " < " + config.getMinRssi() + " — ignoring");
            return;
        }

        long now = System.currentTimeMillis();
        lastSeenAtMs = now;
        lastRssi     = rssi;
        beaconId     = configuredId;

        InsuranceSessionState current = state;

        switch (current) {
            case IDLE:
            case WAITING_FOR_VEHICLE:
                // Start candidate window
                windowStartMs   = now;
                firstSeenAtMs   = now;
                advCount.set(1);
                rssiSum         = rssi;
                rssiSampleCount = 1;
                transition(InsuranceSessionState.CANDIDATE_VEHICLE_DETECTED,
                    "First qualifying advertisement: rssi=" + rssi);
                break;

            case CANDIDATE_VEHICLE_DETECTED:
                // Check if still within confirmation window
                if ((now - windowStartMs) <= config.getAssocConfirmWindowMs()) {
                    int count = advCount.incrementAndGet();
                    rssiSum += rssi; rssiSampleCount++;
                    Log.d(TAG, "Candidate count=" + count + "/" + config.getMinAdvCount()
                        + " rssi=" + rssi);
                    if (count >= config.getMinAdvCount()) {
                        confirmationWindowHits++;
                        associationStartMs = now;
                        transition(InsuranceSessionState.VEHICLE_ASSOCIATED,
                            "Stable association confirmed: count=" + count
                            + " rssi=" + rssi);
                    }
                } else {
                    // Window expired — restart candidate window
                    windowStartMs = now;
                    advCount.set(1);
                    rssiSum = rssi; rssiSampleCount = 1;
                    Log.d(TAG, "Candidate window expired — restarting");
                }
                break;

            case ASSOCIATION_DEGRADED:
                // Beacon re-detected within grace period
                transition(InsuranceSessionState.VEHICLE_ASSOCIATED,
                    "Association restored after degradation: rssi=" + rssi);
                break;

            case VEHICLE_ASSOCIATED:
                // Heartbeat — but drop to DEGRADED if RSSI has fallen below threshold
                if (rssi < config.getMinRssi()) {
                    Log.d(TAG, "Beacon RSSI degraded below threshold: " + rssi
                        + " < " + config.getMinRssi());
                    transition(InsuranceSessionState.ASSOCIATION_DEGRADED,
                        "RSSI below threshold: " + rssi + " < " + config.getMinRssi());
                } else {
                    Log.d(TAG, "Vehicle beacon heartbeat: rssi=" + rssi);
                }
                break;

            default:
                // VEHICLE_DISCONNECTED / SESSION_ENDED — ignore
                break;
        }
    }

    /**
     * Called by InsuranceSessionManager when the configured beacon has been
     * absent for longer than the grace period.
     *
     * @param absentMs how long the beacon has been absent in milliseconds
     */
    public void onBeaconAbsent(long absentMs) {
        InsuranceSessionState current = state;
        long graceMs = config.getBeaconLossGraceMs();

        switch (current) {
            case VEHICLE_ASSOCIATED:
                if (absentMs < graceMs) {
                    transition(InsuranceSessionState.ASSOCIATION_DEGRADED,
                        "Beacon absent " + absentMs + "ms (within grace " + graceMs + "ms)");
                } else {
                    transition(InsuranceSessionState.VEHICLE_DISCONNECTED,
                        "Beacon absent " + absentMs + "ms — beyond grace period");
                }
                break;

            case ASSOCIATION_DEGRADED:
                if (absentMs >= graceMs) {
                    transition(InsuranceSessionState.VEHICLE_DISCONNECTED,
                        "Beacon absent " + absentMs + "ms — grace period expired");
                }
                break;

            case CANDIDATE_VEHICLE_DETECTED:
                if (absentMs >= graceMs) {
                    transition(InsuranceSessionState.IDLE,
                        "Candidate beacon lost before confirmation");
                }
                break;

            default:
                break;
        }
    }

    // -------------------------------------------------------------------------
    // State access
    // -------------------------------------------------------------------------

    public InsuranceSessionState getState()    { return state; }
    public String                getBeaconId() { return beaconId; }
    public long                  getLastSeenAtMs() { return lastSeenAtMs; }
    public int                   getLastRssi()     { return lastRssi; }
    public boolean               isBeaconDetected() {
        return state == InsuranceSessionState.VEHICLE_ASSOCIATED
            || state == InsuranceSessionState.ASSOCIATION_DEGRADED;
    }

    /** GAP #5 — advertisement count since first detection in current window. */
    public int    getAdvertisementCount()     { return advCount.get(); }
    /** GAP #5 — number of times the confirmation window threshold was met. */
    public int    getConfirmationWindowHits() { return confirmationWindowHits; }
    /** GAP #5 — rolling average RSSI across all qualifying advertisements. */
    public double getAverageRssi()            { return rssiSampleCount > 0 ? rssiSum / rssiSampleCount : 0.0; }
    /** GAP #5 — duration since association was first confirmed, 0 if not associated. */
    public long   getAssociationDurationMs()  {
        return (associationStartMs > 0 && isBeaconDetected())
            ? System.currentTimeMillis() - associationStartMs : 0L;
    }

    // GAP #9 — scan profile observability
    public String getCurrentScanProfile()    { return currentScanProfile; }
    public String getScanTransitionReason()  { return scanTransitionReason; }
    public long   getScanProfileStartMs()    { return scanProfileStartMs; }

    /** Called by InsuranceSessionManager when scan profile changes. */
    public void setScanProfile(String profile, String reason) {
        currentScanProfile   = profile;
        scanTransitionReason = reason;
        scanProfileStartMs   = System.currentTimeMillis();
        Log.d(TAG, "Scan profile: " + profile + " [" + reason + "]");
    }

    public AssociationSnapshot getSnapshot(String reason) {
        return new AssociationSnapshot(
            isBeaconDetected(), beaconId, state,
            firstSeenAtMs, lastSeenAtMs, lastRssi, reason);
    }

    /** Resets all state to IDLE. Called when INSURANCE mode is deactivated. */
    public void reset() {
        state                  = InsuranceSessionState.IDLE;
        beaconId               = null;
        firstSeenAtMs          = 0L;
        lastSeenAtMs           = 0L;
        lastRssi               = Integer.MIN_VALUE;
        windowStartMs          = 0L;
        advCount.set(0);
        associationStartMs     = 0L;
        confirmationWindowHits = 0;
        rssiSum                = 0.0;
        rssiSampleCount        = 0;
        currentScanProfile     = "IDLE";
        scanTransitionReason   = null;
        scanProfileStartMs     = 0L;
        Log.d(TAG, "Reset to IDLE");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void transition(InsuranceSessionState newState, String reason) {
        InsuranceSessionState prev = state;
        if (prev == newState) return;
        state = newState;
        Log.d(TAG, "State: " + prev + " → " + newState + " [" + reason + "]");
        AssociationListener l = listener;
        if (l != null) {
            l.onAssociationStateChanged(newState, prev, getSnapshot(reason));
        }
    }
}
