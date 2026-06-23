package com.hotel.blescanner.mode;

import android.util.Log;

/**
 * Holds and manages the current {@link DeviceMode}.
 *
 * Default mode is always HOTEL — existing behaviour is preserved until an
 * explicit backend advisory or manual override changes the mode.
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

    public void setMode(DeviceMode mode) {
        if (mode == null) return;
        Log.d(TAG, "Mode transition: " + currentMode + " → " + mode);
        currentMode = mode;
    }

    public boolean isHotelMode() {
        return currentMode == DeviceMode.HOTEL;
    }

    public boolean isTransportMode() {
        return currentMode == DeviceMode.TRANSPORT;
    }

    public boolean isHybridMode() {
        return currentMode == DeviceMode.HYBRID;
    }
}
