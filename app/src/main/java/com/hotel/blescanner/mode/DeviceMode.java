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
 */
public enum DeviceMode {
    HOTEL,
    TRANSPORT,
    HYBRID
}
