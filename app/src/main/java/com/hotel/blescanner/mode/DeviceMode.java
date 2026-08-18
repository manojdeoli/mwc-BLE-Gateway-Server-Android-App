package com.hotel.blescanner.mode;

/**
 * Operating mode for the Device Agent.
 *
 * HOTEL     — default. Continuous BLE scanning, context engine always active.
 *             All existing Hotel/MDU behaviour is preserved unchanged in this mode.
 * TRANSPORT — on-demand BLE scanning, biometric validation, barrier interaction.
 *             Activated only via explicit backend advisory.
 * HYBRID    — dynamically switches between HOTEL and TRANSPORT based on
 *             backend advisory or proximity signals.
 * INSURANCE — UBI/connected motor insurance mode. Detects vehicle association
 *             via configured vehicle beacon, collects biometric freshness evidence,
 *             optional GPS fallback, and posts telemetry to the Insurance backend.
 *             Activated via insuranceConfig WebSocket message or SharedPreferences.
 *             NFC, barrier, station-WiFi and transport-validation logic do NOT run
 *             in this mode.
 */
public enum DeviceMode {
    HOTEL,
    TRANSPORT,
    HYBRID,
    INSURANCE
}
