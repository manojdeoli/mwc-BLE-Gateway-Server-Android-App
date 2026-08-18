# End-to-End Testing Guide
## HotelBLEScanner (Android) + Insurance Backend (Node.js)

---

## System Overview

```
[BLE Device / Vehicle Beacon]
        ↓  Bluetooth LE
[Android App — HotelBLEScanner]
        ↓  HTTP POST  (port 5000)
[Insurance Backend — Node.js]
        ↓  Vonage CAMARA APIs
[Location / SIM Swap / Identity Insights]
        ↓
[Insurance Dashboard — React]
```

**Ports at a glance**

| Component            | Address                          |
|----------------------|----------------------------------|
| Android WebSocket    | ws://[ANDROID_IP]:8080           |
| Android health       | http://[ANDROID_IP]:8080/health  |
| Backend API          | http://[PC_IP]:5000              |
| Backend health       | http://[PC_IP]:5000/health       |
| API docs (Swagger)   | http://[PC_IP]:5000/api/api-docs |

---

## Prerequisites

### Hardware
- Android device (API 26+) with Bluetooth and WiFi
- PC running Windows with Node.js 18+ and Android Studio
- One BLE beacon device (your vehicle beacon)
- All three devices on the **same WiFi network**

### Software — Backend
```
cd "C:\My Work\OneDrive_1_15-1-2026\insurance-vonage-app-code 1\backend"
npm install
```

### Software — Android
- Android Studio with the project open at:
  `C:\My Work\OneDrive_1_15-1-2026\BEL-Scanner\HotelBLEScanner`
- Gradle sync completed

---

## Part 1 — Environment Setup

### Step 1.1 — Find your PC's local IP address

Open Command Prompt:
```
ipconfig
```
Look for the IPv4 address on your WiFi adapter, e.g. `192.168.1.50`.
This is `[PC_IP]` used throughout this guide.

### Step 1.2 — Start the Insurance Backend

```
cd "C:\My Work\OneDrive_1_15-1-2026\insurance-vonage-app-code 1\backend"
npm start
```

Expected console output:
```
Backend running on http://localhost:5000
Mode: MOCK APIs
```

Verify it is reachable from your browser:
```
GET http://localhost:5000/health
```
Expected:
```json
{ "status": "OK", "mode": "MOCK", "timestamp": "..." }
```

### Step 1.3 — Find your BLE beacon's advertised name

Power on your BLE beacon. Use a BLE scanner app (e.g. nRF Connect on Android) to find the exact advertised device name. Note it down — this is your `vehicleBeaconId`. Example: `CAR-BLE-001`.

### Step 1.4 — Install and start the Android app

1. Connect the Android device via USB with USB Debugging enabled
2. In Android Studio click **Run**
3. Grant all permissions when prompted (Bluetooth, Location, Notifications)
4. Note the IP address shown on the app screen — this is `[ANDROID_IP]`

### Step 1.5 — Verify Android health endpoint

From your PC browser:
```
GET http://[ANDROID_IP]:8080/health
```
Expected:
```json
{ "status": "ok", "webClients": 0, "timestamp": 1234567890000 }
```
No `insurance` block should appear yet.

---

## Part 2 — Activate INSURANCE Mode (First Time Only)

> **Note:** This step is only required **once**. After the insurance config is sent,
> the app automatically activates INSURANCE mode on every subsequent restart.
> If you have already completed this step before, skip to Part 3.

### Step 2.1 — Open a WebSocket client

Use any WebSocket client tool. Options:
- Browser console (see snippet below)
- Postman → New WebSocket Request
- VS Code extension: Thunder Client or WebSocket Client

**Browser console snippet** (open DevTools on any page):
```javascript
const ws = new WebSocket('ws://[ANDROID_IP]:8080');
ws.onmessage = e => console.log('RECEIVED:', JSON.parse(e.data));
ws.onopen = () => console.log('Connected');
```

### Step 2.2 — Send the insurance config

Replace `[PC_IP]` and `vehicleBeaconId` with your actual values:

```javascript
ws.send(JSON.stringify({
  type: "insuranceConfig",
  backendBaseUrl: "http://[PC_IP]:5000",
  policyId: "POLICY-TEST-001",
  phoneNumber: "+447700900000",
  vehicleBeaconId: "CAR-BLE-001"
}));
```

Once sent, the app saves this config to SharedPreferences and saves `INSURANCE`
as the default mode. **All future app restarts will auto-activate INSURANCE mode
without repeating this step.**

To switch back to Hotel mode, connect the Hotel web application — it sends a
`subscribe` or beacon config message which saves `HOTEL` as the default mode.

### Step 2.3 — Verify WAITING_FOR_VEHICLE state

Immediately call the Android health endpoint:
```
GET http://[ANDROID_IP]:8080/health
```

**Expected — session must NOT exist yet:**
```json
{
  "deviceMode": "INSURANCE",
  "insurance": {
    "enabled": true,
    "configured": true,
    "sessionState": "WAITING_FOR_VEHICLE",
    "sessionActive": false,
    "sessionId": null,
    "beaconDetected": false,
    "backendReachability": "UNKNOWN"
  }
}
```

**If `sessionState` is anything other than `WAITING_FOR_VEHICLE` at this point, stop — the session lifecycle is broken.**

---

## Part 3 — Vehicle Association

### Step 3.1 — Bring the BLE beacon into range

Power on your BLE beacon and place it within 5 metres of the Android device.

### Step 3.2 — Watch the state progression

Poll the Android health endpoint every 3–5 seconds:
```
GET http://[ANDROID_IP]:8080/health
```

You should see this progression over ~15 seconds:

**Stage 1 — First advertisement detected:**
```json
{
  "insurance": {
    "sessionState": "CANDIDATE_VEHICLE_DETECTED",
    "sessionActive": false,
    "sessionId": null,
    "advertisementCount": 1
  }
}
```

**Stage 2 — Association confirmed (3 advertisements within 10 seconds):**
```json
{
  "insurance": {
    "sessionState": "VEHICLE_ASSOCIATED",
    "sessionActive": true,
    "sessionId": "a3f4...",
    "beaconDetected": true,
    "advertisementCount": 4,
    "averageRssi": -63.0,
    "associationDurationSeconds": 8,
    "scanProfile": "VEHICLE_ASSOCIATED"
  }
}
```

`sessionId` must only appear **after** `VEHICLE_ASSOCIATED`. Never before.

### Step 3.3 — Verify the WebSocket insuranceStatus event

Your WebSocket client should have received:
```json
{
  "eventType": "insuranceStatus",
  "mode": "INSURANCE",
  "sessionState": "VEHICLE_ASSOCIATED",
  "vehicleBeaconDetected": true,
  "authFreshness": "UNKNOWN",
  "publisherState": "CONNECTED",
  "timestamp": 1234567890000
}
```

---

## Part 4 — Verify Backend Received the Initial Event

### Step 4.1 — Check the backend console

When the Android app sends the initial event, the backend console should print:
```
🚀 [Telematics] Initial Trip Event - Triggering Vonage Security Checks for +447700900000
✅ [Location Retrieval] CAMARA Location: ...   (or a CAMARA unavailable warning in MOCK mode)
```

### Step 4.2 — Query the backend for the latest trip data

```
GET http://[PC_IP]:5000/api/insurance/trips/%2B447700900000/latest
```
(The `+` in the phone number must be URL-encoded as `%2B`)

**Expected response (key fields):**
```json
{
  "eventAccepted": true,
  "sessionId": "sess_...",
  "sessionState": "ACTIVE",
  "userAssociation": {
    "status": "UNKNOWN",
    "confidence": 20
  },
  "vehicleAssociation": {
    "status": "CONFIRMED",
    "confidence": 90
  },
  "locationConfidence": {
    "source": "GPS_FALLBACK",
    "confidence": 70
  },
  "triadScore": 64,
  "pricingDecision": {
    "adjustments": []
  },
  "observerState": {
    "isBluetoothConnected": true,
    "isAuthValid": false
  }
}
```

**What to check:**
- `eventAccepted: true` — backend accepted the event
- `vehicleAssociation.status: "CONFIRMED"` — beacon evidence was strong enough
- `sessionState: "ACTIVE"` — backend session is running
- `userAssociation.status` will be `"UNKNOWN"` until biometric auth is performed on the device

### Step 4.3 — Check the backend system health

```
GET http://[PC_IP]:5000/health
```
```json
{
  "status": "OK",
  "mode": "MOCK",
  "timestamp": "..."
}
```

---

## Part 5 — Biometric Authentication

Biometric auth raises `userAssociation.confidence` from 20 → 92 on the backend.

### Step 5.1 — Trigger biometric on the Android device

On the Android device, open the app and perform the biometric verification (fingerprint or PIN). This records the auth timestamp in `BiometricManager`.

### Step 5.2 — Wait for the next event to be sent

The Android app publishes an `AUTH_FRESHNESS_CHANGED` event when the freshness state transitions from `UNKNOWN` to `FRESH`. This happens within 60 seconds of the biometric check.

### Step 5.3 — Re-query the backend

```
GET http://[PC_IP]:5000/api/insurance/trips/%2B447700900000/latest
```

**Expected change:**
```json
{
  "userAssociation": {
    "status": "CONFIRMED",
    "confidence": 92
  },
  "triadScore": 85,
  "observerState": {
    "isAuthValid": true
  }
}
```

`triadScore` should now be above 80 (HIGH confidence) with both user and vehicle association confirmed.

---

## Part 6 — GPS and Speed Verification

### Step 6.1 — Check GPS availability on Android health

```
GET http://[ANDROID_IP]:8080/health
```
```json
{
  "insurance": {
    "gpsAvailable": true,
    "gpsPermissionGranted": true,
    "speedAvailable": true,
    "speedSource": "GPS"
  }
}
```

### Step 6.2 — Verify speed reaches the backend

In the backend response:
```json
{
  "observerState": {
    "currentSpeedMph": 0.0
  }
}
```
Speed of `0.0` is correct when stationary. The backend falls back to `65` mph if the Android app sends `null` — verify the Android app is sending a real value, not null, when GPS is available.

---

## Part 7 — Beacon Loss and Session Degradation

### Step 7.1 — Move the beacon out of range (or power it off)

Wait for the grace period (default 30 seconds).

### Step 7.2 — Watch Android health degrade

```
GET http://[ANDROID_IP]:8080/health
```

**Within grace period (~15 seconds after loss):**
```json
{
  "insurance": {
    "sessionState": "ASSOCIATION_DEGRADED",
    "beaconDetected": false
  }
}
```

**After grace period (~30 seconds):**
```json
{
  "insurance": {
    "sessionState": "WAITING_FOR_VEHICLE",
    "sessionActive": false,
    "sessionId": null
  }
}
```

The mode stays `INSURANCE` — it does not revert to HOTEL.

### Step 7.3 — Verify backend received the loss event

```
GET http://[PC_IP]:5000/api/insurance/trips/%2B447700900000/latest
```
```json
{
  "vehicleAssociation": {
    "status": "LOST",
    "confidence": 10
  },
  "sessionState": "SUSPENDED"
}
```

---

## Part 8 — Re-association After Beacon Loss

### Step 8.1 — Bring the beacon back into range

Power the beacon back on or move it within range.

### Step 8.2 — Verify re-association

The Android app should cycle through `CANDIDATE_VEHICLE_DETECTED` → `VEHICLE_ASSOCIATED` again. A **new** `sessionId` will be generated (the previous session ended).

```
GET http://[ANDROID_IP]:8080/health
```
```json
{
  "insurance": {
    "sessionState": "VEHICLE_ASSOCIATED",
    "sessionActive": true,
    "sessionId": "b7c2..."
  }
}
```

The new `sessionId` prefix (`b7c2`) is different from the previous one (`a3f4`).

---

## Part 9 — Backend Unavailable (Offline Resilience)

### Step 9.1 — Stop the backend

Press `Ctrl+C` in the backend terminal.

### Step 9.2 — Trigger a new event on Android

Move the beacon out of range and back in to trigger a new association event.

### Step 9.3 — Check Android health shows queue building

```
GET http://[ANDROID_IP]:8080/health
```
```json
{
  "insurance": {
    "backendReachability": "DEGRADED",
    "lastPublishStatus": "TIMEOUT",
    "pendingEvents": 1,
    "publisherState": "FAILED"
  }
}
```

After 3 failures:
```json
{
  "backendReachability": "UNREACHABLE"
}
```

The Android app must not crash. BLE scanning must continue.

### Step 9.4 — Restart the backend

```
npm start
```

### Step 9.5 — Verify queued events are delivered

Within ~30 seconds (retry backoff), the Android app retries. Check:
```
GET http://[ANDROID_IP]:8080/health
```
```json
{
  "insurance": {
    "backendReachability": "AVAILABLE",
    "lastPublishStatus": "SUCCESS",
    "pendingEvents": 0
  }
}
```

---

## Part 10 — GPS Unavailable (Session Must Survive)

### Step 10.1 — Disable location on the Android device

Go to **Settings > Location** and turn it off.

### Step 10.2 — Trigger a new event

Move the beacon out of range and back in.

### Step 10.3 — Verify Android health

```
GET http://[ANDROID_IP]:8080/health
```
```json
{
  "insurance": {
    "gpsAvailable": false,
    "gpsPermissionGranted": false,
    "speedAvailable": false,
    "speedSource": null,
    "sessionActive": true
  }
}
```

Session must remain active. `sessionActive: true` is the key assertion.

### Step 10.4 — Verify backend handles missing GPS

```
GET http://[PC_IP]:5000/api/insurance/trips/%2B447700900000/latest
```
```json
{
  "locationConfidence": {
    "source": "NONE",
    "confidence": 0
  },
  "observerState": {
    "currentSpeedMph": 65
  }
}
```

`source: "NONE"` is correct — backend falls back to its default speed of 65 mph when Android sends null. Vehicle association remains `CONFIRMED`.

Re-enable location on the device after this test.

---

## Part 11 — Event History Verification

### Step 11.1 — Run through a full lifecycle

Complete Parts 3–7 above (activate → associate → degrade → disconnect).

### Step 11.2 — Check the event timeline

```
GET http://[ANDROID_IP]:8080/health
```

Look at the `recentEvents` array in the insurance block:
```json
{
  "insurance": {
    "recentEvents": [
      { "event": "MODE_ACTIVATED",       "detail": "policy=POLI...", "timestamp": "2025-01-15T10:16:00.000Z" },
      { "event": "VEHICLE_CANDIDATE",    "detail": "rssi=-65",       "timestamp": "2025-01-15T10:16:30.000Z" },
      { "event": "ASSOCIATION_CONFIRMED","detail": "rssi=-63",       "timestamp": "2025-01-15T10:16:40.000Z" },
      { "event": "INITIAL_EVENT_SENT",   "detail": "session=a3f4...", "timestamp": "2025-01-15T10:16:40.000Z" },
      { "event": "BEACON_LOST",          "detail": "session=a3f4...", "timestamp": "2025-01-15T10:20:00.000Z" },
      { "event": "SESSION_ENDED",        "detail": "mode stopped",   "timestamp": "2025-01-15T10:20:01.000Z" }
    ]
  }
}
```

**Verify:**
- Events appear in chronological order
- No full phone numbers, policy IDs, or coordinates in any `detail` field
- `INITIAL_EVENT_SENT` appears exactly once per session

---

## Part 12 — Restart Recovery (No Duplicate Initial Events)

### Step 12.1 — Establish an active session

Complete Part 3 — confirm `sessionActive: true` and note the masked `sessionId`.

### Step 12.2 — Force-kill the Android app

On the device: **Settings > Apps > HotelBLEScanner > Force Stop**

### Step 12.3 — Relaunch the app

Tap the app icon or click Run in Android Studio.

### Step 12.4 — Reconnect WebSocket and bring beacon into range

```javascript
const ws = new WebSocket('ws://[ANDROID_IP]:8080');
ws.onmessage = e => console.log(JSON.parse(e.data));
```

### Step 12.5 — Verify no duplicate initial event on the backend

```
GET http://[PC_IP]:5000/api/insurance/trips/%2B447700900000/latest
```

Check `sessionEvents` in the response — there must be only **one** `SESSION_STARTED` entry, not two:
```json
{
  "sessionEvents": [
    { "type": "SESSION_STARTED", "timestamp": "..." }
  ]
}
```

Also check the Android health — the recovered `sessionId` prefix should match the one noted in Step 12.1.

---

## Part 13 — HOTEL Mode Isolation (Must Not Break)

### Step 13.1 — Restart the Android app without sending insuranceConfig

Force-stop and relaunch the app. Do not send any WebSocket message.

### Step 13.2 — Verify HOTEL mode is active

```
GET http://[ANDROID_IP]:8080/health
```
```json
{
  "status": "ok",
  "webClients": 0,
  "timestamp": 1234567890000
}
```

No `deviceMode` field and no `insurance` block — HOTEL mode is the default and produces no insurance output.

### Step 13.3 — Verify hotel beacons still work

Power on a hotel beacon (`ER26B00001`, `ER26B00002`, `ER26B00003`, or `ER26B00004`). Connect a WebSocket client and walk near the beacon. You should receive:
```json
{
  "beaconName": "HotelGate",
  "rssi": -55,
  "zone": "Hotel Entry Gate",
  "timestamp": 1234567890000
}
```

The insurance backend receives nothing during HOTEL mode.

### Step 13.4 — Verify BeaconConfigManager loaded version

Check the Android health endpoint:
```
GET http://[ANDROID_IP]:8080/health
```

If no remote config has been sent, the app uses the hardcoded default config. The logcat will show:
```
[CONFIG] Loaded default config (9 entries, 3 barriers)
```

If a remote config was previously sent and persisted, logcat shows:
```
[CONFIG] Loaded remote config v1 (N entries, N barriers)
```

To reset to defaults (e.g. after a bad config), clear app data on the device:
**Settings > Apps > HotelBLEScanner > Storage > Clear Data**

---

## Part 14 — Dynamic Beacon Config Update

This verifies that the app accepts a remote beacon config sent over WebSocket, persists it, and uses it for all subsequent BLE matching without requiring an app restart.

### Step 14.1 — Connect a WebSocket client

```javascript
const ws = new WebSocket('ws://[ANDROID_IP]:8080');
ws.onmessage = e => console.log('RECEIVED:', JSON.parse(e.data));
ws.onopen = () => console.log('Connected');
```

### Step 14.2 — Send a beacon config update

The message must contain a `"beacons"` array. Replace logical names and zones to match your deployment:

```javascript
ws.send(JSON.stringify({
  "version": "v1",
  "beacons": [
    {
      "matchType": "EXACT",
      "identifier": "ER26B00001",
      "logicalName": "HotelGate",
      "zone": "Hotel Entry Gate",
      "isBarrier": true
    },
    {
      "matchType": "EXACT",
      "identifier": "BCPro_212364",
      "logicalName": "HotelGate",
      "zone": "Hotel Entry Gate",
      "isBarrier": true
    },
    {
      "matchType": "EXACT",
      "identifier": "ER26B00002",
      "logicalName": "HotelKiosk",
      "zone": "Check-in Kiosk",
      "isBarrier": false
    }
  ]
}));
```

**Validation rules enforced by the app:**
- Each entry must have non-empty `matchType`, `identifier`, `logicalName`, `zone`
- At least one entry must have `"isBarrier": true`
- No two entries may share the same `logicalName` with different `zone` values
- If validation fails, the existing config is kept and logcat shows `[CONFIG] ERROR`

### Step 14.3 — Verify config was applied

Check logcat:
```
adb logcat -s "BeaconConfigManager"
```
Expected:
```
[CONFIG] Loaded remote config v1 (3 entries, 1 barriers)
```

If a PREFIX entry without `knownIdentifiers` was sent, you will also see:
```
[CONFIG] WARNING — broad BLE scan active (PREFIX entry without knownIdentifiers)
```

### Step 14.4 — Verify BLE matching uses the new config

Bring a beacon from the new config into range. The WebSocket client should receive events using the new `logicalName` and `zone` values.

Bring a beacon that is **not** in the new config into range — it must produce **no** WebSocket event (filtered by `isAllowedDevice()`).

### Step 14.5 — Verify config persists across restart

Force-stop and relaunch the app. Check logcat on startup:
```
[CONFIG] Loaded remote config v1 (3 entries, 1 barriers)
```

The app must not fall back to the default config after restart.

### Step 14.6 — Verify config update is deferred during active transport session

If a transport validation session is active when the config message arrives, the app defers the BLE scan restart until the session ends (`pendingConfigRestart = true`). The config is still applied immediately — only the scan restart is deferred. Verify in logcat:
```
[CONFIG] Config update deferred — active transport session in progress
```

---

## Part 15 — Backend Association Engine Verification

This verifies the backend's confidence scoring responds correctly to the evidence the Android app sends.

### Scenario A — Strong association (beacon + fresh biometric)

Send this directly to the backend to simulate what the Android app sends after biometric auth:

```
POST http://[PC_IP]:5000/api/insurance/trips/events
Content-Type: application/json

{
  "policyId": "POLICY-TEST-001",
  "phoneNumber": "+447700900000",
  "mode": "observer",
  "isInitialEvent": true,
  "auth": {
    "biometricVerified": true,
    "lastVerifiedAt": "[ISO timestamp within last 30 minutes]",
    "freshnessState": "FRESH"
  },
  "vehicleAssociation": {
    "beaconDetected": true,
    "beaconId": "CAR-BLE-001",
    "associationState": "VEHICLE_ASSOCIATED"
  },
  "location": {
    "lat": 51.5074,
    "lng": -0.1278,
    "source": "DEVICE_GPS_FALLBACK"
  },
  "currentSpeedMph": 35.0
}
```

**Expected:**
```json
{
  "userAssociation":    { "status": "CONFIRMED", "confidence": 92 },
  "vehicleAssociation": { "status": "CONFIRMED", "confidence": 90 },
  "triadScore": 85,
  "sessionState": "ACTIVE"
}
```

### Scenario B — Degraded association (beacon lost, biometric expired)

```json
{
  "policyId": "POLICY-TEST-001",
  "phoneNumber": "+447700900000",
  "mode": "observer",
  "isInitialEvent": false,
  "auth": {
    "biometricVerified": false,
    "freshnessState": "EXPIRED"
  },
  "vehicleAssociation": {
    "beaconDetected": false,
    "beaconId": "CAR-BLE-001",
    "associationState": "VEHICLE_DISCONNECTED"
  }
}
```

**Expected:**
```json
{
  "userAssociation":    { "status": "LOST",      "confidence": 20 },
  "vehicleAssociation": { "status": "LOST",      "confidence": 10 },
  "triadScore": 14,
  "sessionState": "SUSPENDED"
}
```

---

## Part 16 — Full End-to-End Checklist

Run through this checklist in order. Each row must pass before moving to the next.

| # | Test | Pass Condition |
|---|------|----------------|
| 1 | Backend starts | `GET /health` returns `status: OK` |
| 2 | Android starts | `GET :8080/health` returns `status: ok`, no insurance block |
| 3 | INSURANCE mode activated | `sessionState: WAITING_FOR_VEHICLE`, `sessionId: null` (auto on restart after first config) |
| 4 | Beacon in range | `sessionState: CANDIDATE_VEHICLE_DETECTED` |
| 5 | Association confirmed | `sessionState: VEHICLE_ASSOCIATED`, `sessionId` appears |
| 6 | Backend receives initial event | `GET /trips/.../latest` returns `eventAccepted: true`, `sessionState: ACTIVE` |
| 7 | Vehicle association confirmed on backend | `vehicleAssociation.status: CONFIRMED` |
| 8 | Biometric auth performed | `userAssociation.status` changes to `CONFIRMED` on backend |
| 9 | GPS available | `gpsAvailable: true`, `speedSource: GPS` in Android health |
| 10 | Beacon removed | `ASSOCIATION_DEGRADED` → `WAITING_FOR_VEHICLE` on Android |
| 11 | Backend reflects loss | `vehicleAssociation.status: LOST`, `sessionState: SUSPENDED` |
| 12 | Beacon restored | New `sessionId` generated, `VEHICLE_ASSOCIATED` again |
| 13 | Backend offline | `backendReachability: UNREACHABLE`, no crash, queue builds |
| 14 | Backend restored | Queue drains, `backendReachability: AVAILABLE` |
| 15 | GPS disabled | `sessionActive: true` despite `gpsAvailable: false` |
| 16 | App restart | Recovered `sessionId` matches, no duplicate initial event on backend |
| 17 | Event history | `recentEvents` in health shows correct timeline, no PII |
| 18 | HOTEL mode | Hotel beacon events broadcast normally, no insurance block in health |
| 19 | Remote beacon config sent | Logcat shows `[CONFIG] Loaded remote config vN`, new logical names used in WS events |
| 20 | Config validation — no barrier | App rejects config, keeps existing, logcat shows `[CONFIG] ERROR — no barrier beacons` |
| 21 | Config validation — zone collision | App rejects config, keeps existing, logcat shows `[CONFIG] ERROR — zone collision` |
| 22 | Config persists across restart | After force-stop, logcat shows `[CONFIG] Loaded remote config vN` (not default) |
| 23 | Config deferred during transport session | Logcat shows `[CONFIG] Config update deferred` when session active |

---

## Troubleshooting

**Android health endpoint not reachable from PC**
- Confirm both devices are on the same WiFi network
- Check Windows Firewall — allow inbound TCP on port 8080
- Confirm the IP shown on the Android app screen matches what you are using

**`sessionState` stays `WAITING_FOR_VEHICLE` after beacon is powered on**
- Confirm the `vehicleBeaconId` in the config exactly matches the BLE device name (case-sensitive)
- Use nRF Connect on Android to confirm the advertised name
- Check the Android logcat for `[VEH]` tag: `adb logcat -s "[VEH] VehicleAssociationController"`

**Backend returns 404 for `/trips/.../latest`**
- The initial event has not been received yet — wait for `sessionActive: true` on Android health
- Confirm `backendBaseUrl` in the config points to `http://[PC_IP]:5000` not `localhost`

**`userAssociation.status` stays `UNKNOWN` on backend**
- Biometric auth has not been performed on the Android device
- Perform fingerprint/PIN verification in the app and wait up to 60 seconds for the `AUTH_FRESHNESS_CHANGED` event

**Backend console shows CAMARA errors**
- This is expected in MOCK mode (`USE_MOCK_API=true` in `.env`)
- The backend falls back gracefully — `locationConfidence.source` will be `GPS_FALLBACK` or `NONE`
- This does not affect vehicle association scoring

**`backendReachability` stays `UNKNOWN` after association**
- The initial event may still be in the retry queue
- Check `pendingEvents` count in Android health
- Confirm the backend is running and reachable: `curl http://[PC_IP]:5000/health`

**`backendReachability` stays `UNKNOWN` after association**
- The initial event may still be in the retry queue
- Check `pendingEvents` count in Android health
- Confirm the backend is running and reachable: `curl http://[PC_IP]:5000/health`

**Remote beacon config not applied**
- Confirm the WebSocket message contains a `"beacons"` array (not `"beaconName"`)
- Check logcat for `[CONFIG] ERROR` — the config may have failed validation
- Common failures: missing `isBarrier: true` on any entry, or same `logicalName` with two different `zone` values
- If the app is in an active transport session, the BLE scan restart is deferred — the config is still applied

**Beacon not detected after config update**
- Confirm the `identifier` in the config exactly matches the BLE advertised name (case-sensitive)
- Use nRF Connect to verify the exact advertised name
- If using `matchType: PREFIX`, confirm the prefix matches the start of the device name
- Check logcat for `[MATCH]` tag to see how the device name is being resolved

**Logcat commands for debugging**
```
adb logcat -s "[INS] SessionManager"
adb logcat -s "[INS] Publisher"
adb logcat -s "[VEH] VehicleAssociationController"
adb logcat -s "[GPS] InsuranceLocationProvider"
adb logcat -s "[INS] BackendMonitor"
adb logcat -s "BeaconConfigManager"
```
