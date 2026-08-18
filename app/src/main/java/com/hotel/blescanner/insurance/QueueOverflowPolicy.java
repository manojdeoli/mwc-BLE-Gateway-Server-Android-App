package com.hotel.blescanner.insurance;

/**
 * Controls what happens when the pending-event queue reaches capacity.
 *
 * Default: KEEP_LATEST_STATE — preserves the most recent association state event
 * and drops older non-critical events to make room.
 *
 * Applied by InsuranceTelemetryPublisher when the queue is full.
 */
public enum QueueOverflowPolicy {

    /** Drop the oldest queued event to make room for the new one. */
    DROP_OLDEST,

    /** Silently discard the incoming event — queue contents unchanged. */
    DROP_NEWEST,

    /**
     * Drop the oldest non-critical event; if all are critical, drop oldest.
     * Ensures the latest association state change is always preserved.
     */
    KEEP_LATEST_STATE
}
