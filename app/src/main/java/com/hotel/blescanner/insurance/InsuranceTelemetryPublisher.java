package com.hotel.blescanner.insurance;

import android.util.Log;
import com.hotel.blescanner.GatewayServer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

public class InsuranceTelemetryPublisher {

    private static final String TAG = "[INS] Publisher";

    public enum PublisherState { IDLE, SENDING, CONNECTED, FAILED, DISABLED }

    private volatile PublisherState publisherState    = PublisherState.IDLE;
    private volatile String         lastPublishStatus = "NONE";
    private volatile long           lastSuccessMs     = 0L;
    private volatile int            pendingCount      = 0;

    private final InsuranceConfig config;
    private final GatewayServer   gatewayServer;
    private final AtomicBoolean   active = new AtomicBoolean(false);

    private final Queue<InsuranceTelemetryEvent> pendingQueue = new LinkedList<>();

    public InsuranceTelemetryPublisher(InsuranceConfig config, GatewayServer gatewayServer) {
        this.config        = config;
        this.gatewayServer = gatewayServer;
    }

    public void activate() {
        active.set(true);
        publisherState = PublisherState.IDLE;
        Log.d(TAG, "Publisher activated");
    }

    public void deactivate() {
        active.set(false);
        publisherState = PublisherState.DISABLED;
        synchronized (pendingQueue) {
            pendingQueue.clear();
            pendingCount = 0;
        }
        Log.d(TAG, "Publisher deactivated — pending queue cleared");
    }

    public void publish(InsuranceTelemetryEvent event) {
        if (!active.get()) {
            Log.d(TAG, "Publisher inactive — event dropped: " + event.eventType);
            return;
        }
        publisherState = PublisherState.SENDING;
        gatewayServer.broadcastInsuranceTelemetry(event);
        publisherState    = PublisherState.CONNECTED;
        lastPublishStatus = "SUCCESS";
        lastSuccessMs     = System.currentTimeMillis();
        Log.d(TAG, "Event broadcast over WS: " + event.eventType);
    }

    public PublisherState getPublisherState()    { return publisherState; }
    public String         getLastPublishStatus() { return lastPublishStatus; }
    public long           getLastSuccessMs()     { return lastSuccessMs; }
    public int            getPendingCount()      { return pendingCount; }
    public InsuranceBackendMonitor getBackendMonitor() { return new InsuranceBackendMonitor(); }
}
