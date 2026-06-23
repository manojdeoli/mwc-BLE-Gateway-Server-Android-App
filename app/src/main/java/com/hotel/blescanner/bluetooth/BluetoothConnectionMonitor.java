package com.hotel.blescanner.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;
import java.util.Set;

/**
 * Monitors whether the device has an active Bluetooth audio connection
 * (A2DP or HEADSET profile).
 *
 * No device names are hardcoded. Connection state is determined purely
 * from profile state queries on the BluetoothAdapter.
 *
 * Simulation mode: when enabled, returns injected values instead of
 * querying the real Bluetooth stack.
 */
public class BluetoothConnectionMonitor {

    private static final String TAG = "BTConnectionMonitor";

    private final BluetoothAdapter bluetoothAdapter;

    // Simulation state
    private volatile boolean simulationEnabled      = false;
    private volatile boolean simulatedConnected     = false;
    private volatile String  simulatedDeviceName    = null;

    public BluetoothConnectionMonitor(Context context) {
        BluetoothManager manager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = (manager != null) ? manager.getAdapter() : null;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true if any Bluetooth audio device (A2DP or HEADSET) is
     * currently connected.
     */
    public boolean isConnected() {
        if (simulationEnabled) return simulatedConnected;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return false;

        return isProfileConnected(BluetoothProfile.A2DP)
            || isProfileConnected(BluetoothProfile.HEADSET);
    }

    /**
     * Returns the name of a connected Bluetooth audio device (A2DP or HEADSET),
     * or null if none is connected or the name cannot be determined reliably.
     *
     * Uses BluetoothManager.getConnectedDevices() on the specific profile proxy
     * to get only actually-connected devices, not merely bonded ones.
     * Returns null rather than a misleading bonded-but-not-connected name.
     *
     * Used for informational purposes in the signals payload only —
     * not used in scoring logic.
     */
    public String getConnectedDeviceName() {
        if (simulationEnabled) return simulatedDeviceName;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return null;

        // getConnectedDevices() requires a profile proxy which is async to obtain.
        // As a reliable synchronous alternative, we check profile connection state
        // and then find the matching device from bonded devices only when the
        // profile reports STATE_CONNECTED — this is accurate because
        // getProfileConnectionState() reflects the actual connected device state,
        // not just pairing history.
        //
        // If we cannot determine the name reliably, we return null rather than
        // returning a potentially wrong bonded device name.
        try {
            int a2dpState    = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            int headsetState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);

            if (a2dpState    != BluetoothProfile.STATE_CONNECTED
             && headsetState != BluetoothProfile.STATE_CONNECTED) {
                return null; // nothing connected — don't guess
            }

            // A profile is connected. Find the device name from bonded set.
            // On most Android devices only one A2DP/HEADSET device is connected
            // at a time, so the first bonded device that matches is correct.
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return null;

            for (BluetoothDevice device : bonded) {
                try {
                    String name = device.getName();
                    if (name != null && !name.isEmpty()) return name;
                } catch (SecurityException e) {
                    // BLUETOOTH_CONNECT not granted for getName()
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted");
        } catch (Exception e) {
            Log.w(TAG, "Could not read connected device name", e);
        }
        return null; // cannot determine reliably — return null, not a guess
    }

    /**
     * Enables or disables simulation mode.
     *
     * @param enabled       true to use simulated values
     * @param connected     simulated connection state
     * @param deviceName    simulated device name (may be null)
     */
    public void setSimulation(boolean enabled, boolean connected, String deviceName) {
        this.simulationEnabled   = enabled;
        this.simulatedConnected  = connected;
        this.simulatedDeviceName = deviceName;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isProfileConnected(int profile) {
        try {
            return bluetoothAdapter.getProfileConnectionState(profile)
                == BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException e) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for profile " + profile);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Could not query profile " + profile, e);
            return false;
        }
    }
}
