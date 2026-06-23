package com.hotel.blescanner.transport;

import android.util.Log;
import com.hotel.blescanner.BLEScanService;
import com.hotel.blescanner.GatewayServer;
import com.hotel.blescanner.config.TransportConfig;
import com.hotel.blescanner.mode.DeviceMode;
import com.hotel.blescanner.mode.DeviceModeController;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Central controller for Transport mode behaviour.
 *
 * Fixes applied in this revision:
 *   3.1  Biometric freshness checked before triggering prompt — no repeat prompts
 *   3.2  onBiometricSuccess() closes the feedback loop with SUCCESS broadcast
 *   3.3  RSSI threshold guard on barrier proximity (configurable, not hardcoded)
 *   3.4  Explicit transport session lifecycle (start/end) prevents accidental switching
 *   3.5  Advisory stability window — debounce before applying mode switch
 *   3.7  validationReason event broadcasts why validation was triggered
 *   Fix B Structured log tags: [MODE] [VALIDATION] [BIOMETRIC] [SESSION] [ADVISORY]
 *
 * Thread safety:
 *   - applyAdvisory(), onWebSocketDisconnected(), revertToHotel() are synchronized
 *   - All shared state fields are volatile
 *   - biometricCallback is volatile — written on main thread, read on scheduler thread
 */
public class ValidationController {

    // Structured log tags (Fix B)
    private static final String TAG       = "ValidationController";
    private static final String T_MODE    = "[MODE]";
    private static final String T_VAL    = "[VALIDATION]";
    private static final String T_BIO    = "[BIOMETRIC]";
    private static final String T_SES    = "[SESSION]";
    private static final String T_ADV    = "[ADVISORY]";

    private final DeviceModeController    modeController;
    private       RFActivationController  rfActivation;
    private final BLEScanService          bleService;
    private final TransportConfig         config;
    private       GatewayServer           gatewayServer;
    private       BiometricManager        biometricManager;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Fail-safe revert timer (existing Fix 3.7-prev)
    private ScheduledFuture<?> advisoryTimeoutFuture;

    // Fix 3.5: advisory stability debounce — pending advisory waits before being applied
    private ScheduledFuture<?> advisoryStabilityFuture;
    private volatile BackendAdvisory pendingAdvisory = null;

    // Fix 3.4: explicit session state
    private volatile boolean transportSessionActive = false;
    private ScheduledFuture<?> sessionTimeoutFuture;

    // Validation cooldown
    private volatile long lastValidationTriggerMs = 0L;

    // Fix 3.4: pending validation state
    private volatile boolean pendingValidationRequired = false;

    // Fix 3.4: biometric callback
    private volatile BiometricCallback biometricCallback;

    /**
     * Race condition guard for parallel biometric + NFC validation paths.
     *
     * When triggerValidation() fires, both biometric and NFC are started.
     * Whichever completes first calls compareAndSet(false, true) and wins.
     * The second path sees true and exits immediately without broadcasting
     * a duplicate result. Reset to false on every new triggerValidation() call.
     *
     * AtomicBoolean is used (not volatile boolean) because compareAndSet
     * must be atomic — read-check-write must not be interleaved between
     * the biometric callback thread and the NFC main thread.
     */
    private final AtomicBoolean validationConsumed = new AtomicBoolean(false);

    public ValidationController(DeviceModeController   modeController,
                                RFActivationController rfActivation,
                                BLEScanService         bleService,
                                TransportConfig        config) {
        this.modeController = modeController;
        this.rfActivation   = rfActivation;
        this.bleService     = bleService;
        this.config         = config;
    }

    public void setRfActivation(RFActivationController rfActivation)  { this.rfActivation   = rfActivation; }
    public void setGatewayServer(GatewayServer gatewayServer)          { this.gatewayServer   = gatewayServer; }
    public void setBiometricManager(BiometricManager biometricManager) { this.biometricManager = biometricManager; }
    public void setBiometricCallback(BiometricCallback callback)        { this.biometricCallback = callback; }

    // -------------------------------------------------------------------------
    // Advisory handling — Fix 3.2 (atomic) + Fix 3.5 (stability window)
    // -------------------------------------------------------------------------

    /**
     * Receives a validated advisory from GatewayServer.
     *
     * Fix 3.5: advisory is held in a stability window before being applied.
     * If a contradicting advisory arrives within the window, the timer resets —
     * only a stable advisory (no contradiction for ADVISORY_STABILITY_WINDOW_MS)
     * is committed. This prevents mode flipping on unstable WebSocket connections.
     */
    public synchronized void applyAdvisory(BackendAdvisory advisory) {
        Log.d(TAG, T_ADV + " Advisory received: rfDetection=" + advisory.rfDetectionRequired
            + " validation=" + advisory.validationRequired + " risk=" + advisory.riskLevel);

        resetAdvisoryTimeout();   // restart the fail-safe 30s timer on every advisory

        pendingAdvisory = advisory;

        // Cancel any in-flight stability timer and restart it
        if (advisoryStabilityFuture != null && !advisoryStabilityFuture.isDone()) {
            advisoryStabilityFuture.cancel(false);
        }
        advisoryStabilityFuture = scheduler.schedule(
            this::commitAdvisory,
            config.getAdvisoryStabilityWindowMs(),
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Commits the pending advisory after the stability window has passed without contradiction.
     * Atomic: both modeController and rfActivation are updated before applyCurrentMode().
     */
    private synchronized void commitAdvisory() {
        BackendAdvisory advisory = pendingAdvisory;
        if (advisory == null) return;

        Log.d(TAG, T_ADV + " Committing advisory: rfDetection=" + advisory.rfDetectionRequired
            + " validation=" + advisory.validationRequired);

        pendingValidationRequired = advisory.validationRequired;

        if (advisory.rfDetectionRequired) {
            rfActivation.setRfDetectionRequired(true);
            modeController.setMode(DeviceMode.TRANSPORT);
            startTransportSession();
        } else {
            rfActivation.setRfDetectionRequired(false);
            modeController.setMode(DeviceMode.HOTEL);
            endTransportSession();
        }

        bleService.applyCurrentMode();
        Log.d(TAG, T_MODE + " Mode committed: " + modeController.getMode());
    }

    // -------------------------------------------------------------------------
    // Fix 3.4: Transport session lifecycle
    // -------------------------------------------------------------------------

    private void startTransportSession() {
        if (transportSessionActive) return;
        transportSessionActive = true;
        Log.d(TAG, T_SES + " Transport session started");

        // Reset session timeout
        cancelSessionTimeout();
        sessionTimeoutFuture = scheduler.schedule(() -> {
            Log.w(TAG, T_SES + " Session timeout — no barrier detected, ending session");
            endTransportSessionAndRevert();
        }, config.getSessionTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private void endTransportSession() {
        if (!transportSessionActive) return;
        transportSessionActive = false;
        cancelSessionTimeout();
        Log.d(TAG, T_SES + " Transport session ended");
    }

    private synchronized void endTransportSessionAndRevert() {
        endTransportSession();
        revertToHotel();
    }

    public boolean isTransportSessionActive() { return transportSessionActive; }

    // -------------------------------------------------------------------------
    // WebSocket disconnect — immediate revert
    // -------------------------------------------------------------------------

    public synchronized void onWebSocketDisconnected() {
        // Do NOT revert during an active transport session — the web app reconnects
        // every 2s and each cycle would otherwise kill the session prematurely.
        // The session has its own timeout (sessionTimeoutMs) as a safety net.
        if (transportSessionActive) {
            Log.d(TAG, T_ADV + " WebSocket disconnected — transport session active, keeping TRANSPORT mode");
            return;
        }
        Log.w(TAG, T_ADV + " WebSocket disconnected — no active session, reverting to HOTEL");
        cancelAdvisoryTimeout();
        cancelAdvisoryStability();
        revertToHotel();
    }

    // -------------------------------------------------------------------------
    // Barrier proximity — Fix 3.3 (RSSI threshold) + Fix 3.4 (session guard)
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService.scanCallback on every beacon detection.
     *
     * Guards (in order):
     *   1. TRANSPORT mode active
     *   2. Active transport session (Fix 3.4)
     *   3. Cooldown window
     *   4. Beacon is in barrier list (config-driven)
     *   5. RSSI above threshold — close enough to barrier (Fix 3.3)
     */
    public void onBarrierProximity(String beaconName, int rssi) {
        if (!modeController.isTransportMode()) return;

        // Fix 3.4: only process if session is explicitly active
        if (!transportSessionActive) {
            Log.d(TAG, T_VAL + " Barrier detected but no active session — ignoring [" + beaconName + "]");
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastValidationTriggerMs < config.getValidationCooldownMs()) return;

        if (!isNearBarrier(beaconName)) return;

        // Fix 3.3: RSSI threshold — reject far-field detections
        if (rssi < config.getBarrierRssiThreshold()) {
            Log.d(TAG, T_VAL + " Barrier " + beaconName + " detected but RSSI too weak: "
                + rssi + " < " + config.getBarrierRssiThreshold() + " — ignoring");
            return;
        }

        lastValidationTriggerMs = now;

        // Reset session timeout on barrier detection — user is actively using the barrier
        if (sessionTimeoutFuture != null && !sessionTimeoutFuture.isDone()) {
            sessionTimeoutFuture.cancel(false);
            sessionTimeoutFuture = scheduler.schedule(
                this::endTransportSessionAndRevert,
                config.getSessionTimeoutMs(), TimeUnit.MILLISECONDS);
        }

        Log.d(TAG, T_VAL + " Barrier proximity confirmed: " + beaconName + " rssi=" + rssi);
        triggerValidation(beaconName);
    }

    public boolean isNearBarrier(String beaconName) {
        if (beaconName == null) return false;
        return Arrays.asList(config.getBarrierBeacons()).contains(beaconName);
    }

    // -------------------------------------------------------------------------
    // NFC success — parallel path to biometric
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService.nfcTagReadReceiver after a card UID is read.
     *
     * Race guard: if biometric already consumed this validation, this call is a no-op.
     * This is the mirror of onBiometricSuccess() — same guard, different broadcast.
     *
     * @param tagId     uppercase hex UID of the card, e.g. "A3F204BC"
     * @param journeyId journey identifier from the advisory (or beacon name fallback)
     */
    public void onNfcSuccess(String tagId, String journeyId) {
        if (!validationConsumed.compareAndSet(false, true)) {
            Log.d(TAG, T_BIO + " NFC success ignored — biometric already consumed this validation");
            return;
        }
        Log.d(TAG, T_BIO + " NFC validation succeeded: tagId=" + tagId);
        pendingValidationRequired = false;

        GatewayServer gs = gatewayServer;
        if (gs != null) {
            gs.broadcastNfcValidationEvent(journeyId, tagId, "SUCCESS");
        }
        Log.d(TAG, T_VAL + " NFC Validation completed at journey: " + journeyId);
    }

    // -------------------------------------------------------------------------
    // Fix 3.2: Biometric success closes the feedback loop
    // -------------------------------------------------------------------------

    /**
     * Called by MainActivity after successful BiometricPrompt authentication.
     *
     * Fix 3.2: clears pendingValidationRequired, records auth time, broadcasts SUCCESS.
     * Race guard: if NFC already consumed this validation, this call is a no-op.
     */
    public void onBiometricSuccess(String beaconName) {
        if (!validationConsumed.compareAndSet(false, true)) {
            Log.d(TAG, T_BIO + " Biometric success ignored — NFC already consumed this validation");
            return;
        }
        Log.d(TAG, T_BIO + " Biometric authentication succeeded at: " + beaconName);
        pendingValidationRequired = false;

        GatewayServer gs = gatewayServer;
        if (gs != null) {
            gs.broadcastValidationEvent(beaconName, "SUCCESS");
        }
        Log.d(TAG, T_VAL + " Validation completed successfully at barrier: " + beaconName);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void shutdown() {
        cancelAdvisoryTimeout();
        cancelAdvisoryStability();
        cancelSessionTimeout();
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fix 3.1: checks biometric freshness BEFORE triggering prompt.
     * Fix 3.7: broadcasts validationReason event with explainability data.
     * NFC: fires NFC_ENABLE broadcast in parallel with biometric (same journey).
     * Race guard: resets validationConsumed so the first of the two paths wins.
     */
    private void triggerValidation(String beaconName) {
        // Reset race guard — new validation cycle starts here
        validationConsumed.set(false);

        boolean biometricFresh = false;
        String  reason;

        if (biometricManager != null) {
            biometricFresh = biometricManager.isBiometricFresh(config.getBiometricMaxAgeMs());
        }

        // Fix 3.7: broadcast reason event for explainability
        GatewayServer gs = gatewayServer;
        if (gs != null) {
            reason = biometricFresh ? "PROXIMITY_VERIFIED" : "BIOMETRIC_REQUIRED";
            gs.broadcastValidationReasonEvent(beaconName, reason, biometricFresh);
        }

        // Resolve journeyId: prefer advisory.journeyId, fall back to beaconName
        String journeyId = (pendingAdvisory != null && pendingAdvisory.journeyId != null
                            && !pendingAdvisory.journeyId.isEmpty())
            ? pendingAdvisory.journeyId
            : beaconName;

        if (pendingValidationRequired && !biometricFresh) {
            // Fix 3.1: only prompt if biometric is NOT fresh
            Log.d(TAG, T_BIO + " Biometric not fresh — requesting authentication at: " + beaconName);
            BiometricCallback cb = biometricCallback;
            if (cb != null) {
                cb.onBiometricRequired();
            } else {
                Log.d(TAG, T_BIO + " Activity not in foreground — deferring biometric prompt");
            }

            // Fire NFC in parallel — independent of biometric outcome
            // The first of the two paths to complete wins via validationConsumed
            android.content.Intent nfcIntent =
                new android.content.Intent("NFC_ENABLE");
            nfcIntent.putExtra("journeyId", journeyId);
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(bleService)
                .sendBroadcast(nfcIntent);
            Log.d(TAG, T_VAL + " NFC dispatch enabled alongside biometric for journey: " + journeyId);

        } else if (biometricFresh) {
            // Fix 3.1: biometric fresh — skip both prompts, auto-validate
            Log.d(TAG, T_BIO + " Biometric fresh — skipping prompt, auto-validating at: " + beaconName);
            onBiometricSuccess(beaconName);
            return;
        }

        // Broadcast validation REQUIRED event to WebSocket clients
        if (gs != null) {
            gs.broadcastValidationEvent(beaconName, "REQUIRED");
        }
        Log.d(TAG, T_VAL + " Validation triggered: beaconName=" + beaconName
            + " journeyId=" + journeyId + " biometricFresh=" + biometricFresh);
    }

    private void resetAdvisoryTimeout() {
        if (advisoryTimeoutFuture != null && !advisoryTimeoutFuture.isDone()) {
            advisoryTimeoutFuture.cancel(false);
        }
        // Only schedule revert timeout if no active transport session.
        // Once session starts, session timeout (5 min) controls revert — not advisory timeout.
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
        if (advisoryTimeoutFuture != null && !advisoryTimeoutFuture.isDone()) {
            advisoryTimeoutFuture.cancel(false);
        }
    }

    private void cancelAdvisoryStability() {
        if (advisoryStabilityFuture != null && !advisoryStabilityFuture.isDone()) {
            advisoryStabilityFuture.cancel(false);
        }
    }

    private void cancelSessionTimeout() {
        if (sessionTimeoutFuture != null && !sessionTimeoutFuture.isDone()) {
            sessionTimeoutFuture.cancel(false);
        }
    }

    private synchronized void revertToHotel() {
        rfActivation.setRfDetectionRequired(false);
        modeController.setMode(DeviceMode.HOTEL);
        pendingValidationRequired = false;
        bleService.applyCurrentMode();
        Log.d(TAG, T_MODE + " Reverted to HOTEL mode");
    }
}
