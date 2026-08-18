package com.hotel.blescanner.insurance;

import android.util.Log;
import java.util.Map;
import java.util.HashMap;

/**
 * Tracks backend reachability state for INSURANCE mode.
 *
 * Inputs: success/failure signals from InsuranceTelemetryPublisher.
 * Output: health status block consumed by /health endpoint and insuranceStatus events.
 *
 * Does NOT make network calls itself — it only observes publish outcomes.
 * Thread-safe: all fields are volatile.
 */
public class InsuranceBackendMonitor {

    private static final String TAG = "[INS] BackendMonitor";

    public enum ReachabilityState { UNKNOWN, AVAILABLE, DEGRADED, UNREACHABLE }

    private volatile ReachabilityState state             = ReachabilityState.UNKNOWN;
    private volatile long              lastSuccessMs     = 0L;
    private volatile long              lastFailureMs     = 0L;
    private volatile String            lastFailureReason = null;
    private volatile int               consecutiveFails  = 0;

    /** Called by publisher on every successful HTTP 2xx response. */
    public void onPublishSuccess() {
        consecutiveFails  = 0;
        lastSuccessMs     = System.currentTimeMillis();
        lastFailureReason = null;
        ReachabilityState prev = state;
        state = ReachabilityState.AVAILABLE;
        if (prev != ReachabilityState.AVAILABLE) {
            Log.d(TAG, "Backend reachability: " + prev + " → AVAILABLE");
        }
    }

    /**
     * Called by publisher on any failure (timeout, DNS, 5xx, retry exhausted).
     * @param reason short reason string (e.g. "TIMEOUT", "NO_NETWORK", "HTTP_503")
     */
    public void onPublishFailure(String reason) {
        lastFailureMs     = System.currentTimeMillis();
        lastFailureReason = reason;
        consecutiveFails++;
        ReachabilityState prev = state;
        state = (consecutiveFails >= 3) ? ReachabilityState.UNREACHABLE
                                        : ReachabilityState.DEGRADED;
        if (prev != state) {
            Log.w(TAG, "Backend reachability: " + prev + " → " + state
                + " [" + reason + "] consecutiveFails=" + consecutiveFails);
        }
    }

    public ReachabilityState getState() { return state; }

    /** Returns a health block suitable for /health and insuranceStatus. */
    public Map<String, Object> toHealthBlock() {
        Map<String, Object> m = new HashMap<>();
        m.put("backendReachability", state.name());
        m.put("lastSuccessfulPublish",
            lastSuccessMs > 0 ? InsuranceTelemetryEventFactory.toIso8601(lastSuccessMs) : null);
        if (lastFailureReason != null) m.put("lastFailureReason", lastFailureReason);
        return m;
    }

    public void reset() {
        state             = ReachabilityState.UNKNOWN;
        lastSuccessMs     = 0L;
        lastFailureMs     = 0L;
        lastFailureReason = null;
        consecutiveFails  = 0;
    }
}
