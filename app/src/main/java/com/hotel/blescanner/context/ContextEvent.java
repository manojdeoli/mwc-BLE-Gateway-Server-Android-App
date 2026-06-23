package com.hotel.blescanner.context;

/**
 * Represents a single mobility context evaluation result.
 *
 * Serialised by Gson into the WebSocket envelope:
 * {
 *   "eventType": "context",
 *   "data": {
 *     "mode": "CAR",
 *     "confidence": 85,
 *     "signals": {
 *       "bluetoothConnected": true,
 *       "connectedDeviceName": "Car Audio",
 *       "speed": 42.0,
 *       "motion": "VEHICLE",
 *       "bleProximity": false
 *     },
 *     "timestamp": 1712345678910
 *   }
 * }
 *
 * Field names are intentionally lowercase to match the JSON contract.
 */
public class ContextEvent {

    public final String eventType = "context";
    public final Data data;

    public ContextEvent(String mode, int confidence, Signals signals) {
        this.data = new Data(mode, confidence, signals);
    }

    public static class Data {
        public final String  mode;
        public final int     confidence;
        public final Signals signals;
        public final long    timestamp;

        public Data(String mode, int confidence, Signals signals) {
            this.mode       = mode;
            this.confidence = confidence;
            this.signals    = signals;
            this.timestamp  = System.currentTimeMillis();
        }
    }

    public static class Signals {
        public final boolean bluetoothConnected;
        public final String  connectedDeviceName; // null if none
        public final float   speed;               // km/h, meaningful only when speedAvailable=true
        public final boolean speedAvailable;       // false when GPS fix is stale or unavailable
        public final String  motion;              // MotionState name
        public final boolean bleProximity;

        public Signals(boolean bluetoothConnected,
                       String connectedDeviceName,
                       float speed,
                       boolean speedAvailable,
                       String motion,
                       boolean bleProximity) {
            this.bluetoothConnected  = bluetoothConnected;
            this.connectedDeviceName = connectedDeviceName;
            this.speed               = speed;
            this.speedAvailable      = speedAvailable;
            this.motion              = motion;
            this.bleProximity        = bleProximity;
        }
    }
}
