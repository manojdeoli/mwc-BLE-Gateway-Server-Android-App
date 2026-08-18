package com.hotel.blescanner.insurance;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.hotel.blescanner.transport.BiometricManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Central orchestrator for the INSURANCE mode session lifecycle.
 *
 * GAP #1 — MODE ACTIVATION vs SESSION CREATION are now separate:
 *
 *   start() → WAITING_FOR_VEHICLE (no sessionId, no initial event, no timers)
 *       ↓
 *   VehicleAssociationController fires VEHICLE_ASSOCIATED
 *       ↓
 *   startNewSession() → sessionId generated, initial event published
 *
 * This prevents empty sessions when the mode is activated but no vehicle is present.
 *
 * Does NOT own:
 *   - BLE scan start/stop (RFActivationController owns that)
 *   - HTTP publishing (InsuranceTelemetryPublisher owns that)
 *   - Vehicle association rules (VehicleAssociationController owns that)
 *   - GPS location (InsuranceLocationProvider owns that)
 *
 * INSURANCE mode isolation:
 *   - NFC, barrier, station-WiFi, and transport-validation logic NEVER run here.
 *   - This class must never be instantiated or called in HOTEL or TRANSPORT mode.
 */
public class InsuranceSessionManager {

    private static final String TAG = "[INS] SessionManager";

    private final InsuranceConfig                config;
    private final BiometricManager               biometricManager;
    private final VehicleAssociationController   vehicleController;
    private final InsuranceTelemetryEventFactory eventFactory;
    private final InsuranceTelemetryPublisher    publisher;
    private final InsuranceLocationProvider      locationProvider;
    private final Handler                        mainHandler;
    private final Context                        context;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // GAP #11 — bounded event timeline
    private final InsuranceEventHistory eventHistory = new InsuranceEventHistory();

    // GAP #13 — restart recovery
    private final InsuranceSessionRecovery sessionRecovery;

    // -------------------------------------------------------------------------
    // Session state
    // -------------------------------------------------------------------------

    private volatile String                sessionId            = null;
    private volatile boolean               isInitialEventSent   = false;
    private volatile long                  sessionCreationMs    = 0L;
    private volatile InsuranceSessionState currentSessionState  = InsuranceSessionState.IDLE;
    private volatile BiometricFreshnessState lastFreshnessState = BiometricFreshnessState.UNKNOWN;
    private volatile long                  lastPublishMs        = 0L;
    private volatile long                  lastBeaconSeenMs     = 0L;

    // Scheduled tasks
    private ScheduledFuture<?> periodicVerifyFuture;
    private ScheduledFuture<?> beaconAbsenceCheckFuture;

    // -------------------------------------------------------------------------
    // Status listener
    // -------------------------------------------------------------------------

    public interface StatusListener {
        void onInsuranceStatusChanged(InsuranceSessionState state, String sessionId,
                                      boolean beaconDetected, BiometricFreshnessState freshness,
                                      InsuranceTelemetryPublisher.PublisherState publisherState);
    }

    private volatile StatusListener statusListener;

    public InsuranceSessionManager(InsuranceConfig config,
                                   BiometricManager biometricManager,
                                   VehicleAssociationController vehicleController,
                                   InsuranceTelemetryEventFactory eventFactory,
                                   InsuranceTelemetryPublisher publisher,
                                   InsuranceLocationProvider locationProvider,
                                   Context context) {
        this.config            = config;
        this.biometricManager  = biometricManager;
        this.vehicleController = vehicleController;
        this.eventFactory      = eventFactory;
        this.publisher         = publisher;
        this.locationProvider  = locationProvider;
        this.mainHandler       = new Handler(Looper.getMainLooper());
        this.context           = context;
        this.sessionRecovery   = new InsuranceSessionRecovery(context);

        vehicleController.setListener(this::onAssociationStateChanged);
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    // -------------------------------------------------------------------------
    // Lifecycle — GAP #1: start() enters WAITING_FOR_VEHICLE, NOT a session
    // -------------------------------------------------------------------------

    /**
     * Called when INSURANCE mode is activated.
     *
     * GAP #1: Does NOT create a session. Does NOT generate a sessionId.
     * Does NOT publish an initial event. Enters WAITING_FOR_VEHICLE.
     *
     * Only lightweight monitoring starts here:
     *   - publisher activated (ready to send when session exists)
     *   - GPS started (so first fix is available when session begins)
     *   - biometric freshness check scheduled
     *   - restart recovery attempted
     */
    public void start() {
        Log.d(TAG, "Starting — policy=" + InsuranceTelemetryEventFactory.maskId(config.getPolicyId())
            + " phone=" + InsuranceTelemetryEventFactory.maskPhone(config.getPhoneNumber())
            + " beacon=" + config.getRegisteredVehicleBeaconId());

        publisher.activate();
        locationProvider.start();
        scheduleBiometricFreshnessCheck();

        // GAP #13 — attempt restart recovery before entering WAITING_FOR_VEHICLE
        InsuranceSessionRecovery.RecoveryData recovery = sessionRecovery.tryRecover();
        if (recovery != null) {
            sessionId          = recovery.sessionId;
            isInitialEventSent = recovery.initialEventSent;
            sessionCreationMs  = recovery.sessionCreationTimeMs;
            currentSessionState = InsuranceSessionState.WAITING_FOR_VEHICLE;
            Log.d(TAG, "Recovered session: " + InsuranceTelemetryEventFactory.maskId(sessionId)
                + " initialSent=" + isInitialEventSent);
        } else {
            // GAP #1 — no session yet, just waiting
            currentSessionState = InsuranceSessionState.WAITING_FOR_VEHICLE;
        }

        eventHistory.record(InsuranceEventHistory.HistoryEventType.MODE_ACTIVATED,
            "policy=" + InsuranceTelemetryEventFactory.maskId(config.getPolicyId()));
        notifyStatusChanged();
        Log.d(TAG, "Insurance mode active — state=WAITING_FOR_VEHICLE (no session yet)");
    }

    /**
     * Stops the session manager. Publishes SESSION_ENDED if a session is active.
     */
    public void stop() {
        Log.d(TAG, "Stopping insurance session manager");
        if (currentSessionState == InsuranceSessionState.VEHICLE_ASSOCIATED
                || currentSessionState == InsuranceSessionState.ASSOCIATION_DEGRADED) {
            publishEvent(InsuranceEventType.SESSION_ENDED, false);
        }
        cancelPeriodicVerification();
        cancelBeaconAbsenceCheck();
        locationProvider.stop();
        publisher.deactivate();
        vehicleController.reset();
        sessionRecovery.clear();
        eventHistory.record(InsuranceEventHistory.HistoryEventType.SESSION_ENDED, "mode stopped");
        sessionId           = null;
        isInitialEventSent  = false;
        sessionCreationMs   = 0L;
        currentSessionState = InsuranceSessionState.IDLE;
        scheduler.shutdownNow();
        Log.d(TAG, "Insurance session manager stopped");
    }

    // -------------------------------------------------------------------------
    // BLE input
    // -------------------------------------------------------------------------

    /**
     * Called by BLEScanService when a BLE advertisement is received.
     * Only processes the configured vehicle beacon.
     * INSURANCE mode isolation: only called when DeviceMode == INSURANCE.
     */
    public void onBeaconDetected(String rawDeviceName, int rssi) {
        String configuredId = config.getRegisteredVehicleBeaconId();
        String physicalId   = config.getPhysicalBeaconId();
        boolean matches = rawDeviceName != null
            && (rawDeviceName.equals(configuredId) || rawDeviceName.equals(physicalId));
        if (!matches) return;

        lastBeaconSeenMs = System.currentTimeMillis();
        vehicleController.onBeaconDetected(rawDeviceName, rssi);
        cancelBeaconAbsenceCheck();
        scheduleBeaconAbsenceCheck();
    }

    // -------------------------------------------------------------------------
    // Association state changes
    // -------------------------------------------------------------------------

    private void onAssociationStateChanged(InsuranceSessionState newState,
                                           InsuranceSessionState previousState,
                                           VehicleAssociationController.AssociationSnapshot snapshot) {
        currentSessionState = newState;
        Log.d(TAG, "Association: " + previousState + " → " + newState);

        switch (newState) {
            case CANDIDATE_VEHICLE_DETECTED:
                eventHistory.record(InsuranceEventHistory.HistoryEventType.VEHICLE_CANDIDATE,
                    "rssi=" + snapshot.lastRssi);
                break;

            case VEHICLE_ASSOCIATED:
                if (previousState == InsuranceSessionState.CANDIDATE_VEHICLE_DETECTED
                        || previousState == InsuranceSessionState.WAITING_FOR_VEHICLE) {
                    // GAP #1 — session is created HERE, not on mode activation
                    startNewSession(snapshot);
                } else if (previousState == InsuranceSessionState.ASSOCIATION_DEGRADED) {
                    publishEvent(InsuranceEventType.VEHICLE_ASSOCIATION_CONFIRMED, false);
                }
                eventHistory.record(InsuranceEventHistory.HistoryEventType.ASSOCIATION_CONFIRMED,
                    "rssi=" + snapshot.lastRssi);
                // GAP #9 — update scan profile
                vehicleController.setScanProfile("VEHICLE_ASSOCIATED", "Association confirmed");
                break;

            case ASSOCIATION_DEGRADED:
                publishEvent(InsuranceEventType.VEHICLE_ASSOCIATION_DEGRADED, false);
                break;

            case VEHICLE_DISCONNECTED:
                publishEvent(InsuranceEventType.VEHICLE_ASSOCIATION_LOST, false);
                eventHistory.record(InsuranceEventHistory.HistoryEventType.BEACON_LOST,
                    "session=" + InsuranceTelemetryEventFactory.maskId(sessionId));
                endSession();
                break;

            case IDLE:
            case WAITING_FOR_VEHICLE:
                break;

            default:
                break;
        }
        notifyStatusChanged();
    }

    // -------------------------------------------------------------------------
    // Session lifecycle — GAP #1
    // -------------------------------------------------------------------------

    private void startNewSession(VehicleAssociationController.AssociationSnapshot snapshot) {
        // Guard: do not create a new session if one is already active
        if (sessionId != null && isInitialEventSent) {
            Log.w(TAG, "Session already active — not creating duplicate: "
                + InsuranceTelemetryEventFactory.maskId(sessionId));
            return;
        }
        sessionId          = UUID.randomUUID().toString();
        isInitialEventSent = false;
        sessionCreationMs  = System.currentTimeMillis();
        Log.d(TAG, "New session started: " + InsuranceTelemetryEventFactory.maskId(sessionId));
        publishEvent(InsuranceEventType.VEHICLE_ASSOCIATION_STARTED, true);
        schedulePeriodicVerification();
        // GAP #13 — persist recovery metadata after initial event is queued
        sessionRecovery.save(sessionId, isInitialEventSent, sessionCreationMs);
    }

    private void endSession() {
        Log.d(TAG, "Session ended: " + InsuranceTelemetryEventFactory.maskId(sessionId));
        cancelPeriodicVerification();
        cancelBeaconAbsenceCheck();
        sessionRecovery.clear();
        sessionId           = null;
        isInitialEventSent  = false;
        sessionCreationMs   = 0L;
        // GAP #1 — return to WAITING_FOR_VEHICLE, not IDLE, so mode stays active
        currentSessionState = InsuranceSessionState.WAITING_FOR_VEHICLE;
        vehicleController.reset();
        vehicleController.setScanProfile("WAITING_FOR_VEHICLE", "Session ended");
        notifyStatusChanged();
    }

    // -------------------------------------------------------------------------
    // Biometric freshness boundary detection
    // -------------------------------------------------------------------------

    private void scheduleBiometricFreshnessCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try { checkBiometricFreshnessBoundary(); }
            catch (Exception e) { Log.e(TAG, "Biometric freshness check error", e); }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void checkBiometricFreshnessBoundary() {
        BiometricFreshnessState current = eventFactory.computeFreshnessState();
        BiometricFreshnessState last    = lastFreshnessState;
        if (current == last) return;

        Log.d(TAG, "Biometric freshness: " + last + " → " + current);
        lastFreshnessState = current;
        eventHistory.record(InsuranceEventHistory.HistoryEventType.AUTH_CHANGED,
            last + "→" + current);

        if (sessionId != null && isInitialEventSent) {
            publishEvent(InsuranceEventType.AUTH_FRESHNESS_CHANGED, false);
        }
        notifyStatusChanged();
    }

    // -------------------------------------------------------------------------
    // Periodic verification
    // -------------------------------------------------------------------------

    private void schedulePeriodicVerification() {
        cancelPeriodicVerification();
        long intervalMs = config.getPeriodicVerifyMs();
        periodicVerifyFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (sessionId != null && isInitialEventSent
                        && currentSessionState == InsuranceSessionState.VEHICLE_ASSOCIATED) {
                    publishEvent(InsuranceEventType.PERIODIC_VERIFICATION, false);
                }
            } catch (Exception e) { Log.e(TAG, "Periodic verification error", e); }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        Log.d(TAG, "Periodic verification scheduled every " + config.getPeriodicVerifyMinutes() + " min");
    }

    private void cancelPeriodicVerification() {
        if (periodicVerifyFuture != null && !periodicVerifyFuture.isDone()) {
            periodicVerifyFuture.cancel(false);
        }
    }

    // -------------------------------------------------------------------------
    // Beacon absence monitoring
    // -------------------------------------------------------------------------

    private void scheduleBeaconAbsenceCheck() {
        long graceMs = config.getBeaconLossGraceMs();
        beaconAbsenceCheckFuture = scheduler.schedule(() -> {
            try {
                long absentMs = System.currentTimeMillis() - lastBeaconSeenMs;
                vehicleController.onBeaconAbsent(absentMs);
            } catch (Exception e) { Log.e(TAG, "Beacon absence check error", e); }
        }, graceMs + 1000, TimeUnit.MILLISECONDS);
    }

    private void cancelBeaconAbsenceCheck() {
        if (beaconAbsenceCheckFuture != null && !beaconAbsenceCheckFuture.isDone()) {
            beaconAbsenceCheckFuture.cancel(false);
        }
    }

    // -------------------------------------------------------------------------
    // Event publishing
    // -------------------------------------------------------------------------

    private void publishEvent(InsuranceEventType eventType, boolean forceInitial) {
        if (!forceInitial && !isCriticalEvent(eventType)) {
            long now = System.currentTimeMillis();
            if ((now - lastPublishMs) < config.getMinPublishIntervalMs()) {
                Log.d(TAG, "Skipping " + eventType + " — within minimum publish interval");
                return;
            }
        }

        boolean isInitial = forceInitial && !isInitialEventSent;
        VehicleAssociationController.AssociationSnapshot snapshot =
            vehicleController.getSnapshot(eventType.name());

        InsuranceTelemetryEvent event = eventFactory.build(
            eventType, sessionId, isInitial, snapshot, locationProvider);

        if (isInitial) {
            isInitialEventSent = true;
            eventHistory.record(InsuranceEventHistory.HistoryEventType.INITIAL_EVENT_SENT,
                "session=" + InsuranceTelemetryEventFactory.maskId(sessionId));
            // GAP #13 — update recovery metadata now that initial event is sent
            sessionRecovery.save(sessionId, true, sessionCreationMs);
        }

        lastPublishMs = System.currentTimeMillis();
        publisher.publish(event);
    }

    /**
     * Called when the backend reconnects and requests a resync.
     * Publishes a PERIODIC_VERIFICATION event immediately if a session is active,
     * bypassing the minimum publish interval so liveTrips is populated right away.
     */
    public void publishResync() {
        if (sessionId != null && isInitialEventSent) {
            Log.d(TAG, "Resync requested — publishing current state");
            VehicleAssociationController.AssociationSnapshot snapshot =
                vehicleController.getSnapshot(InsuranceEventType.PERIODIC_VERIFICATION.name());
            InsuranceTelemetryEvent event = eventFactory.build(
                InsuranceEventType.PERIODIC_VERIFICATION, sessionId, false, snapshot, locationProvider);
            lastPublishMs = System.currentTimeMillis();
            publisher.publish(event);
        } else {
            Log.d(TAG, "Resync requested but no active session — skipping");
        }
    }

    private boolean isCriticalEvent(InsuranceEventType type) {
        return type == InsuranceEventType.VEHICLE_ASSOCIATION_STARTED
            || type == InsuranceEventType.VEHICLE_ASSOCIATION_LOST
            || type == InsuranceEventType.SESSION_ENDED;
    }

    // -------------------------------------------------------------------------
    // Status notification
    // -------------------------------------------------------------------------

    private void notifyStatusChanged() {
        StatusListener l = statusListener;
        if (l == null) return;
        mainHandler.post(() -> l.onInsuranceStatusChanged(
            currentSessionState,
            sessionId,
            vehicleController.isBeaconDetected(),
            lastFreshnessState,
            publisher.getPublisherState()));
    }

    // -------------------------------------------------------------------------
    // GAP #6 — AssociationEvidenceSnapshot for diagnostics
    // -------------------------------------------------------------------------

    public AssociationEvidenceSnapshot getEvidenceSnapshot() {
        long bioAge = -1L;
        long lastAuth = biometricManager.getLastAuthTimeMs();
        if (lastAuth > 0) bioAge = System.currentTimeMillis() - lastAuth;

        return new AssociationEvidenceSnapshot(
            lastFreshnessState,
            bioAge,
            vehicleController.getState(),
            vehicleController.getAdvertisementCount(),
            vehicleController.getConfirmationWindowHits(),
            vehicleController.getAverageRssi(),
            vehicleController.getAssociationDurationMs(),
            vehicleController.getLastSeenAtMs(),
            locationProvider.isLocationAvailable(),
            locationProvider.isSpeedAvailable(),
            locationProvider.getSpeedSource(),
            locationProvider.getLastSpeedUpdateMs(),
            currentSessionState
        );
    }

    // -------------------------------------------------------------------------
    // GAP #11 — event history
    // -------------------------------------------------------------------------

    public List<Map<String, Object>> getEventHistory() {
        return eventHistory.toList();
    }

    // -------------------------------------------------------------------------
    // Accessors for health endpoint and UI
    // -------------------------------------------------------------------------

    public InsuranceSessionState getSessionState()    { return currentSessionState; }
    public String                getSessionId()       { return sessionId; }
    public boolean               isSessionActive()    { return sessionId != null && isInitialEventSent; }
    public InsuranceTelemetryPublisher getPublisher() { return publisher; }
    public BiometricFreshnessState getFreshnessState() { return lastFreshnessState; }
    public VehicleAssociationController getVehicleController() { return vehicleController; }
    public InsuranceLocationProvider getLocationProvider()     { return locationProvider; }
}
