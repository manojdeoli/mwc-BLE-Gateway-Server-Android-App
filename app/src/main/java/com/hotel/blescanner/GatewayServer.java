package com.hotel.blescanner;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.hotel.blescanner.config.BeaconConfigManager;
import com.hotel.blescanner.context.ContextEvent;
import com.hotel.blescanner.transport.BackendAdvisory;
import com.hotel.blescanner.transport.ValidationController;
import com.hotel.blescanner.mode.DeviceModePrefs;
import com.hotel.blescanner.mode.DeviceMode;
import fi.iki.elonen.NanoWSD;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class GatewayServer extends NanoWSD {

    private static final String TAG      = "GatewayServer";
    private static final String T_CONFIG = "[CONFIG]";
    private static final int    PORT     = 8080;

    private final Map<String, WebSocket> webClients   = new ConcurrentHashMap<>();
    private final Map<String, BLEData>   bleDataStore = new ConcurrentHashMap<>();
    private final Gson   gson   = new Gson();
    private final String userId;
    private final DeviceModePrefs deviceModePrefs;

    /**
     * Periodic ping scheduler — sends a WebSocket ping to every connected client
     * every 15 seconds to prevent browser-side idle timeout (1006 disconnects).
     * NanoWSD does not send keepalives by default; without this the browser
     * closes the connection after its own idle threshold (~30s on most clients).
     */
    private final ScheduledExecutorService pingScheduler =
        Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pingFuture;

    private volatile ValidationController validationController;

    /**
     * Single source of truth for beacon identity and zone mapping.
     * Replaces the three private methods that were previously hardcoded here:
     *   isAllowedDevice(), mapDeviceToZone(), mapBeaconToZone().
     * Set by BLEScanService after BeaconConfigManager is initialised.
     */
    private volatile BeaconConfigManager beaconConfigManager;

    public GatewayServer(String userId, android.content.Context context) {
        super(PORT);
        this.userId = userId;
        this.deviceModePrefs = new DeviceModePrefs(context);
    }

    @Override
    public void start() throws IOException {
        super.start();
        pingFuture = pingScheduler.scheduleAtFixedRate(() -> {
            for (Map.Entry<String, WebSocket> entry : new HashMap<>(webClients).entrySet()) {
                try {
                    WebSocket ws = entry.getValue();
                    if (ws != null && ws.isOpen()) {
                        ws.ping(new byte[0]);
                    } else {
                        webClients.remove(entry.getKey());
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Ping failed for " + entry.getKey() + " — removing");
                    webClients.remove(entry.getKey());
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
        Log.d(TAG, "GatewayServer started with 15s ping keepalive");
    }

    @Override
    public void stop() {
        if (pingFuture != null) pingFuture.cancel(false);
        pingScheduler.shutdownNow();
        super.stop();
        Log.d(TAG, "GatewayServer stopped");
    }

    public void setValidationController(ValidationController controller) {
        this.validationController = controller;
    }

    public void setBeaconConfigManager(BeaconConfigManager manager) {
        this.beaconConfigManager = manager;
    }

    /** Returns true if at least one WebSocket client is currently connected. */
    public boolean hasConnectedClients() {
        return !webClients.isEmpty();
    }

    /**
     * Callback interface — notified when a new WebSocket client connects.
     * Used by ValidationController to re-send pending validation results
     * on reconnect without relying on a retry timer.
     */
    public interface ClientConnectedListener {
        void onClientConnected();
    }

    private volatile ClientConnectedListener clientConnectedListener;

    public void setClientConnectedListener(ClientConnectedListener listener) {
        this.clientConnectedListener = listener;
    }

    // -------------------------------------------------------------------------
    // HTTP — unchanged
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Insurance health / diagnostic support — additive, injected by BLEScanService
    // -------------------------------------------------------------------------

    public interface InsuranceHealthProvider {
        /** Returns an additive insurance health block for the /health endpoint. */
        Map<String, Object> getInsuranceHealthBlock();
        /** Returns the current device mode name. */
        String getDeviceModeName();
    }

    private volatile InsuranceHealthProvider insuranceHealthProvider;

    public void setInsuranceHealthProvider(InsuranceHealthProvider provider) {
        this.insuranceHealthProvider = provider;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if ("/health".equals(uri)) {
            Map<String, Object> health = new HashMap<>();
            health.put("status",     "ok");
            health.put("webClients", webClients.size());
            health.put("timestamp",  System.currentTimeMillis());
            // Additive: device mode and insurance block (null-safe)
            InsuranceHealthProvider ihp = insuranceHealthProvider;
            if (ihp != null) {
                health.put("deviceMode", ihp.getDeviceModeName());
                Map<String, Object> insBlock = ihp.getInsuranceHealthBlock();
                if (insBlock != null) health.put("insurance", insBlock);
            }
            Response response = newFixedLengthResponse(
                Response.Status.OK, "application/json", gson.toJson(health));
            response.addHeader("Access-Control-Allow-Origin",  "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type");
            return response;
        }
        return super.serve(session);
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new BLEWebSocket(handshake);
    }

    // -------------------------------------------------------------------------
    // Broadcast methods
    // -------------------------------------------------------------------------

    /**
     * Broadcasts a BLE beacon event to all WebSocket clients.
     *
     * Uses BeaconConfigManager for all three previously hardcoded operations:
     *   isAllowedDevice()  → beaconConfigManager.isAllowedDevice()
     *   mapDeviceToZone()  → beaconConfigManager.mapToLogicalName()
     *   mapBeaconToZone()  → beaconConfigManager.getZone()
     *
     * WebSocket JSON schema is unchanged:
     * {"beaconName":"HotelGate","rssi":-55,"zone":"Hotel Entry Gate","timestamp":...}
     */
    public void broadcastBLEEvent(String beaconName, int rssi) {
        new Thread(() -> {
            try {
                BeaconConfigManager bcm = beaconConfigManager;
                if (bcm == null || !bcm.isAllowedDevice(beaconName)) return;

                String mappedName = bcm.mapToLogicalName(beaconName);
                String zone       = bcm.getZone(mappedName);

                BLEData data = new BLEData(mappedName, rssi, zone, System.currentTimeMillis());
                bleDataStore.put(userId, data);
                broadcastToAllClients(gson.toJson(data));
            } catch (Exception e) {
                Log.e(TAG, "Error in broadcastBLEEvent", e);
            }
        }).start();
    }

    /** Unchanged — broadcasts mobility context event. */
    public void broadcastContextEvent(ContextEvent event) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(event));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting context event", e);
            }
        }).start();
    }

    /** Broadcasts an exit signal (CLEAR or AMBIGUOUS) — unchanged contract. */
    public void broadcastExitSignal(String beaconName, String signal,
                                    String confidence, String journeyId) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(
                    new ExitSignalEvent(beaconName, signal, confidence, journeyId)));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting exit signal", e);
            }
        }).start();
    }

    /** Broadcasts a validation lifecycle event (REQUIRED/SUCCESS) — unchanged contract. */
    public void broadcastValidationEvent(String journeyId, String status, String method) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(new ValidationEvent(journeyId, status, method)));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting validation event", e);
            }
        }).start();
    }

    /** Broadcasts an NFC card-read validation event — unchanged contract. */
    public void broadcastNfcValidationEvent(String journeyId, String tagId, String status) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(new NfcValidationEvent(journeyId, tagId, status)));
            } catch (Exception e) {
                Log.e(TAG, "[NFC] Error broadcasting NFC validation event", e);
            }
        }).start();
    }

    /** Broadcasts a full insurance telemetry event to all WebSocket clients.
     *  The PC backend's WebSocket client receives this and populates liveTrips.
     *  This replaces the HTTP POST approach — no firewall issues, aligns with Hotel pattern. */
    public void broadcastInsuranceTelemetry(com.hotel.blescanner.insurance.InsuranceTelemetryEvent event) {
        new Thread(() -> {
            try {
                com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject();
                wrapper.addProperty("type", "insuranceTelemetry");
                wrapper.add("payload", gson.toJsonTree(event));
                broadcastToAllClients(wrapper.toString());
                Log.d(TAG, "[INSURANCE] Telemetry broadcast over WS: " + event.eventType);
            } catch (Exception e) {
                Log.e(TAG, "[INSURANCE] Error broadcasting telemetry", e);
            }
        }).start();
    }

    /** Broadcasts an insurance diagnostic status event to local WebSocket clients only.
     *  Used for development visibility. React Insurance dashboard must NOT depend on this.
     *  Does not expose sensitive data (no phone number, no precise location, no policy details). */
    public void broadcastInsuranceStatus(String sessionId, String sessionState,
                                         boolean beaconDetected, String authFreshness,
                                         String publisherState) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(
                    new InsuranceStatusEvent(sessionId, sessionState,
                                             beaconDetected, authFreshness, publisherState)));
            } catch (Exception e) {
                Log.e(TAG, "[INSURANCE] Error broadcasting insurance status", e);
            }
        }).start();
    }

    /** Broadcasts a biometric validation event — same schema as NFC, method=BIOMETRIC. */
    public void broadcastBiometricValidationEvent(String journeyId, String status) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(new BiometricValidationEvent(journeyId, status)));
            } catch (Exception e) {
                Log.e(TAG, "[BIOMETRIC] Error broadcasting biometric validation event", e);
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // WebSocket inner class
    // -------------------------------------------------------------------------

    private class BLEWebSocket extends WebSocket {
        private final String clientId;

        public BLEWebSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
            this.clientId = "client_" + System.currentTimeMillis();
        }

        @Override
        protected void onOpen() {
            Log.d(TAG, "WebSocket opened: " + clientId);
            webClients.put(clientId, this);
            if (bleDataStore.containsKey(userId)) {
                try { send(gson.toJson(bleDataStore.get(userId))); }
                catch (IOException e) { Log.e(TAG, "Error sending initial data", e); }
            }
            // Notify ValidationController so any pending biometric result
            // can be re-broadcast immediately to this new client
            ClientConnectedListener ccl = clientConnectedListener;
            if (ccl != null) ccl.onClientConnected();
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            Log.d(TAG, "WebSocket closed: " + clientId);
            webClients.remove(clientId);
            // Only notify ValidationController if NOT in INSURANCE mode.
            // INSURANCE mode is sticky — it must not reset on every WS reconnect.
            if (webClients.isEmpty()
                    && deviceModePrefs.getLastMode() != DeviceMode.INSURANCE) {
                ValidationController vc = validationController;
                if (vc != null) vc.onWebSocketDisconnected();
            }
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            String msg = message.getTextPayload();
            Log.d(TAG, "Received: " + msg);

            // Existing subscribe handling — Hotel app connects
            if (msg.contains("subscribe")) {
                Log.d(TAG, "Client subscribed: " + clientId + " — saving HOTEL as default mode");
                deviceModePrefs.saveMode(DeviceMode.HOTEL);
            }

            // SERVER_URL message — store in TransportConfig for beacon config fetch
            if (msg.contains("SERVER_URL")) {
                try {
                    com.google.gson.JsonObject obj = gson.fromJson(msg, com.google.gson.JsonObject.class);
                    if (obj.has("beaconConfigUrl")) {
                        String url = obj.get("beaconConfigUrl").getAsString();
                        BLEScanService svc = BLEScanService.getActiveInstance();
                        if (svc != null) {
                            svc.onServerUrlReceived(url);
                        }
                        Log.d(TAG, "[CONFIG] Beacon config server URL received: " + url);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse SERVER_URL message: " + e.getMessage());
                }
            }

            // Backend requesting a resync — publish current state immediately
            if (msg.contains("\"requestResync\"")) {
                Log.d(TAG, "[INSURANCE] requestResync received — triggering resync");
                BLEScanService svc = BLEScanService.getActiveInstance();
                if (svc != null) svc.onResyncRequested();
                return;
            }

            // Insurance config update — detected by type=insuranceConfig
            // Additive: does not affect HOTEL or TRANSPORT config paths.
            if (msg.contains("\"insuranceConfig\"") || msg.contains("\"type\":\"insuranceConfig\"")) {
                Log.d(TAG, "[INSURANCE] Insurance config message received — saving INSURANCE as default mode");
                // Log the backendBaseUrl received so we can confirm the PC IP reached Android
                try {
                    com.google.gson.JsonObject cfgObj = gson.fromJson(msg, com.google.gson.JsonObject.class);
                    String receivedBackendUrl = cfgObj.has("backendBaseUrl") ? cfgObj.get("backendBaseUrl").getAsString() : "MISSING";
                    String receivedPhone      = cfgObj.has("phoneNumber")    ? cfgObj.get("phoneNumber").getAsString()    : "MISSING";
                    Log.d(TAG, "[INSURANCE] backendBaseUrl received: " + receivedBackendUrl);
                    Log.d(TAG, "[INSURANCE] phoneNumber received: " + receivedPhone);
                } catch (Exception e) {
                    Log.w(TAG, "[INSURANCE] Could not parse insuranceConfig fields: " + e.getMessage());
                }
                deviceModePrefs.saveMode(DeviceMode.INSURANCE);
                BLEScanService svc = BLEScanService.getActiveInstance();
                if (svc != null) {
                    svc.onInsuranceConfigReceived(msg);
                } else {
                    Log.w(TAG, "[INSURANCE] BLEScanService not available — insurance config ignored");
                }
                return;
            }

            // Beacon config update from backend (Phase 8)
            // Detected by presence of "beacons" array alongside optional "version" field.
            // Distinct from advisory messages which always contain "riskLevel".
            if (msg.contains("\"beacons\"")) {
                Log.d(TAG, T_CONFIG + " Beacon config message received — saving HOTEL as default mode");
                deviceModePrefs.saveMode(DeviceMode.HOTEL);
                BLEScanService svc = BLEScanService.getActiveInstance();
                if (svc != null) {
                    svc.onBeaconConfigReceived(msg);
                } else {
                    Log.w(TAG, T_CONFIG + " BLEScanService not available — config update ignored");
                }
                return;
            }

            // Advisory parsing — strict Gson, no fragile contains() check
            ValidationController vc = validationController;
            if (vc != null) {
                try {
                    BackendAdvisory advisory = gson.fromJson(msg, BackendAdvisory.class);
                    if (advisory != null && advisory.isValid()) {
                        vc.applyAdvisory(advisory);
                    }
                } catch (JsonSyntaxException e) {
                    Log.w(TAG, "Ignoring unparseable message: " + e.getMessage());
                }
            }
        }

        @Override protected void onPong(WebSocketFrame pong) {
            Log.d(TAG, "Pong received from " + clientId);
        }

        @Override
        protected void onException(IOException exception) {
            Log.e(TAG, "WebSocket exception for " + clientId, exception);
            webClients.remove(clientId);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void broadcastToAllClients(String json) {
        for (Map.Entry<String, WebSocket> entry : new HashMap<>(webClients).entrySet()) {
            try {
                WebSocket client = entry.getValue();
                if (client != null && client.isOpen()) {
                    client.send(json);
                } else {
                    webClients.remove(entry.getKey());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error sending to client " + entry.getKey(), e);
                webClients.remove(entry.getKey());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Data models — event schemas unchanged
    // -------------------------------------------------------------------------

    private static class BLEData {
        String beaconName; int rssi; String zone; long timestamp;
        BLEData(String n, int r, String z, long t) {
            beaconName = n; rssi = r; zone = z; timestamp = t;
        }
    }

    private static class ExitSignalEvent {
        final String eventType  = "exitSignal";
        final String beaconName;
        final String signal;
        final String confidence;
        final String journeyId;
        final long   timestamp  = System.currentTimeMillis();
        ExitSignalEvent(String beaconName, String signal, String confidence, String journeyId) {
            this.beaconName = beaconName; this.signal = signal;
            this.confidence = confidence; this.journeyId = journeyId;
        }
    }

    private static class ValidationEvent {
        final String eventType = "validation";
        final String status;
        final String method;
        final String journeyId;
        final long   timestamp = System.currentTimeMillis();
        ValidationEvent(String journeyId, String status, String method) {
            this.journeyId = journeyId; this.status = status; this.method = method;
        }
    }

    private static class NfcValidationEvent {
        final String eventType = "validation";
        final String status;
        final String method    = "NFC";
        final String journeyId;
        final String tagId;
        final long   timestamp = System.currentTimeMillis();
        NfcValidationEvent(String journeyId, String tagId, String status) {
            this.journeyId = journeyId; this.tagId = tagId; this.status = status;
        }
    }

    private static class BiometricValidationEvent {
        final String eventType = "validation";
        final String status;
        final String method    = "BIOMETRIC";
        final String journeyId;
        final long   timestamp = System.currentTimeMillis();
        BiometricValidationEvent(String journeyId, String status) {
            this.journeyId = journeyId; this.status = status;
        }
    }

    /** Local diagnostic event — for development visibility only.
     *  React Insurance dashboard must NOT depend on this event.
     *  No sensitive data: no phone number, no precise location, no policy details. */
    private static class InsuranceStatusEvent {
        final String  eventType          = "insuranceStatus";
        final String  mode               = "INSURANCE";
        final String  sessionId;
        final String  sessionState;
        final boolean vehicleBeaconDetected;
        final String  authFreshness;
        final String  publisherState;
        final long    timestamp          = System.currentTimeMillis();
        InsuranceStatusEvent(String sessionId, String sessionState,
                             boolean vehicleBeaconDetected, String authFreshness,
                             String publisherState) {
            this.sessionId             = sessionId;
            this.sessionState          = sessionState;
            this.vehicleBeaconDetected = vehicleBeaconDetected;
            this.authFreshness         = authFreshness;
            this.publisherState        = publisherState;
        }
    }
}
