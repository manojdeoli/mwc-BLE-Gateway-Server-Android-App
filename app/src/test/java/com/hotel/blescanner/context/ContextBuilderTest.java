package com.hotel.blescanner.context;

import com.hotel.blescanner.bluetooth.BluetoothConnectionMonitor;
import com.hotel.blescanner.motion.MotionAnalyzer;
import com.hotel.blescanner.motion.MotionState;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ContextBuilder}.
 *
 * Android framework classes are replaced with lightweight stubs so these
 * tests run on the JVM without a device or Robolectric.
 *
 * All expected values are derived from {@link ScoringConfig} constants —
 * no magic numbers appear in assertions.
 *
 * Covers:
 *   - All scoring paths (CAR, TRANSIT, WALKING, UNCERTAIN)
 *   - BLE proximity timestamp decay (feedback A)
 *   - Speed unavailable gating (feedback C)
 *   - Penalisation rules
 *   - Score clamping
 *   - Signals payload correctness
 *   - Confidence threshold boundary
 */
public class ContextBuilderTest {

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    private static class StubMotionAnalyzer extends MotionAnalyzer {
        private MotionState state          = MotionState.UNKNOWN;
        private float       speed          = 0f;
        private boolean     speedAvailable = false;

        StubMotionAnalyzer() { super(null, new ContextConfig(null) {
            // Override all getters to return ScoringConfig defaults without SharedPreferences
            @Override public float getMotionVarianceStill()   { return ScoringConfig.MOTION_VARIANCE_STILL_THRESHOLD; }
            @Override public float getMotionVarianceVehicle() { return ScoringConfig.MOTION_VARIANCE_VEHICLE_THRESHOLD; }
            @Override public int   getMotionWindowSize()      { return ScoringConfig.MOTION_WINDOW_SIZE; }
            @Override public long  getGpsMaxAgeMs()           { return ScoringConfig.GPS_MAX_AGE_MS; }
        }); }

        void set(MotionState state, float speed, boolean speedAvailable) {
            this.state          = state;
            this.speed          = speed;
            this.speedAvailable = speedAvailable;
        }

        @Override public MotionState getMotionState()    { return state; }
        @Override public float       getEstimatedSpeed() { return speed; }
        @Override public boolean     isSpeedAvailable()  { return speedAvailable; }
        @Override public void        start()             {}
        @Override public void        stop()              {}
    }

    private static class StubBluetoothMonitor extends BluetoothConnectionMonitor {
        private boolean connected  = false;
        private String  deviceName = null;

        StubBluetoothMonitor() { super(null); }

        void set(boolean connected, String deviceName) {
            this.connected  = connected;
            this.deviceName = deviceName;
        }

        @Override public boolean isConnected()            { return connected; }
        @Override public String  getConnectedDeviceName() { return deviceName; }
    }

    /** ContextConfig stub that returns ScoringConfig defaults without SharedPreferences. */
    private static class DefaultContextConfig extends ContextConfig {
        DefaultContextConfig() { super(null); }

        @Override public int   getBtConnectedCarScore()       { return ScoringConfig.BT_CONNECTED_CAR_SCORE; }
        @Override public int   getBtSlowSpeedCarPenalty()     { return ScoringConfig.BT_SLOW_SPEED_CAR_PENALTY; }
        @Override public float getSpeedCarThreshold()         { return ScoringConfig.SPEED_CAR_THRESHOLD; }
        @Override public float getSpeedTransitThreshold()     { return ScoringConfig.SPEED_TRANSIT_THRESHOLD; }
        @Override public float getSpeedWalkThreshold()        { return ScoringConfig.SPEED_WALK_THRESHOLD; }
        @Override public int   getSpeedCarScore()             { return ScoringConfig.SPEED_CAR_SCORE; }
        @Override public int   getSpeedTransitScore()         { return ScoringConfig.SPEED_TRANSIT_SCORE; }
        @Override public int   getSpeedWalkScore()            { return ScoringConfig.SPEED_WALK_SCORE; }
        @Override public int   getMotionVehicleCarScore()     { return ScoringConfig.MOTION_VEHICLE_CAR_SCORE; }
        @Override public int   getMotionVehicleTransitScore() { return ScoringConfig.MOTION_VEHICLE_TRANSIT_SCORE; }
        @Override public int   getMotionWalkingScore()        { return ScoringConfig.MOTION_WALKING_SCORE; }
        @Override public int   getBleProximityTransitScore()  { return ScoringConfig.BLE_PROXIMITY_TRANSIT_SCORE; }
        @Override public int   getConfidenceThreshold()       { return ScoringConfig.CONFIDENCE_THRESHOLD; }
        @Override public long  getBleProximityWindowMs()      { return ScoringConfig.BLE_PROXIMITY_WINDOW_MS; }
        @Override public long  getGpsMaxAgeMs()               { return ScoringConfig.GPS_MAX_AGE_MS; }
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private StubMotionAnalyzer   motion;
    private StubBluetoothMonitor bluetooth;
    private ContextBuilder       builder;

    @Before
    public void setUp() {
        motion    = new StubMotionAnalyzer();
        bluetooth = new StubBluetoothMonitor();
        builder   = new ContextBuilder(motion, bluetooth, new DefaultContextConfig());
    }

    // -------------------------------------------------------------------------
    // UNCERTAIN — no signals
    // -------------------------------------------------------------------------

    @Test
    public void noSignals_returnsUncertain() {
        motion.set(MotionState.UNKNOWN, 0f, false);
        bluetooth.set(false, null);
        // no notifyBleBeaconSeen call

        ContextEvent event = builder.buildContext();

        assertEquals("UNCERTAIN", event.data.mode);
        assertTrue(event.data.confidence < ScoringConfig.CONFIDENCE_THRESHOLD);
    }

    // -------------------------------------------------------------------------
    // BLE proximity decay (feedback A)
    // -------------------------------------------------------------------------

    @Test
    public void bleProximity_activeImmediatelyAfterNotify() {
        motion.set(MotionState.UNKNOWN, 0f, false);
        bluetooth.set(false, null);
        builder.notifyBleBeaconSeen();

        ContextEvent event = builder.buildContext();

        assertTrue("BLE proximity should be active immediately after notify",
            event.data.signals.bleProximity);
    }

    @Test
    public void bleProximity_inactiveWhenNeverNotified() {
        motion.set(MotionState.UNKNOWN, 0f, false);
        bluetooth.set(false, null);
        // deliberately no notifyBleBeaconSeen()

        ContextEvent event = builder.buildContext();

        assertFalse("BLE proximity should be false when no beacon seen",
            event.data.signals.bleProximity);
    }

    @Test
    public void bleProximity_decaysAfterWindowExpires() throws InterruptedException {
        // Use a ContextConfig with a very short window to test decay without long waits
        ContextConfig shortWindowConfig = new DefaultContextConfig() {
            @Override public long getBleProximityWindowMs() { return 50L; } // 50ms window
        };
        ContextBuilder shortBuilder = new ContextBuilder(motion, bluetooth, shortWindowConfig);

        motion.set(MotionState.UNKNOWN, 0f, false);
        bluetooth.set(false, null);
        shortBuilder.notifyBleBeaconSeen();

        // Immediately — should be active
        assertTrue(shortBuilder.buildContext().data.signals.bleProximity);

        // Wait for window to expire
        Thread.sleep(100);

        // After expiry — should be inactive (self-correcting, no explicit clear needed)
        assertFalse("BLE proximity should decay after window expires",
            shortBuilder.buildContext().data.signals.bleProximity);
    }

    // -------------------------------------------------------------------------
    // Speed unavailable gating (feedback C)
    // -------------------------------------------------------------------------

    @Test
    public void speedUnavailable_speedScoringSkipped() {
        // VEHICLE motion only — no speed contribution because speedAvailable=false
        motion.set(MotionState.VEHICLE, 60f, false); // speed=60 but unavailable
        bluetooth.set(false, null);

        ContextEvent event = builder.buildContext();

        // Only motion scores apply: carScore = MOTION_VEHICLE_CAR_SCORE(20)
        // transitScore = MOTION_VEHICLE_TRANSIT_SCORE(15)
        // Both < CONFIDENCE_THRESHOLD(50) → UNCERTAIN
        assertEquals("UNCERTAIN", event.data.mode);
        assertFalse("speedAvailable should be false in signals", event.data.signals.speedAvailable);
    }

    @Test
    public void speedAvailable_speedScoringApplied() {
        motion.set(MotionState.VEHICLE, ScoringConfig.SPEED_CAR_THRESHOLD + 10f, true);
        bluetooth.set(false, null);

        ContextEvent event = builder.buildContext();

        assertTrue("speedAvailable should be true in signals", event.data.signals.speedAvailable);
        assertEquals("CAR", event.data.mode);
        int expectedCar = ScoringConfig.SPEED_CAR_SCORE + ScoringConfig.MOTION_VEHICLE_CAR_SCORE;
        assertEquals(expectedCar, event.data.confidence);
    }

    // -------------------------------------------------------------------------
    // CAR scoring paths
    // -------------------------------------------------------------------------

    @Test
    public void bluetoothConnectedHighSpeed_returnsCar() {
        motion.set(MotionState.VEHICLE, ScoringConfig.SPEED_CAR_THRESHOLD + 5f, true);
        bluetooth.set(true, "Car Audio");

        ContextEvent event = builder.buildContext();

        assertEquals("CAR", event.data.mode);
        int expectedCar = ScoringConfig.BT_CONNECTED_CAR_SCORE
                        + ScoringConfig.SPEED_CAR_SCORE
                        + ScoringConfig.MOTION_VEHICLE_CAR_SCORE;
        assertEquals(expectedCar, event.data.confidence);
    }

    @Test
    public void bluetoothConnectedSlowSpeed_penaltyApplied() {
        float slowSpeed = ScoringConfig.SPEED_WALK_THRESHOLD - 0.5f;
        motion.set(MotionState.STILL, slowSpeed, true);
        bluetooth.set(true, "Headphones");

        ContextEvent event = builder.buildContext();

        int expectedCar = ScoringConfig.BT_CONNECTED_CAR_SCORE
                        - ScoringConfig.BT_SLOW_SPEED_CAR_PENALTY; // 40-15=25 < 50
        assertEquals("UNCERTAIN", event.data.mode);
        assertEquals(expectedCar, event.data.confidence);
    }

    @Test
    public void bluetoothConnectedSpeedUnavailable_penaltyNotApplied() {
        // Penalty requires speedAvailable=true AND speed < threshold
        // When speed is unavailable, penalty should NOT be applied
        motion.set(MotionState.STILL, 0f, false);
        bluetooth.set(true, "Headphones");

        ContextEvent event = builder.buildContext();

        // No penalty: carScore = BT_CONNECTED_CAR_SCORE = 40 < 50 → UNCERTAIN
        // But confidence should be 40, not 25 (which would be with penalty)
        assertEquals("UNCERTAIN", event.data.mode);
        assertEquals(ScoringConfig.BT_CONNECTED_CAR_SCORE, event.data.confidence);
    }

    // -------------------------------------------------------------------------
    // TRANSIT scoring paths
    // -------------------------------------------------------------------------

    @Test
    public void transitSpeedAndBleProximity_returnsTransit() {
        float transitSpeed = ScoringConfig.SPEED_TRANSIT_THRESHOLD + 1f;
        motion.set(MotionState.VEHICLE, transitSpeed, true);
        bluetooth.set(false, null);
        builder.notifyBleBeaconSeen();

        ContextEvent event = builder.buildContext();

        int expectedTransit = ScoringConfig.SPEED_TRANSIT_SCORE
                            + ScoringConfig.BLE_PROXIMITY_TRANSIT_SCORE
                            + ScoringConfig.MOTION_VEHICLE_TRANSIT_SCORE;
        int expectedCar     = ScoringConfig.MOTION_VEHICLE_CAR_SCORE;

        if (expectedTransit >= expectedCar) {
            assertEquals("TRANSIT", event.data.mode);
            assertEquals(expectedTransit, event.data.confidence);
        } else {
            assertEquals("CAR", event.data.mode);
        }
    }

    // -------------------------------------------------------------------------
    // WALKING scoring paths
    // -------------------------------------------------------------------------

    @Test
    public void walkingMotionAndWalkSpeed_returnsWalking() {
        float walkSpeed = ScoringConfig.SPEED_WALK_THRESHOLD + 0.5f;
        motion.set(MotionState.WALKING, walkSpeed, true);
        bluetooth.set(false, null);

        ContextEvent event = builder.buildContext();

        int expectedWalking = ScoringConfig.MOTION_WALKING_SCORE + ScoringConfig.SPEED_WALK_SCORE;
        assertEquals("WALKING", event.data.mode);
        assertEquals(expectedWalking, event.data.confidence);
    }

    // -------------------------------------------------------------------------
    // Score clamping
    // -------------------------------------------------------------------------

    @Test
    public void allSignalsMaxed_scoresClampedTo100() {
        motion.set(MotionState.VEHICLE, ScoringConfig.SPEED_CAR_THRESHOLD + 50f, true);
        bluetooth.set(true, "Car Audio");
        builder.notifyBleBeaconSeen();

        ContextEvent event = builder.buildContext();

        assertTrue("Confidence must be <= 100", event.data.confidence <= 100);
        assertTrue("Confidence must be >= 0",   event.data.confidence >= 0);
    }

    // -------------------------------------------------------------------------
    // Signals payload correctness
    // -------------------------------------------------------------------------

    @Test
    public void signalsPayload_reflectsInputValues() {
        float speed = 35f;
        motion.set(MotionState.VEHICLE, speed, true);
        bluetooth.set(true, "My Car");
        builder.notifyBleBeaconSeen();

        ContextEvent event = builder.buildContext();

        assertTrue(event.data.signals.bluetoothConnected);
        assertEquals("My Car", event.data.signals.connectedDeviceName);
        assertEquals(speed, event.data.signals.speed, 0.01f);
        assertTrue(event.data.signals.speedAvailable);
        assertEquals(MotionState.VEHICLE.name(), event.data.signals.motion);
        assertTrue(event.data.signals.bleProximity);
    }

    @Test
    public void eventType_isAlwaysContext() {
        assertEquals("context", builder.buildContext().eventType);
    }

    @Test
    public void timestamp_isRecentEpochMs() {
        long before = System.currentTimeMillis();
        ContextEvent event = builder.buildContext();
        long after  = System.currentTimeMillis();

        assertTrue(event.data.timestamp >= before);
        assertTrue(event.data.timestamp <= after);
    }

    // -------------------------------------------------------------------------
    // Confidence threshold boundary
    // -------------------------------------------------------------------------

    @Test
    public void confidenceAboveThreshold_isNotUncertain() {
        // WALKING(30) + WALK_SPEED(25) = 55 >= 50
        float walkSpeed = ScoringConfig.SPEED_WALK_THRESHOLD + 0.5f;
        motion.set(MotionState.WALKING, walkSpeed, true);
        bluetooth.set(false, null);

        assertNotEquals("UNCERTAIN", builder.buildContext().data.mode);
    }

    @Test
    public void confidenceBelowThreshold_isUncertain() {
        // BLE proximity alone → transitScore = 20 < 50
        motion.set(MotionState.UNKNOWN, 0f, false);
        bluetooth.set(false, null);
        builder.notifyBleBeaconSeen();

        assertEquals("UNCERTAIN", builder.buildContext().data.mode);
    }
}
