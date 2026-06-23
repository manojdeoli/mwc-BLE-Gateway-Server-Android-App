package com.hotel.blescanner;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.hotel.blescanner.context.ContextEvent;
import com.hotel.blescanner.transport.BackendAdvisory;
import com.hotel.blescanner.transport.ValidationController;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GatewayServer extends NanoWSD {
    private static final String TAG  = "GatewayServer";
    private static final int    PORT = 8080;

    private final Map<String, WebSocket> webClients   = new ConcurrentHashMap<>();
    private final Map<String, BLEData>   bleDataStore = new ConcurrentHashMap<>();
    private final Gson   gson   = new Gson();
    private final String userId;

    private volatile ValidationController validationController;

    public GatewayServer(String userId) {
        super(PORT);
        this.userId = userId;
    }

    public void setValidationController(ValidationController controller) {
        this.validationController = controller;
    }

    // -------------------------------------------------------------------------
    // HTTP — unchanged
    // -------------------------------------------------------------------------

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if ("/health".equals(uri)) {
            Map<String, Object> health = new HashMap<>();
            health.put("status",     "ok");
            health.put("webClients", webClients.size());
            health.put("timestamp",  System.currentTimeMillis());
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

    /** Unchanged — broadcasts BLE beacon event to all connected WebSocket clients. */
    public void broadcastBLEEvent(String beaconName, int rssi) {
        new Thread(() -> {
            try {
                if (!isAllowedDevice(beaconName)) return;
                String mappedName = mapDeviceToZone(beaconName);
                BLEData data = new BLEData(
                    mappedName, rssi, mapBeaconToZone(mappedName), System.currentTimeMillis());
                bleDataStore.put(userId, data);
                broadcastToAllClients(gson.toJson(data));
            } catch (Exception e) {
                Log.e(TAG, "Error in broadcastBLEEvent", e);
            }
        }).start();
    }

    /** Unchanged — broadcasts mobility context event to all connected WebSocket clients. */
    public void broadcastContextEvent(ContextEvent event) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(event));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting context event", e);
            }
        }).start();
    }

    /**
     * Broadcasts a validation lifecycle event (REQUIRED or SUCCESS).
     * Format: {"eventType":"validation","status":"REQUIRED","method":"BLE",
     *          "beaconName":"HotelGate","timestamp":...}
     * Unchanged — existing BLE validation contract preserved exactly.
     */
    public void broadcastValidationEvent(String beaconName, String status) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(new ValidationEvent(beaconName, status)));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting validation event", e);
            }
        }).start();
    }

    /**
     * Broadcasts an NFC card-read validation event.
     *
     * Format:
     * {
     *   "eventType": "validation",
     *   "status":    "SUCCESS",
     *   "method":    "NFC",
     *   "journeyId": "journey-20240418-001",
     *   "tagId":     "A3F204BC",
     *   "timestamp": 1712345678910
     * }
     *
     * Parallel to broadcastValidationEvent() — same eventType, different method field.
     * Existing broadcastValidationEvent() signature and behaviour are untouched.
     *
     * @param journeyId correlates with the advisory that triggered this validation
     * @param tagId     uppercase hex UID of the card that was tapped
     * @param status    "SUCCESS" or "FAILED"
     */
    public void broadcastNfcValidationEvent(String journeyId, String tagId, String status) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(new NfcValidationEvent(journeyId, tagId, status)));
            } catch (Exception e) {
                Log.e(TAG, "[NFC] Error broadcasting NFC validation event", e);
            }
        }).start();
    }

    /**
     * Fix 3.7: broadcasts explainability event — WHY validation was triggered.
     * Format: {"eventType":"validationReason","beaconName":"HotelGate",
     *          "reason":"BIOMETRIC_REQUIRED","biometricFresh":false,"timestamp":...}
     */
    public void broadcastValidationReasonEvent(String beaconName, String reason, boolean biometricFresh) {
        new Thread(() -> {
            try {
                broadcastToAllClients(gson.toJson(
                    new ValidationReasonEvent(beaconName, reason, biometricFresh)));
            } catch (Exception e) {
                Log.e(TAG, "Error broadcasting validation reason event", e);
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
        }

        @Override
        protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            Log.d(TAG, "WebSocket closed: " + clientId);
            webClients.remove(clientId);
            if (webClients.isEmpty()) {
                ValidationController vc = validationController;
                if (vc != null) vc.onWebSocketDisconnected();
            }
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            String msg = message.getTextPayload();
            Log.d(TAG, "Received: " + msg);

            // Existing subscribe handling — unchanged
            if (msg.contains("subscribe")) {
                Log.d(TAG, "Client subscribed: " + clientId);
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

        @Override protected void onPong(WebSocketFrame pong) {}

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

    private boolean isAllowedDevice(String name) {
        if (name == null) return false;
        return name.equals("HotelGate")     || name.equals("HotelKiosk")  ||
               name.equals("HotelElevator") || name.equals("HotelRoom")   ||
               name.equals("ER26B00001")    || name.equals("ER26B00002")  ||
               name.equals("ER26B00003")    || name.equals("ER26B00004")  ||
               name.equals("BCPro_212364");
    }

    private String mapDeviceToZone(String deviceName) {
        switch (deviceName) {
            case "ER26B00001":
            case "BCPro_212364": return "HotelGate";
            case "ER26B00002":   return "HotelKiosk";
            case "ER26B00003":   return "HotelElevator";
            case "ER26B00004":   return "HotelRoom";
            default:             return deviceName;
        }
    }

    private String mapBeaconToZone(String beaconName) {
        String lower = beaconName.toLowerCase();
        if (lower.contains("gate"))     return "Hotel Entry Gate";
        if (lower.contains("kiosk"))    return "Check-in Kiosk";
        if (lower.contains("elevator")) return "Elevator Lobby";
        if (lower.contains("room"))     return "Room 1337";
        return "Unknown Area";
    }

    // -------------------------------------------------------------------------
    // Data models
    // -------------------------------------------------------------------------

    private static class BLEData {
        String beaconName; int rssi; String zone; long timestamp;
        BLEData(String beaconName, int rssi, String zone, long timestamp) {
            this.beaconName = beaconName; this.rssi = rssi;
            this.zone = zone; this.timestamp = timestamp;
        }
    }

    private static class ValidationEvent {
        final String eventType = "validation";
        final String status;
        final String method    = "BLE";
        final String beaconName;
        final long   timestamp = System.currentTimeMillis();
        ValidationEvent(String beaconName, String status) {
            this.beaconName = beaconName; this.status = status;
        }
    }

    /**
     * NFC card-read validation event.
     * Same eventType as ValidationEvent so the web app handles both uniformly.
     * Differentiated by method="NFC" and the presence of tagId / journeyId.
     */
    private static class NfcValidationEvent {
        final String eventType = "validation";
        final String status;
        final String method    = "NFC";
        final String journeyId;
        final String tagId;     // uppercase hex UID, e.g. "A3F204BC"
        final long   timestamp = System.currentTimeMillis();
        NfcValidationEvent(String journeyId, String tagId, String status) {
            this.journeyId = journeyId;
            this.tagId     = tagId;
            this.status    = status;
        }
    }

    /** Fix 3.7: explainability payload. */
    private static class ValidationReasonEvent {
        final String  eventType      = "validationReason";
        final String  beaconName;
        final String  reason;
        final boolean biometricFresh;
        final long    timestamp      = System.currentTimeMillis();
        ValidationReasonEvent(String beaconName, String reason, boolean biometricFresh) {
            this.beaconName     = beaconName;
            this.reason         = reason;
            this.biometricFresh = biometricFresh;
        }
    }
}
