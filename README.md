# Hotel BLE Scanner - Android Application

## Overview
Android application that scans for Bluetooth Low Energy (BLE) beacons and broadcasts events to web applications via WebSocket on port 8080.

## Features
- ✅ Displays Mobile IP Address (http://[IP]:8080)
- ✅ Scans for specific hotel beacons
- ✅ WebSocket server on port 8080
- ✅ Real-time BLE event broadcasting
- ✅ Compatible with HotelMDU web application

## Supported Beacons
- HotelGate (ER26B00001, BCPro_212364)
- HotelKiosk (ER26B00002)
- HotelElevator (ER26B00003)
- HotelRoom (ER26B00004)

## Setup Instructions

### 1. Open in Android Studio
```
File > Open > Select: C:\My Work\OneDrive_1_15-1-2026\BEL-Scanner\HotelBLEScanner
```

### 2. Build the Project
- Wait for Gradle sync to complete
- Click Build > Make Project

### 3. Run on Device
- Connect Android device via USB
- Enable Developer Options and USB Debugging
- Click Run > Run 'app'

### 4. Grant Permissions
When app starts, grant:
- Bluetooth permissions
- Location permissions
- Network permissions

### 5. Connect Web Application
1. Note the IP address shown on the app (e.g., http://192.168.1.100:8080)
2. Update web application's GATEWAY_URL to this IP
3. Start scanning on the Android app
4. Web app will receive BLE events via WebSocket

## Communication Protocol

### WebSocket Connection
```
ws://[MOBILE_IP]:8080/
```

### Message Format
```json
{
  "beaconName": "HotelGate",
  "rssi": -55,
  "zone": "Hotel Entry Gate",
  "timestamp": 1704297600000
}
```

### Health Check
```
GET http://[MOBILE_IP]:8080/health
```

## Web Application Integration

Update `gatewayClient.js`:
```javascript
const GATEWAY_URL = 'http://192.168.1.100:8080'; // Use mobile IP
```

## Troubleshooting

**Bluetooth not working:**
- Enable Bluetooth in Android settings
- Grant all permissions

**WebSocket not connecting:**
- Ensure mobile and PC are on same WiFi network
- Check firewall settings
- Verify IP address is correct

**No beacons detected:**
- Ensure beacons are powered on
- Check beacon names match the filter list
- Move closer to beacons

## Architecture
```
[BLE Beacons] 
    ↓ (Bluetooth)
[Android App - BLE Scanner]
    ↓ (WebSocket on port 8080)
[Web Application - React]
    ↓ (Display)
[User Interface]
```

## Requirements
- Android 8.0 (API 26) or higher
- Bluetooth Low Energy support
- WiFi connection
- Location permission (required for BLE scanning)
