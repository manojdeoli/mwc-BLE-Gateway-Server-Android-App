# Quick Start Guide

## Android Application Created Successfully! ✅

Location: `C:\My Work\OneDrive_1_15-1-2026\BEL-Scanner\HotelBLEScanner`

## What's Included:

### 1. **MainActivity.java**
   - Displays Mobile IP Address prominently at the top
   - BLE scanning with real-time status updates
   - Start/Stop buttons for scanning control
   - Permission handling

### 2. **GatewayServer.java**
   - WebSocket server on port 8080
   - Broadcasts BLE events to connected web clients
   - Health check endpoint
   - Device filtering and zone mapping

### 3. **UI Layout**
   - IP Address display (blue background, bold text)
   - Status text showing current beacon
   - Start/Stop scanning buttons

## Next Steps:

### Step 1: Open in Android Studio
```
1. Launch Android Studio
2. File > Open
3. Navigate to: C:\My Work\OneDrive_1_15-1-2026\BEL-Scanner\HotelBLEScanner
4. Click OK
5. Wait for Gradle sync
```

### Step 2: Build & Run
```
1. Connect Android device via USB
2. Enable USB Debugging on device
3. Click Run button (green triangle)
4. Select your device
5. App will install and launch
```

### Step 3: Use the App
```
1. Grant all permissions when prompted
2. Note the IP address shown (e.g., http://192.168.1.100:8080)
3. Click "Start Scanning"
4. App will scan for beacons and broadcast events
```

### Step 4: Connect Web App
```
1. Open: C:\My Work\OneDrive_1_15-1-2026\HotelMDU-Aduna-Video-Background
2. Update .env file:
   REACT_APP_GATEWAY_URL=http://192.168.1.100:8080
   (Use the IP from your Android app)
3. Start web app: npm start
4. Web app will receive BLE events from Android
```

## Key Features:

✅ **IP Display**: Shows "Server: http://[YOUR_IP]:8080" at the top
✅ **Real-time Status**: Updates with each beacon detection
✅ **WebSocket Server**: Broadcasts to multiple web clients
✅ **Beacon Filtering**: Only scans for hotel-specific beacons
✅ **Zone Mapping**: Maps device names to zones (Gate, Kiosk, Elevator, Room)

## Communication Flow:

```
BLE Beacon (e.g., ER26B00001)
    ↓
Android App (Scans & Detects)
    ↓
WebSocket Broadcast on port 8080
    ↓
Web Application (Receives via gatewayClient.js)
    ↓
RSSI Processing (rssiProcessor.js)
    ↓
UI Update (Shows proximity)
```

## Troubleshooting:

**Can't see IP address?**
- Ensure WiFi is connected
- Restart the app

**Web app not receiving events?**
- Ensure both devices on same WiFi network
- Check firewall settings
- Verify IP address in web app matches Android app

**No beacons detected?**
- Ensure beacons are powered on
- Move closer to beacons
- Check beacon names match filter list

## Project Structure:
```
HotelBLEScanner/
├── app/
│   ├── src/main/
│   │   ├── java/com/hotel/blescanner/
│   │   │   ├── MainActivity.java ✅
│   │   │   └── GatewayServer.java ✅
│   │   ├── res/layout/
│   │   │   └── activity_main.xml ✅
│   │   └── AndroidManifest.xml ✅
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

Ready to build! 🚀
