package com.hotel.blescanner.transport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.hotel.blescanner.config.TransportConfig;
import java.util.Arrays;
import java.util.List;

/**
 * Monitors WiFi connectivity to detect when the device is at a known
 * transport station. Station proximity is the PRIMARY trigger for:
 *
 *   1. BLE scan activation in TRANSPORT mode (Gap 2.1)
 *      → sets RFActivationController.userNearStation = true/false
 *      → BLEScanService.applyCurrentMode() starts/stops scan based on this
 *
 *   2. Biometric freshness check (Gap 2.3)
 *      → triggered when user first arrives at station
 *      → NOT triggered on every advisory or beacon detection
 *      → optional pre-journey prompt — non-blocking
 *
 * Station detection method:
 *   Compares the connected WiFi SSID against a configurable list of known
 *   station SSIDs from TransportConfig. An optional RSSI threshold filters
 *   out weak far-range leakage.
 *
 * Lifecycle:
 *   start() → registers WIFI_STATE_CHANGED + NETWORK_STATE_CHANGED receivers
 *   stop()  → unregisters receivers, resets nearStation state
 *
 * All callbacks are delivered on the broadcast receiver thread.
 * BLEScanService.onStationProximityChanged() calls applyCurrentMode() which
 * is thread-safe (RFActivationController uses volatile flags).
 *
 * HOTEL mode isolation:
 *   This monitor is only created and started when ValidationController
 *   commits an EXIT-stage advisory (TRANSPORT mode activation).
 *   It is never created in HOTEL mode — zero impact on hotel functionality.
 */
public class NetworkProximityMonitor {

    private static final String TAG = "[NET] NetworkProximityMonitor";

    /**
     * Callback delivered to BLEScanService when station proximity changes.
     * Both methods are called on the broadcast receiver thread — implementations
     * must be thread-safe.
     */
    public interface StationProximityListener {
        /** User has arrived at a known transport station (WiFi match + RSSI check). */
        void onNearStationDetected(String ssid);

        /** User has left the station or WiFi disconnected. */
        void onLeftStation();
    }

    private final Context                  context;
    private final TransportConfig          config;
    private final StationProximityListener listener;
    private final BiometricManager         biometricManager;
    private final BiometricCallback        biometricCallback;

    private volatile boolean registered    = false;
    private volatile boolean nearStation   = false;

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            evaluateStationProximity();
        }
    };

    public NetworkProximityMonitor(Context context,
                                   TransportConfig config,
                                   StationProximityListener listener,
                                   BiometricManager biometricManager,
                                   BiometricCallback biometricCallback) {
        this.context          = context;
        this.config           = config;
        this.listener         = listener;
        this.biometricManager = biometricManager;
        this.biometricCallback = biometricCallback;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts monitoring. Registers WiFi broadcast receivers and performs
     * an immediate evaluation so the state is correct on start.
     */
    public void start() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(wifiReceiver, filter);
        registered = true;
        Log.d(TAG, "Started — monitoring for station SSIDs: "
            + Arrays.toString(config.getStationSsids()));
        // Evaluate immediately — device may already be at station when monitor starts
        evaluateStationProximity();
    }

    /**
     * Stops monitoring and resets nearStation state to false.
     * Always call this before service destroy to avoid leaked receivers.
     */
    public void stop() {
        if (!registered) return;
        try {
            context.unregisterReceiver(wifiReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering WiFi receiver", e);
        }
        registered  = false;
        nearStation = false;
        Log.d(TAG, "Stopped");
    }

    public boolean isNearStation() { return nearStation; }

    // -------------------------------------------------------------------------
    // Private — core evaluation
    // -------------------------------------------------------------------------

    /**
     * Called on every WiFi state change event.
     * Checks whether the current connected SSID is in the configured station list
     * and whether the signal strength meets the minimum threshold.
     *
     * Transitions:
     *   not-near → near  : calls listener.onNearStationDetected() + biometric check
     *   near → not-near  : calls listener.onLeftStation()
     *   no change        : silent
     */
    private void evaluateStationProximity() {
        String[] stationSsids = config.getStationSsids();
        if (stationSsids.length == 0) {
            // No station SSIDs configured — cannot detect proximity
            // This is expected in Hotel-only deployments
            Log.d(TAG, "No station SSIDs configured — proximity detection inactive");
            return;
        }

        WifiInfo wifiInfo = getCurrentWifiInfo();
        if (wifiInfo == null) {
            transitionToNotNear();
            return;
        }

        String connectedSsid = sanitiseSsid(wifiInfo.getSSID());
        int    rssi          = wifiInfo.getRssi();

        boolean ssidMatch  = isSsidMatch(connectedSsid, stationSsids);
        boolean rssiOk     = rssi >= config.getNearStationRssiThreshold();

        Log.d(TAG, "WiFi eval: ssid=" + connectedSsid + " rssi=" + rssi
            + " ssidMatch=" + ssidMatch + " rssiOk=" + rssiOk);

        if (ssidMatch && rssiOk) {
            transitionToNear(connectedSsid);
        } else {
            transitionToNotNear();
        }
    }

    private void transitionToNear(String ssid) {
        if (nearStation) return;   // already near — no repeat callback
        nearStation = true;
        Log.d(TAG, "At station: " + ssid);
        listener.onNearStationDetected(ssid);

        // Gap 2.3: biometric freshness check triggered at station arrival,
        // NOT at advisory commit and NOT at barrier.
        checkBiometricFreshness();
    }

    private void transitionToNotNear() {
        if (!nearStation) return;  // already away — no repeat callback
        nearStation = false;
        Log.d(TAG, "Left station");
        listener.onLeftStation();
    }

    /**
     * Gap 2.3: optional pre-journey biometric freshness check.
     * Triggered once when user arrives at station — non-blocking.
     * If biometric is stale, the prompt is shown as a UX courtesy.
     * Does NOT block BLE scanning or session lifecycle.
     * Does NOT fire at the barrier.
     */
    private void checkBiometricFreshness() {
        if (biometricManager == null) return;
        boolean fresh = biometricManager.isBiometricFresh(config.getBiometricMaxAgeMs());
        Log.d(TAG, "[BIOMETRIC] Station arrival freshness check: fresh=" + fresh);
        if (!fresh) {
            BiometricCallback cb = biometricCallback;
            if (cb != null) {
                Log.d(TAG, "[BIOMETRIC] Stale — requesting optional pre-journey check");
                cb.onBiometricRequired();
            } else {
                Log.d(TAG, "[BIOMETRIC] Stale — Activity not in foreground, deferring");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private WifiInfo getCurrentWifiInfo() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getSSID() == null) return null;
            // "<unknown ssid>" means not connected
            if ("<unknown ssid>".equalsIgnoreCase(info.getSSID())) return null;
            return info;
        } catch (Exception e) {
            Log.w(TAG, "Could not read WiFi info", e);
            return null;
        }
    }

    /**
     * Strips surrounding quotes Android adds to SSIDs: "\"StationWiFi\"" → "StationWiFi"
     */
    private static String sanitiseSsid(String ssid) {
        if (ssid == null) return "";
        return ssid.replace("\"", "").trim();
    }

    private static boolean isSsidMatch(String connectedSsid, String[] stationSsids) {
        if (connectedSsid == null || connectedSsid.isEmpty()) return false;
        for (String stationSsid : stationSsids) {
            if (connectedSsid.equalsIgnoreCase(stationSsid.trim())) return true;
        }
        return false;
    }
}
