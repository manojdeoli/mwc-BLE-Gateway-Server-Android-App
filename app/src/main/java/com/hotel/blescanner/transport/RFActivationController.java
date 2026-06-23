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
 * Scan mode is set per-mode by BLEScanService.applyCurrentMode():
 *   HOTEL     → SCAN_MODE_LOW_LATENCY  (original behaviour, unchanged)
 *   TRANSPORT → SCAN_MODE_BALANCED     (battery optimisation — Fix A)
 *
 * When scan mode changes mid-session, ensureScanRunning() restarts the scan
 * with the new settings automatically.
 *
 * Thread safety: scanRunning and pendingScanMode are volatile.
 */
public class RFActivationController {

    private static final String TAG = "[RF] RFActivationController";

    private final BluetoothLeScanner bleScanner;
    private final List<ScanFilter>   filters;
    private final ScanCallback       callback;

    private volatile boolean scanRunning         = false;
    private volatile boolean rfDetectionRequired = false;
    private volatile int     currentScanMode     = ScanSettings.SCAN_MODE_LOW_LATENCY;

    public RFActivationController(BluetoothLeScanner bleScanner,
                                  List<ScanFilter>   filters,
                                  ScanSettings       ignoredSettings,  // mode set dynamically
                                  ScanCallback       callback) {
        this.bleScanner = bleScanner;
        this.filters    = filters;
        this.callback   = callback;
    }

    // -------------------------------------------------------------------------
    // RF flag
    // -------------------------------------------------------------------------

    public boolean isRfDetectionRequired() { return rfDetectionRequired; }
    public void setRfDetectionRequired(boolean required) { this.rfDetectionRequired = required; }

    // -------------------------------------------------------------------------
    // Scan mode — set by BLEScanService per operating mode (Fix A)
    // -------------------------------------------------------------------------

    /**
     * Updates the scan mode. If scan is currently running and the mode changed,
     * restarts the scan with the new settings automatically.
     */
    public void setScanMode(int scanMode) {
        if (scanMode == currentScanMode) return;
        Log.d(TAG, "Scan mode changing: " + currentScanMode + " → " + scanMode);
        currentScanMode = scanMode;
        if (scanRunning) {
            // Restart scan with new settings
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
            case ScanSettings.SCAN_MODE_LOW_LATENCY:  return "LOW_LATENCY";
            case ScanSettings.SCAN_MODE_BALANCED:     return "BALANCED";
            case ScanSettings.SCAN_MODE_LOW_POWER:    return "LOW_POWER";
            default:                                  return "UNKNOWN(" + mode + ")";
        }
    }
}
