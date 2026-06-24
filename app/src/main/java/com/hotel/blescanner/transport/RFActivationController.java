package com.hotel.blescanner.transport;

import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.util.Log;
import java.util.List;

/**
 * Owns the BLE scan lifecycle on behalf of BLEScanService.
 *
 * All scan state transitions flow through {@link #ensureScanRunning()} and
 * {@link #ensureScanStopped()}, which are idempotent — safe to call repeatedly.
 *
 * Scan mode per operating mode:
 *   HOTEL     → SCAN_MODE_LOW_LATENCY  (original behaviour — unchanged)
 *   TRANSPORT → SCAN_MODE_BALANCED     (battery optimisation)
 *
 * BLE activation model in TRANSPORT mode (Gap 2.1):
 *   PRIMARY trigger  : userNearStation=true (set by NetworkProximityMonitor)
 *   SECONDARY context: rfDetectionRequired=true (set by advisory — mode switch only)
 *
 *   BLEScanService.applyCurrentMode() uses userNearStation — NOT rfDetectionRequired —
 *   to decide whether to call ensureScanRunning() in TRANSPORT mode.
 *   rfDetectionRequired controls whether TRANSPORT mode is active at all,
 *   but does not directly start or stop the scan.
 *
 * Thread safety: all volatile fields — written on network/advisory thread,
 * read on applyCurrentMode() caller thread.
 */
public class RFActivationController {

    private static final String TAG = "[RF] RFActivationController";

    private final BluetoothLeScanner bleScanner;
    private final List<ScanFilter>   filters;
    private final ScanCallback       callback;

    private volatile boolean scanRunning          = false;
    private volatile boolean rfDetectionRequired  = false;

    /**
     * Gap 2.1: PRIMARY BLE activation trigger in TRANSPORT mode.
     * Set to true by NetworkProximityMonitor when station WiFi is detected.
     * Set to false when user leaves station network.
     * BLE scanning in TRANSPORT mode is on only when this is true.
     */
    private volatile boolean userNearStation      = false;

    private volatile int     currentScanMode      = ScanSettings.SCAN_MODE_LOW_LATENCY;

    public RFActivationController(BluetoothLeScanner bleScanner,
                                  List<ScanFilter>   filters,
                                  ScanSettings       ignoredSettings,
                                  ScanCallback       callback) {
        this.bleScanner = bleScanner;
        this.filters    = filters;
        this.callback   = callback;
    }

    // -------------------------------------------------------------------------
    // RF flag — advisory-driven mode activation (NOT the BLE scan trigger)
    // -------------------------------------------------------------------------

    public boolean isRfDetectionRequired()              { return rfDetectionRequired; }
    public void    setRfDetectionRequired(boolean req)  { this.rfDetectionRequired = req; }

    // -------------------------------------------------------------------------
    // Network proximity — PRIMARY BLE trigger (Gap 2.1)
    // -------------------------------------------------------------------------

    /**
     * Returns true when the device is at a known transport station
     * as determined by NetworkProximityMonitor (WiFi SSID/subnet).
     */
    public boolean isUserNearStation()                  { return userNearStation; }

    /**
     * Called by BLEScanService when NetworkProximityMonitor reports
     * station entry or exit. This is the primary BLE scan trigger.
     */
    public void setUserNearStation(boolean nearStation) {
        Log.d(TAG, "userNearStation: " + this.userNearStation + " → " + nearStation);
        this.userNearStation = nearStation;
    }

    // -------------------------------------------------------------------------
    // Scan mode — set by BLEScanService per operating mode
    // -------------------------------------------------------------------------

    public void setScanMode(int scanMode) {
        if (scanMode == currentScanMode) return;
        Log.d(TAG, "Scan mode: " + scanModeName(currentScanMode) + " → " + scanModeName(scanMode));
        currentScanMode = scanMode;
        if (scanRunning) {
            stopScanInternal();
            startScanInternal();
        }
    }

    // -------------------------------------------------------------------------
    // Idempotent lifecycle control
    // -------------------------------------------------------------------------

    /** Starts BLE scanning if not already running. Safe to call multiple times. */
    public void ensureScanRunning() {
        if (scanRunning) return;
        startScanInternal();
    }

    /** Stops BLE scanning if currently running. Safe to call multiple times. */
    public void ensureScanStopped() {
        if (!scanRunning) return;
        stopScanInternal();
    }

    public boolean isScanRunning() { return scanRunning; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void startScanInternal() {
        try {
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(currentScanMode)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build();
            bleScanner.startScan(filters, settings, callback);
            scanRunning = true;
            Log.d(TAG, "BLE scan started [mode=" + scanModeName(currentScanMode) + "]");
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied — cannot start scan", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start scan", e);
        }
    }

    private void stopScanInternal() {
        try {
            bleScanner.stopScan(callback);
            scanRunning = false;
            Log.d(TAG, "BLE scan stopped");
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied — cannot stop scan", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop scan", e);
        }
    }

    private static String scanModeName(int mode) {
        switch (mode) {
            case ScanSettings.SCAN_MODE_LOW_LATENCY: return "LOW_LATENCY";
            case ScanSettings.SCAN_MODE_BALANCED:    return "BALANCED";
            case ScanSettings.SCAN_MODE_LOW_POWER:   return "LOW_POWER";
            default:                                 return "UNKNOWN(" + mode + ")";
        }
    }
}
