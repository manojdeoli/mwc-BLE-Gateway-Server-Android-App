package com.hotel.blescanner.motion;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;
import com.hotel.blescanner.context.ContextConfig;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Analyses device motion using the accelerometer and optionally
 * the last known GPS speed from LocationManager.
 *
 * Classification is based on a rolling variance window of accelerometer
 * magnitudes. All thresholds are read from {@link ContextConfig} which
 * falls back to ScoringConfig defaults — no inline numeric literals exist.
 *
 * GPS staleness (feedback C):
 *   GPS fixes older than config.getGpsMaxAgeMs() are treated as unavailable.
 *   {@link #isSpeedAvailable()} returns false in that case, and
 *   {@link #getEstimatedSpeed()} returns 0. ContextBuilder gates speed
 *   scoring on isSpeedAvailable() to avoid stale-fix bias.
 *
 * Simulation mode:
 *   When enabled via {@link #setSimulation}, real sensor readings are bypassed.
 *   isSpeedAvailable() returns true in simulation so speed scoring is active.
 */
public class MotionAnalyzer implements SensorEventListener {

    private static final String TAG = "MotionAnalyzer";

    private final SensorManager   sensorManager;
    private final LocationManager locationManager;
    private final ContextConfig   config;

    // Rolling window of accelerometer magnitudes
    private final Deque<Float> magnitudeWindow = new ArrayDeque<>();

    // Latest classified state — updated on sensor thread, read on scheduler thread
    private volatile MotionState currentMotionState  = MotionState.UNKNOWN;
    private volatile float       currentSpeed        = 0f;
    private volatile boolean     currentSpeedAvail   = false;

    // Simulation state
    private volatile boolean     simulationEnabled   = false;
    private volatile MotionState simulatedMotion     = MotionState.UNKNOWN;
    private volatile float       simulatedSpeed      = 0f;

    public MotionAnalyzer(Context context, ContextConfig config) {
        this.config          = config;
        this.sensorManager   = (context != null)
            ? (SensorManager)   context.getSystemService(Context.SENSOR_SERVICE)   : null;
        this.locationManager = (context != null)
            ? (LocationManager) context.getSystemService(Context.LOCATION_SERVICE) : null;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        if (sensorManager == null) return;
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Accelerometer registered");
        } else {
            Log.w(TAG, "Accelerometer not available on this device");
        }
    }

    public void stop() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        magnitudeWindow.clear();
        Log.d(TAG, "MotionAnalyzer stopped");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the current motion classification. */
    public MotionState getMotionState() {
        if (simulationEnabled) return simulatedMotion;
        return currentMotionState;
    }

    /**
     * Returns the estimated speed in km/h.
     * Only meaningful when {@link #isSpeedAvailable()} returns true.
     * Returns 0 when GPS is unavailable or the last fix is stale.
     */
    public float getEstimatedSpeed() {
        if (simulationEnabled) return simulatedSpeed;
        return currentSpeed;
    }

    /**
     * Returns true if the current speed value is based on a fresh GPS fix.
     * ContextBuilder gates speed scoring on this flag to avoid stale-fix bias.
     * Always returns true in simulation mode.
     */
    public boolean isSpeedAvailable() {
        if (simulationEnabled) return true;
        return currentSpeedAvail;
    }

    /**
     * Enables or disables simulation mode.
     *
     * @param enabled        true to use simulated values
     * @param motionOverride the motion state to return when simulating
     * @param speedOverride  the speed (km/h) to return when simulating
     */
    public void setSimulation(boolean enabled, MotionState motionOverride, float speedOverride) {
        this.simulationEnabled = enabled;
        this.simulatedMotion   = motionOverride;
        this.simulatedSpeed    = speedOverride;
    }

    // -------------------------------------------------------------------------
    // SensorEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // Net magnitude after subtracting gravity (approximate)
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;

        // Maintain rolling window
        magnitudeWindow.addLast(magnitude);
        if (magnitudeWindow.size() > config.getMotionWindowSize()) {
            magnitudeWindow.pollFirst();
        }

        if (magnitudeWindow.size() < config.getMotionWindowSize()) return;

        float variance = computeVariance(magnitudeWindow);
        currentMotionState = classifyFromVariance(variance);

        // Update speed from GPS on every sensor tick (cheap — reads cached value)
        updateGpsSpeed();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private float computeVariance(Deque<Float> window) {
        float sum = 0f;
        for (float v : window) sum += v;
        float mean = sum / window.size();

        float varianceSum = 0f;
        for (float v : window) varianceSum += (v - mean) * (v - mean);
        return varianceSum / window.size();
    }

    private MotionState classifyFromVariance(float variance) {
        if (variance < config.getMotionVarianceStill()) {
            return MotionState.STILL;
        } else if (variance > config.getMotionVarianceVehicle()) {
            return MotionState.VEHICLE;
        } else {
            return MotionState.WALKING;
        }
    }

    /**
     * Reads the last known GPS speed and validates its freshness.
     * Updates currentSpeed and currentSpeedAvail atomically.
     *
     * A fix older than config.getGpsMaxAgeMs() is treated as stale —
     * currentSpeedAvail is set to false and currentSpeed to 0 to prevent
     * stale values from biasing context decisions toward STILL/WALKING.
     */
    private void updateGpsSpeed() {
        if (locationManager == null) {
            currentSpeed      = 0f;
            currentSpeedAvail = false;
            return;
        }
        try {
            Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }

            if (location != null && location.hasSpeed()) {
                long ageMs = System.currentTimeMillis() - location.getTime();
                if (ageMs <= config.getGpsMaxAgeMs()) {
                    currentSpeed      = location.getSpeed() * 3.6f; // m/s → km/h
                    currentSpeedAvail = true;
                    return;
                }
                // Fix is stale — do not use it
            }
        } catch (SecurityException e) {
            // Location permission not granted
        } catch (Exception e) {
            Log.w(TAG, "Could not read GPS speed", e);
        }
        currentSpeed      = 0f;
        currentSpeedAvail = false;
    }
}
