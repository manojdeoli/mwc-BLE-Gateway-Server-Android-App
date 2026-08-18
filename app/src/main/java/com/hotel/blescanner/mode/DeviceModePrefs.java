package com.hotel.blescanner.mode;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the last mode requested by a connecting application via WebSocket.
 *
 * Rules:
 *   - Insurance app sends {"type":"insuranceConfig",...} → saves INSURANCE
 *   - Hotel app connects (subscribe / beacon config / advisory) → saves HOTEL
 *
 * Used by BLEScanService at startup to auto-activate the correct mode
 * without any manual step after the first configuration.
 */
public class DeviceModePrefs {

    private static final String PREFS_NAME = "device_mode_prefs";
    private static final String KEY_MODE   = "last_requested_mode";

    private final SharedPreferences prefs;

    public DeviceModePrefs(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveMode(DeviceMode mode) {
        if (mode != null) prefs.edit().putString(KEY_MODE, mode.name()).apply();
    }

    /** Returns the last saved mode, or HOTEL if nothing has been saved yet. */
    public DeviceMode getLastMode() {
        String saved = prefs.getString(KEY_MODE, DeviceMode.HOTEL.name());
        try {
            return DeviceMode.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return DeviceMode.HOTEL;
        }
    }
}
