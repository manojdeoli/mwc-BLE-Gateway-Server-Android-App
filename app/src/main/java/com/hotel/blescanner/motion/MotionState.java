package com.hotel.blescanner.motion;

/**
 * Represents the classified motion state of the device,
 * derived from accelerometer variance analysis.
 */
public enum MotionState {
    /** Device is stationary — negligible accelerometer variance. */
    STILL,

    /** Device is moving on foot — moderate, rhythmic accelerometer variance. */
    WALKING,

    /** Device is in a vehicle — low variance with sustained non-zero speed. */
    VEHICLE,

    /** Sensor data is unavailable or insufficient to classify. */
    UNKNOWN
}
