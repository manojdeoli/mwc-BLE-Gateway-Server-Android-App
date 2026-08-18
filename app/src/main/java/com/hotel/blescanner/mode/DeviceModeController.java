package com.hotel.blescanner.mode;

import android.util.Log;

/**
 * Holds and manages the current {@link DeviceMode}.
 *
 * Default mode is always HOTEL — existing behaviour is preserved until an
 * explicit backend advisory or manual override changes the mode.
 *
 * Mode precedence:
 *   1. TRANSPORT advisory (from backend WebSocket) switches HOTEL ↔ TRANSPORT.
 *      It does NOT override INSURANCE mode — ValidationController checks
 *      isInsuranceMode() before applying a transport advisory.
 *   2. INSURANCE mode is set via insuranceConfig WebSocket message or config.
 *      A TRANSPORT advisory received while in INSURANCE mode is logged and ignored.
 *   3. HOTEL is the safe default and the revert target for all modes.
 *
 * Thread safety: currentMode is volatile — written on WebSocket/advisory thread,
 * read on scan callback thread and scheduler thread.
 */
public class DeviceModeController {

    private static final String TAG = "DeviceModeController";

    private volatile DeviceMode currentMode = DeviceMode.HOTEL;

    public DeviceMode getMode() {
        return currentMode;
    }

    /** Existing callers (ValidationController, BLEScanService) use this unchanged. */
    public void setMode(DeviceMode mode) {
        if (mode == null) return;
        Log.d(TAG, "Mode transition: " + currentMode + " → " + mode);
        currentMode = mode;
    }

    /**
     * Sets the device mode with an explicit reason for audit logging.
     * Preferred for INSURANCE mode activation/deactivation.
     *
     * @param mode   target mode
     * @param reason human-readable reason (logged only, never transmitted)
     */
    public void setDeviceMode(DeviceMode mode, String reason) {
        if (mode == null) return;
        Log.d(TAG, "Mode transition: " + currentMode + " → " + mode + " [" + reason + "]");
        currentMode = mode;
    }

    public boolean isHotelMode()     { return currentMode == DeviceMode.HOTEL; }
    public boolean isTransportMode() { return currentMode == DeviceMode.TRANSPORT; }
    public boolean isHybridMode()    { return currentMode == DeviceMode.HYBRID; }
    public boolean isInsuranceMode() { return currentMode == DeviceMode.INSURANCE; }
}
