package com.hotel.blescanner.context;

import com.hotel.blescanner.bluetooth.BluetoothConnectionMonitor;
import com.hotel.blescanner.motion.MotionAnalyzer;
import com.hotel.blescanner.motion.MotionState;

/**
 * Fuses signals from BLE proximity, Bluetooth connection state and motion
 * analysis into a single mobility context evaluation.
 *
 * Scoring model:
 *   1. Initialise scores for CAR, TRANSIT, WALKING to 0
 *   2. Apply additive contributions from each signal source
 *   3. Apply penalisation rules
 *   4. Clamp all scores to [0, 100]
 *   5. Select the highest-scoring mode
 *   6. If winning confidence < config.getConfidenceThreshold() → UNCERTAIN
 *
 * All numeric weights and thresholds are read from {@link ContextConfig} which
 * falls back to {@link ScoringConfig} defaults. No inline numeric literals exist
 * in this class.
 *
 * BLE proximity uses a timestamp decay window (feedback A):
 *   - BLEScanService writes lastBleSeenAtMs on every beacon detection
 *   - buildContext() treats proximity as active only if
 *     (now - lastBleSeenAtMs) <= config.getBleProximityWindowMs()
 *   - This self-corrects without any explicit clear() call
 *
 * Speed scoring is gated on speedAvailable (feedback C):
 *   - If MotionAnalyzer reports speed as unavailable, speed scoring is skipped
 *   - This prevents stale GPS fixes from biasing the model toward STILL/WALKING
 *
 * Thread safety:
 *   - lastBleSeenAtMs is volatile — written on scan callback thread, read on scheduler thread
 *   - MotionAnalyzer and BluetoothConnectionMonitor expose volatile fields internally
 */
public class ContextBuilder {

    private final MotionAnalyzer             motionAnalyzer;
    private final BluetoothConnectionMonitor bluetoothMonitor;
    private final ContextConfig              config;

    /**
     * Timestamp of the last beacon detection in epoch ms.
     * 0 means no beacon has been seen since service start.
     * Volatile — written on scan callback thread, read on scheduler thread.
     */
    private volatile long lastBleSeenAtMs = 0L;

    public ContextBuilder(MotionAnalyzer motionAnalyzer,
                          BluetoothConnectionMonitor bluetoothMonitor,
                          ContextConfig config) {
        this.motionAnalyzer   = motionAnalyzer;
        this.bluetoothMonitor = bluetoothMonitor;
        this.config           = config;
    }

    // -------------------------------------------------------------------------
    // Signal input from BLE layer
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService whenever a target beacon is detected.
     * Records the current timestamp — proximity decays automatically after
     * BLE_PROXIMITY_WINDOW_MS without a new call.
     *
     * This is the only coupling point between the existing BLE layer and
     * the new context layer.
     */
    public void notifyBleBeaconSeen() {
        this.lastBleSeenAtMs = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Core evaluation
    // -------------------------------------------------------------------------

    /**
     * Reads all current signal values, runs the scoring model and returns
     * a fully populated {@link ContextEvent}.
     *
     * This method is non-blocking and safe to call from any thread.
     */
    public ContextEvent buildContext() {
        long now = System.currentTimeMillis();

        // --- Read signals ---
        boolean     btConnected     = bluetoothMonitor.isConnected();
        String      btDeviceName    = bluetoothMonitor.getConnectedDeviceName();
        float       speed           = motionAnalyzer.getEstimatedSpeed();
        boolean     speedAvailable  = motionAnalyzer.isSpeedAvailable();
        MotionState motion          = motionAnalyzer.getMotionState();

        // BLE proximity decays automatically — no explicit clear needed
        boolean bleProximity = (lastBleSeenAtMs > 0)
            && (now - lastBleSeenAtMs) <= config.getBleProximityWindowMs();

        // --- Initialise scores ---
        int carScore     = 0;
        int transitScore = 0;
        int walkingScore = 0;

        // --- Bluetooth signal ---
        if (btConnected) {
            carScore += config.getBtConnectedCarScore();
        }

        // --- Speed signal (only when GPS fix is fresh) ---
        if (speedAvailable) {
            if (speed > config.getSpeedCarThreshold()) {
                carScore += config.getSpeedCarScore();
            } else if (speed > config.getSpeedTransitThreshold()) {
                transitScore += config.getSpeedTransitScore();
            } else if (speed > config.getSpeedWalkThreshold()) {
                walkingScore += config.getSpeedWalkScore();
            }
        }

        // --- Motion signal ---
        if (motion == MotionState.VEHICLE) {
            carScore     += config.getMotionVehicleCarScore();
            transitScore += config.getMotionVehicleTransitScore();
        } else if (motion == MotionState.WALKING) {
            walkingScore += config.getMotionWalkingScore();
        }

        // --- BLE proximity signal ---
        if (bleProximity) {
            transitScore += config.getBleProximityTransitScore();
        }

        // --- Penalisation ---
        // Bluetooth connected but moving slowly → likely stationary with headphones
        if (btConnected && speedAvailable && speed < config.getSpeedWalkThreshold()) {
            carScore -= config.getBtSlowSpeedCarPenalty();
        }

        // --- Clamp to [0, 100] ---
        carScore     = clamp(carScore);
        transitScore = clamp(transitScore);
        walkingScore = clamp(walkingScore);

        // --- Select mode ---
        String mode;
        int confidence;

        if (carScore >= transitScore && carScore >= walkingScore) {
            mode       = "CAR";
            confidence = carScore;
        } else if (transitScore >= walkingScore) {
            mode       = "TRANSIT";
            confidence = transitScore;
        } else {
            mode       = "WALKING";
            confidence = walkingScore;
        }

        // --- Confidence threshold ---
        if (confidence < config.getConfidenceThreshold()) {
            mode = "UNCERTAIN";
        }

        ContextEvent.Signals signals = new ContextEvent.Signals(
            btConnected,
            btDeviceName,
            speed,
            speedAvailable,
            motion.name(),
            bleProximity
        );

        return new ContextEvent(mode, confidence, signals);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
