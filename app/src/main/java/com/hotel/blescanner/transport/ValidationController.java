package com.hotel.blescanner.transport;

import android.content.Intent;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.hotel.blescanner.BLEScanService;
import com.hotel.blescanner.GatewayServer;
import com.hotel.blescanner.config.BeaconConfigManager;
import com.hotel.blescanner.config.TransportConfig;
import com.hotel.blescanner.mode.DeviceMode;
import com.hotel.blescanner.mode.DeviceModeController;
import com.hotel.blescanner.motion.MotionAnalyzer;
import com.hotel.blescanner.motion.MotionState;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Central controller for Transport mode behaviour.
 *
 * Refinements applied in this revision:
 *
 *   Phase 1+4+8 : Biometric removed from barrier flow entirely.
 *                 Barrier uses NFC-only validation (triggerNfcOnly).
 *                 validationConsumed (AtomicBoolean race guard) removed.
 *
 *   Phase 2     : Biometric is passive freshness check only.
 *                 Triggered by NetworkProximityMonitor at station arrival
 *                 (Gap 2.3), NOT at barrier and NOT at advisory commit.
 *                 BiometricManager and BiometricCallback kept — usage only.
 *
 *   Phase 3     : Binary barrier decision in onBarrierProximity():
 *                 validationRequired=false → broadcastBarrierDecision(OPEN)
 *                 validationRequired=true  → broadcastBarrierDecision(CLOSED) + NFC
 *
 *   Phase 5     : ENTRY stage fast-path in commitAdvisory().
 *                 stage=ENTRY (or absent) → pendingValidationRequired=false,
 *                 rfDetectionRequired IGNORED, NO session, NO BLE activation.
 *                 Returns immediately — zero barrier logic at entry.
 *
 *   Gap 2.2     : Renamed barrier event to barrierDecision — clarifies device=signal,
 *                 backend=control authority.
 *
 *   Gap 2.4     : correlationScore from BackendAdvisory is logged for transparency.
 *                 Device never branches on it — all decisions from validationRequired.
 *
 * All existing mechanisms preserved:
 *   - Advisory stability window (Fix 3.5)
 *   - Fail-safe revert to HOTEL (advisory timeout + WebSocket disconnect)
 *   - Session lifecycle + session timeout (Fix 3.4)
 *   - RSSI threshold guard (Fix 3.3)
 *   - Validation cooldown (Fix 3.6)
 *   - NFC success flow unchanged
 *   - All structured log tags [MODE][VALIDATION][BIOMETRIC][SESSION][ADVISORY]
 *
 * Thread safety:
 *   - applyAdvisory(), onWebSocketDisconnected(), revertToHotel() synchronized
 *   - All shared state volatile
 *   - biometricCallback volatile — written on main thread, read on scheduler thread
 */
public class ValidationController {

    private static final String TAG    = "ValidationController";
    private static final String T_MODE = "[MODE]";
    private static final String T_VAL  = "[VALIDATION]";
    private static final String T_BIO  = "[BIOMETRIC]";
    private static final String T_SES  = "[SESSION]";
    private static final String T_ADV  = "[ADVISORY]";

    private final DeviceModeController   modeController;
    private       RFActivationController rfActivation;
    private final BLEScanService         bleService;
    private final TransportConfig        config;
    private       GatewayServer          gatewayServer;
    private       BiometricManager       biometricManager;   // freshness check only
    private volatile BiometricCallback   biometricCallback;  // pre-journey prompt only
    private       BeaconConfigManager    beaconConfigManager; // barrier beacon lookup
    private       MotionAnalyzer          motionAnalyzer;      // simulation target

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Advisory lifecycle
    private ScheduledFuture<?> advisoryTimeoutFuture;
    private ScheduledFuture<?> advisoryStabilityFuture;
    private volatile BackendAdvisory pendingAdvisory = null;

    // Session lifecycle
    private volatile boolean       transportSessionActive   = false;
    private ScheduledFuture<?>     sessionTimeoutFuture;

    // Barrier state
    private volatile long    lastBarrierEvalMs        = 0L;
    private volatile boolean pendingValidationRequired = false;

    /**
     * Idempotency guard for simulation (review comment 2.3).
     * Set to true the first time simulateBarrierProximity() fires in a session.
     * Reset in endTransportSession() and revertToHotel() so the next session
     * can trigger simulation again if the advisory includes it.
     */
    private volatile boolean simulationBarrierTriggered = false;

    /**
     * Pending biometric validation result waiting for a WebSocket client to connect.
     * Set when broadcastBiometricValidationEvent fails due to no connected clients.
     * Cleared once successfully broadcast. Allows immediate re-send on reconnect.
     */
    private volatile String pendingBiometricJourneyId = null;

    /**
     * Gap 2.3: BLE-absent fallback timer.
     * Scheduled when a transport session starts. If no BLE barrier beacon is
     * detected within BLE_ABSENT_FALLBACK_MS (default 30s), the device broadcasts
     * an exitSignal CLEAR using network-only confidence, keeping the system
     * functional when BLE is unavailable.
     * Cancelled and reset on every successful barrier proximity detection.
     */
    private ScheduledFuture<?> bleAbsentFallbackFuture;
    private volatile String    pendingFallbackJourneyId = null;

    public ValidationController(DeviceModeController   modeController,
                                RFActivationController rfActivation,
                                BLEScanService         bleService,
                                TransportConfig        config) {
        this.modeController = modeController;
        this.rfActivation   = rfActivation;
        this.bleService     = bleService;
        this.config         = config;
    }

    public void setRfActivation(RFActivationController rfActivation)  { this.rfActivation     = rfActivation; }
    public void setGatewayServer(GatewayServer gs)                     { this.gatewayServer     = gs; }
    public void setBiometricManager(BiometricManager bm)               { this.biometricManager  = bm; }
    public void setBiometricCallback(BiometricCallback cb)             { this.biometricCallback  = cb; }
    public void setBeaconConfigManager(BeaconConfigManager bcm)        { this.beaconConfigManager = bcm; }
    public void setMotionAnalyzer(MotionAnalyzer ma)                   { this.motionAnalyzer      = ma; }

    // -------------------------------------------------------------------------
    // Advisory handling — stability window (Fix 3.5) + ENTRY fast-path (Phase 5)
    // -------------------------------------------------------------------------

    public synchronized void applyAdvisory(BackendAdvisory advisory) {
        Log.d(TAG, T_ADV + " Advisory received: stage=" + advisory.stage
            + " rfDetection=" + advisory.rfDetectionRequired
            + " validation=" + advisory.validationRequired
            + " risk=" + advisory.riskLevel
            + " correlationScore=" + advisory.correlationScore);

        resetAdvisoryTimeout();
        pendingAdvisory = advisory;

        if (advisoryStabilityFuture != null && !advisoryStabilityFuture.isDone()) {
            advisoryStabilityFuture.cancel(false);
        }
        advisoryStabilityFuture = scheduler.schedule(
            this::commitAdvisory,
            config.getAdvisoryStabilityWindowMs(),
            TimeUnit.MILLISECONDS);
    }

    /**
     * Commits the pending advisory after the stability window.
     *
     * Phase 5 / Gap 2.5 — ENTRY fast-path:
     *   stage=ENTRY (or absent) → pendingValidationRequired=false,
     *   rfDetectionRequired IGNORED, no session, no BLE activation, return.
     *   Barrier is always OPEN at entry with zero device-side logic.
     *
     * EXIT stage:
     *   Normal transport flow — session started, BLE activation eligible.
     *   pendingValidationRequired = advisory.validationRequired (backend correlation result).
     */
    private synchronized void commitAdvisory() {
        BackendAdvisory advisory = pendingAdvisory;
        if (advisory == null) return;

        Log.d(TAG, T_ADV + " Committing: stage=" + advisory.stage
            + " validation=" + advisory.validationRequired
            + " correlationScore=" + advisory.correlationScore);

        // Phase 5 / Gap 2.5: ENTRY or absent stage — minimal logic, barrier always open
        if (!advisory.isExitStage()) {
            pendingValidationRequired = false;
            // Explicitly do NOT set rfDetectionRequired — no BLE at entry
            // Explicitly do NOT start a session
            Log.d(TAG, T_ADV + " ENTRY stage — barrier always open, no BLE, no session");
            return;
        }

        // EXIT stage — normal transport flow
        // Gap 2.4: log correlation confidence + score; device never branches on them
        Log.d(TAG, T_ADV + " EXIT stage — correlationScore=" + advisory.correlationScore
            + " correlationConfidence=" + advisory.correlationConfidence
            + " → validationRequired=" + advisory.validationRequired);

        pendingValidationRequired = advisory.validationRequired;

        if (advisory.rfDetectionRequired) {
            rfActivation.setRfDetectionRequired(true);
            rfActivation.setUserNearStation(true);
            modeController.setMode(DeviceMode.TRANSPORT);
            startTransportSession(resolveJourneyId(advisory.journeyId != null
                ? advisory.journeyId : ""));
            // Trigger biometric validation — in demo this is the identity
            // verification step (Step 4). On success, broadcasts a validation
            // SUCCESS event to React via WebSocket, completing Steps 4 and 5.
            triggerBiometricValidation();
        } else {
            rfActivation.setRfDetectionRequired(false);
            rfActivation.setUserNearStation(false);  // clear station flag when transport deactivates
            modeController.setMode(DeviceMode.HOTEL);
            endTransportSession();
        }

        bleService.applyCurrentMode();
        Log.d(TAG, T_MODE + " Mode committed: " + modeController.getMode());

        // -----------------------------------------------------------------
        // Simulation block — runs AFTER mode and session are committed.
        // (review 2.2: inside commitAdvisory, not applyAdvisory)
        // Only processed when the advisory carries a simulation field.
        // -----------------------------------------------------------------
        if (advisory.simulation != null) {

            // review 2.1: simulateBarrier only when validationRequired=true
            // review 2.3: idempotency — fire only once per transport session
            if (advisory.simulation.simulateBarrier
                    && advisory.validationRequired
                    && !simulationBarrierTriggered
                    && transportSessionActive) {
                simulationBarrierTriggered = true;
                simulateBarrierProximity(advisory);
            }

            // review 2.5: motion simulation only while in TRANSPORT mode
            if (advisory.simulation.simulateMotion != null
                    && modeController.isTransportMode()) {
                applyMotionSimulation(advisory.simulation.simulateMotion);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle (Fix 3.4) — EXIT stage only
    // -------------------------------------------------------------------------

    private void startTransportSession(String journeyId) {
        if (transportSessionActive) return;
        transportSessionActive   = true;
        pendingFallbackJourneyId = journeyId;
        Log.d(TAG, T_SES + " Transport session started");
        cancelSessionTimeout();
        sessionTimeoutFuture = scheduler.schedule(() -> {
            Log.w(TAG, T_SES + " Session timeout — no barrier detected, reverting");
            endTransportSessionAndRevert();
        }, config.getSessionTimeoutMs(), TimeUnit.MILLISECONDS);

        // Gap 2.3: schedule BLE-absent fallback — fires if no beacon detected in time
        scheduleBleAbsentFallback(journeyId);
    }

    private void endTransportSession() {
        if (!transportSessionActive) return;
        transportSessionActive     = false;
        simulationBarrierTriggered = false;  // reset idempotency guard for next session
        cancelSessionTimeout();
        cancelBleAbsentFallback();
        pendingFallbackJourneyId = null;
        // Clear motion simulation so real sensors take over immediately
        if (motionAnalyzer != null) {
            motionAnalyzer.setSimulation(false, MotionState.UNKNOWN, 0f);
            Log.d(TAG, "[SIMULATION] Motion simulation cleared on session end");
        }
        Log.d(TAG, T_SES + " Transport session ended");
        // Notify BLEScanService so any deferred config restart can be applied (refinement 2.5)
        bleService.onSessionEnded();
    }

    private synchronized void endTransportSessionAndRevert() {
        endTransportSession();
        revertToHotel();
    }

    public boolean isTransportSessionActive() { return transportSessionActive; }

    // -------------------------------------------------------------------------
    // WebSocket disconnect — fail-safe revert
    // -------------------------------------------------------------------------

    public synchronized void onWebSocketDisconnected() {
        if (transportSessionActive) {
            // Session has its own timeout — do not kill session on transient WS drop
            Log.d(TAG, T_ADV + " WS disconnected — session active, keeping TRANSPORT");
            return;
        }
        if (pendingAdvisory != null) {
            // Advisory is in the stability window — do not revert yet.
            // The stability window will commit shortly; if it times out, the
            // advisory timeout will revert to HOTEL safely.
            Log.d(TAG, T_ADV + " WS disconnected — advisory pending, deferring revert");
            return;
        }
        Log.w(TAG, T_ADV + " WS disconnected — no session, no pending advisory, reverting to HOTEL");
        cancelAdvisoryTimeout();
        cancelAdvisoryStability();
        revertToHotel();
    }

    // -------------------------------------------------------------------------
    // Barrier proximity — Phase 3: binary OPEN/CLOSED decision
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService.scanCallback on every beacon detection.
     *
     * Guards (in order):
     *   1. TRANSPORT mode — HOTEL calls return immediately (zero HOTEL impact)
     *   2. Active transport session — no accidental validation outside session
     *   3. Cooldown — prevents repeat evaluations within 10s
     *   4. Beacon in barrier list — config-driven, not hardcoded
     *   5. RSSI threshold — rejects far-field detections (Fix 3.3)
     *
     * Binary decision (Phase 3 / Gap 2.2):
     *   validationRequired=false → barrierDecision OPEN  (80–90% of users)
     *   validationRequired=true  → barrierDecision CLOSED + triggerNfcOnly
     *
     * Device sends a SIGNAL (barrierDecision). Backend/infrastructure controls
     * the physical barrier — the device never commands hardware (Gap 2.2).
     */
    public void onBarrierProximity(String beaconName, int rssi) {
        // Guard 1: HOTEL mode — return immediately, zero impact on hotel flows
        if (!modeController.isTransportMode()) return;

        // Guard 2: session must be explicitly active
        if (!transportSessionActive) {
            Log.d(TAG, T_VAL + " Barrier detected — no active session, ignoring [" + beaconName + "]");
            return;
        }

        // Guard 3: cooldown
        long now = System.currentTimeMillis();
        if (now - lastBarrierEvalMs < config.getValidationCooldownMs()) return;

        // Guard 4: beacon in configured barrier list
        if (!isNearBarrier(beaconName)) return;

        // Guard 5: RSSI threshold — close-proximity only
        if (rssi < config.getBarrierRssiThreshold()) {
            Log.d(TAG, T_VAL + " Barrier " + beaconName + " RSSI too weak: "
                + rssi + " < " + config.getBarrierRssiThreshold() + " — ignoring");
            return;
        }

        lastBarrierEvalMs = now;

        // Beacon detected — cancel BLE-absent fallback (gap 2.3: BLE is present)
        cancelBleAbsentFallback();

        // Reset session timeout — user actively at barrier
        resetSessionTimeout();

        String journeyId = resolveJourneyId(beaconName);

        Log.d(TAG, T_VAL + " Barrier confirmed: " + beaconName
            + " rssi=" + rssi + " journeyId=" + journeyId
            + " validationRequired=" + pendingValidationRequired);

        if (!pendingValidationRequired) {
            // Gap 2.1: CLEAR — clear journey, no interaction required (80–90% of users)
            Log.d(TAG, T_VAL + " Exit signal CLEAR — clear journey: " + beaconName);
            GatewayServer gs = gatewayServer;
            if (gs != null) gs.broadcastExitSignal(beaconName, "CLEAR", "HIGH_CONFIDENCE", journeyId);
        } else {
            // Gap 2.1: AMBIGUOUS — journey ambiguous, scan required
            Log.d(TAG, T_VAL + " Exit signal AMBIGUOUS — scan required: " + beaconName);
            GatewayServer gs = gatewayServer;
            if (gs != null) gs.broadcastExitSignal(beaconName, "AMBIGUOUS", "LOW_CONFIDENCE", journeyId);
            triggerScanValidation(beaconName, journeyId);
        }
    }

    public boolean isNearBarrier(String beaconName) {
        if (beaconName == null) return false;
        // BeaconConfigManager is the single source of truth for barrier identity.
        // Falls back to TransportConfig if BeaconConfigManager not yet wired.
        if (beaconConfigManager != null) {
            return beaconConfigManager.isBarrierBeacon(beaconName);
        }
        // Fallback: TransportConfig.getBarrierBeacons() (deprecated, kept for safety)
        return Arrays.asList(config.getBarrierBeacons()).contains(beaconName);
    }

    // -------------------------------------------------------------------------
    // Gap 2.4: configurable scan validation method (NFC / RFID / OTHER)
    // Biometric is NOT part of this path.
    // -------------------------------------------------------------------------

    /**
     * Activates the configured scan validation method (default: NFC foreground dispatch)
     * and broadcasts a validation REQUIRED event to WebSocket clients.
     *
     * Gap 2.4: method label is read from TransportConfig.getValidationMethod() —
     * not hardcoded. Deployers can override to "RFID" or "OTHER" via SharedPrefs
     * without a code rebuild. Current hardware path is NFC via RfidNfcReader.
     */
    private void triggerScanValidation(String beaconName, String journeyId) {
        String method = config.getValidationMethod();

        // Enable NFC/RFID foreground dispatch via LocalBroadcast to MainActivity
        Intent nfcIntent = new Intent("NFC_ENABLE");
        nfcIntent.putExtra("journeyId", journeyId);
        LocalBroadcastManager.getInstance(bleService).sendBroadcast(nfcIntent);

        // Notify WebSocket clients — method label from config
        GatewayServer gs = gatewayServer;
        if (gs != null) gs.broadcastValidationEvent(journeyId, "REQUIRED", method);

        Log.d(TAG, T_VAL + " Scan validation triggered [method=" + method + "]"
            + " journey=" + journeyId + " barrier=" + beaconName);
    }

    // -------------------------------------------------------------------------
    // NFC success — unchanged flow, simplified (no race guard needed)
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService.nfcTagReadReceiver after a card UID is read.
     * NFC is the ONLY validation method — no race guard needed (biometric removed).
     *
     * @param tagId     uppercase hex UID of the card, e.g. "A3F204BC"
     * @param journeyId journey identifier from the advisory
     */
    public void onNfcSuccess(String tagId, String journeyId) {
        Log.d(TAG, T_BIO + " Scan validation succeeded: tagId=" + tagId
            + " journey=" + journeyId + " method=" + config.getValidationMethod());
        pendingValidationRequired = false;
        cancelBleAbsentFallback();

        GatewayServer gs = gatewayServer;
        if (gs != null) gs.broadcastNfcValidationEvent(journeyId, tagId, "SUCCESS");

        Log.d(TAG, T_VAL + " Scan validation completed at journey: " + journeyId);
    }

    // -------------------------------------------------------------------------
    // Biometric — freshness state management only (Phase 2 / Gap 2.3)
    // Triggered by NetworkProximityMonitor at station arrival, not here.
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService.biometricSuccessReceiver after the user completes
     * the optional pre-journey biometric prompt.
     *
     * This records the auth time only.
     * It does NOT broadcast any barrier or validation event.
     * It does NOT affect pendingValidationRequired.
     * Biometric is NOT a barrier step in this design.
     */
    public void onBiometricSuccess() {
        Log.d(TAG, T_BIO + " Pre-journey biometric completed — auth time recorded");
        // Auth time is already recorded by BLEScanService.recordBiometricAuthTime()
        // Nothing else to do here — biometric is not a validation step
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void shutdown() {
        cancelAdvisoryTimeout();
        cancelAdvisoryStability();
        cancelSessionTimeout();
        cancelBleAbsentFallback();
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Simulation helpers
    // -------------------------------------------------------------------------

    /**
     * Triggers biometric validation when advisory commits to TRANSPORT mode.
     *
     * Freshness check first:
     *   If biometric was completed within BIOMETRIC_MAX_AGE_MS (default 30 min),
     *   skip the prompt and auto-complete validation immediately.
     *
     * If not fresh:
     *   If Activity is in foreground → launch prompt via BiometricCallback.
     *   If Activity is in background/lock screen → post a full-screen notification
     *     that brings MainActivity to foreground, which then launches the prompt.
     */
    private void triggerBiometricValidation() {
        // Freshness check — skip prompt if recently authenticated
        if (biometricManager != null
                && biometricManager.isBiometricFresh(config.getBiometricMaxAgeMs())) {
            Log.d(TAG, T_BIO + " Biometric still fresh — auto-completing validation");
            onBiometricValidationSuccess();
            return;
        }

        BiometricCallback cb = biometricCallback;
        if (cb != null) {
            // Activity is in foreground — launch prompt directly
            Log.d(TAG, T_BIO + " Requesting biometric validation (foreground)");
            cb.onBiometricRequired();
        } else {
            // Activity is in background or lock screen — use full-screen notification
            // to bring MainActivity to foreground, which then launches the prompt
            Log.d(TAG, T_BIO + " Activity not in foreground — posting biometric notification");
            bleService.postBiometricNotification(resolveJourneyId("unknown"));
        }
    }

    /**
     * Called by BLEScanService.biometricSuccessReceiver after biometric succeeds,
     * OR called directly from triggerBiometricValidation() when freshness check passes.
     * Broadcasts a validation SUCCESS event over WebSocket so React advances
     * Steps 4 and 5 on the ValidationTimeline.
     * Retries once after 2 seconds if WebSocket is mid-reconnect.
     */
    public void onBiometricValidationSuccess() {
        String journeyId = resolveJourneyId("unknown");
        Log.d(TAG, T_BIO + " Biometric validation succeeded — journey=" + journeyId);
        pendingValidationRequired = false;
        pendingBiometricJourneyId = journeyId;  // store for reconnect re-send
        cancelBleAbsentFallback();
        broadcastBiometricSuccessWithRetry(journeyId, 0);
    }

    /**
     * Called by GatewayServer.ClientConnectedListener when a new WebSocket client connects.
     * Re-sends any pending biometric validation result immediately — handles the case
     * where the biometric completed while all clients were disconnected (1006 cycle).
     */
    public void onClientReconnected() {
        String journeyId = pendingBiometricJourneyId;
        if (journeyId != null) {
            Log.d(TAG, T_BIO + " Client reconnected — re-sending pending biometric result: " + journeyId);
            GatewayServer gs = gatewayServer;
            if (gs != null) {
                gs.broadcastBiometricValidationEvent(journeyId, "SUCCESS");
                pendingBiometricJourneyId = null;
                Log.d(TAG, T_VAL + " Pending biometric result sent on reconnect: " + journeyId);
            }
        }
    }

    private void broadcastBiometricSuccessWithRetry(String journeyId, int attempt) {
        GatewayServer gs = gatewayServer;
        if (gs != null && gs.hasConnectedClients()) {
            gs.broadcastBiometricValidationEvent(journeyId, "SUCCESS");
            pendingBiometricJourneyId = null;  // clear — successfully sent
            Log.d(TAG, T_VAL + " Biometric validation broadcast complete: " + journeyId);
        } else if (attempt < 10) {
            Log.d(TAG, T_VAL + " No WS clients yet — retrying biometric broadcast in 3s (attempt "
                + (attempt + 1) + "/10)");
            scheduler.schedule(
                () -> broadcastBiometricSuccessWithRetry(journeyId, attempt + 1),
                3, TimeUnit.SECONDS);
        } else {
            Log.w(TAG, T_VAL + " Biometric broadcast failed after 10 attempts — no clients connected");
        }
    }

    /**
     * Triggers a synthetic barrier proximity event using the first configured
     * barrier beacon name and a close-range simulated RSSI of -50.
     *
     * Bypasses the real scan callback — calls onBarrierProximity() directly
     * so all existing guards (mode, session, cooldown, RSSI threshold) still apply.
     */
    private void simulateBarrierProximity(BackendAdvisory advisory) {
        String beaconName = config.getBarrierBeacons().length > 0
            ? config.getBarrierBeacons()[0]
            : "HotelGate";
        Log.d(TAG, "[SIMULATION] Barrier triggered via advisory — beacon=" + beaconName
            + " journey=" + advisory.journeyId);
        onBarrierProximity(beaconName, -50);
    }

    /**
     * Applies or disables motion simulation on MotionAnalyzer.
     * Only called when modeController.isTransportMode() is true (review 2.5).
     *
     * @param sim parsed MotionSimulation block from the advisory
     */
    private void applyMotionSimulation(BackendAdvisory.MotionSimulation sim) {
        if (motionAnalyzer == null) return;
        if (!sim.enabled) {
            motionAnalyzer.setSimulation(false, MotionState.UNKNOWN, 0f);
            Log.d(TAG, "[SIMULATION] Motion disabled");
            return;
        }
        MotionState state;
        String modeStr = sim.mode != null ? sim.mode.toUpperCase() : "";
        switch (modeStr) {
            case "WALKING":    state = MotionState.WALKING;  break;
            case "STATIONARY": state = MotionState.STILL;    break;
            default:           state = MotionState.VEHICLE;  break;
        }
        motionAnalyzer.setSimulation(true, state, sim.speedKmph);
        Log.d(TAG, "[SIMULATION] Motion enabled: " + sim.mode + " @ " + sim.speedKmph + " km/h");
    }

    private String resolveJourneyId(String beaconName) {
        return (pendingAdvisory != null
                && pendingAdvisory.journeyId != null
                && !pendingAdvisory.journeyId.isEmpty())
            ? pendingAdvisory.journeyId
            : beaconName;
    }

    // -------------------------------------------------------------------------
    // Gap 2.3: BLE-absent fallback helpers
    // -------------------------------------------------------------------------

    /**
     * Schedules the BLE-absent fallback timer.
     * If no BLE beacon is detected within BLE_ABSENT_FALLBACK_MS, broadcasts
     * exitSignal CLEAR with network-only confidence, so the system works
     * even when BLE beacons are unavailable at a deployment site.
     */
    private void scheduleBleAbsentFallback(String journeyId) {
        cancelBleAbsentFallback();
        bleAbsentFallbackFuture = scheduler.schedule(() -> {
            if (!transportSessionActive) return;
            Log.w(TAG, T_VAL + " BLE absent fallback — no beacon detected in "
                + config.getBleAbsentFallbackMs() + "ms, signalling CLEAR via network");
            GatewayServer gs = gatewayServer;
            if (gs != null) {
                gs.broadcastExitSignal(
                    "NETWORK_ONLY", "CLEAR", "NETWORK_CONFIDENCE",
                    journeyId != null && !journeyId.isEmpty() ? journeyId : "unknown");
            }
        }, config.getBleAbsentFallbackMs(), TimeUnit.MILLISECONDS);
    }

    private void cancelBleAbsentFallback() {
        if (bleAbsentFallbackFuture != null && !bleAbsentFallbackFuture.isDone())
            bleAbsentFallbackFuture.cancel(false);
    }

    private void resetSessionTimeout() {
        if (sessionTimeoutFuture != null && !sessionTimeoutFuture.isDone()) {
            sessionTimeoutFuture.cancel(false);
        }
        sessionTimeoutFuture = scheduler.schedule(
            this::endTransportSessionAndRevert,
            config.getSessionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private void resetAdvisoryTimeout() {
        cancelAdvisoryTimeout();
        if (!transportSessionActive) {
            advisoryTimeoutFuture = scheduler.schedule(() -> {
                Log.w(TAG, T_ADV + " Timeout — no advisory for "
                    + config.getAdvisoryTimeoutMs() + "ms, reverting to HOTEL");
                endTransportSession();
                revertToHotel();
            }, config.getAdvisoryTimeoutMs(), TimeUnit.MILLISECONDS);
        }
    }

    private void cancelAdvisoryTimeout() {
        if (advisoryTimeoutFuture != null && !advisoryTimeoutFuture.isDone())
            advisoryTimeoutFuture.cancel(false);
    }

    private void cancelAdvisoryStability() {
        if (advisoryStabilityFuture != null && !advisoryStabilityFuture.isDone())
            advisoryStabilityFuture.cancel(false);
    }

    private void cancelSessionTimeout() {
        if (sessionTimeoutFuture != null && !sessionTimeoutFuture.isDone())
            sessionTimeoutFuture.cancel(false);
    }

    private synchronized void revertToHotel() {
        rfActivation.setRfDetectionRequired(false);
        rfActivation.setUserNearStation(false);
        modeController.setMode(DeviceMode.HOTEL);
        pendingValidationRequired  = false;
        simulationBarrierTriggered = false;  // reset idempotency guard
        // Clear any active motion simulation when leaving TRANSPORT mode
        if (motionAnalyzer != null) {
            motionAnalyzer.setSimulation(false, MotionState.UNKNOWN, 0f);
        }
        bleService.applyCurrentMode();
        Log.d(TAG, T_MODE + " Reverted to HOTEL mode");
    }
}
