package com.hotel.blescanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.hotel.blescanner.bluetooth.BluetoothConnectionMonitor;
import com.hotel.blescanner.config.TransportConfig;
import com.hotel.blescanner.context.ContextBuilder;
import com.hotel.blescanner.context.ContextConfig;
import com.hotel.blescanner.context.ContextEvent;
import com.hotel.blescanner.context.ScoringConfig;
import com.hotel.blescanner.mode.DeviceModeController;
import com.hotel.blescanner.motion.MotionAnalyzer;
import com.hotel.blescanner.motion.MotionState;
import com.hotel.blescanner.transport.BiometricCallback;
import com.hotel.blescanner.transport.BiometricManager;
import com.hotel.blescanner.transport.NetworkProximityMonitor;
import com.hotel.blescanner.transport.RFActivationController;
import com.hotel.blescanner.transport.ValidationController;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BLEScanService extends Service {

    private static final String TAG             = "BLEScanService";
    private static final String T_MODE          = "[MODE]";
    private static final String T_BIO           = "[BIOMETRIC]";
    private static final int    NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID      = "BLEScanChannel";
    private static final String DEMO_SUBSCRIPTION_ID = "hotel-demo-subscription";

    // Core BLE + server
    private BluetoothLeScanner    bleScanner;
    private GatewayServer         gatewayServer;
    private RFActivationController rfActivation;

    // Transport controllers
    private ValidationController   validationController;
    private BiometricManager        biometricManager;
    private TransportConfig         transportConfig;

    /**
     * Gap 2.1: NetworkProximityMonitor — PRIMARY BLE trigger in TRANSPORT mode.
     * Created when ValidationController commits an EXIT advisory.
     * Never created in HOTEL mode — zero HOTEL impact.
     */
    private NetworkProximityMonitor networkProximityMonitor;

    // Dedup maps — unchanged
    private final Map<String, Long>    lastSeenBeacons = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastRssiValues  = new ConcurrentHashMap<>();

    // Mode controller — default = HOTEL
    private final DeviceModeController modeController = new DeviceModeController();

    // Context layer — unchanged
    private MotionAnalyzer             motionAnalyzer;
    private BluetoothConnectionMonitor bluetoothMonitor;
    private ContextBuilder             contextBuilder;
    private ScheduledExecutorService   contextScheduler;

    // -------------------------------------------------------------------------
    // Static holders — allow MainActivity to interact without binding
    // -------------------------------------------------------------------------

    private static volatile BiometricCallback    biometricCallbackRef      = null;
    private static volatile ValidationController activeValidationController = null;
    private static volatile BiometricManager     activeBiometricManager    = null;

    public static void setBiometricCallbackRef(BiometricCallback callback) {
        biometricCallbackRef = callback;
        if (activeValidationController != null) {
            activeValidationController.setBiometricCallback(callback);
        }
    }

    /**
     * Called by MainActivity after successful pre-journey BiometricPrompt.
     * Records auth timestamp in BiometricManager without requiring service binding.
     * Biometric is a pre-journey check only — not a barrier step.
     */
    public static void recordBiometricAuthTime() {
        if (activeBiometricManager != null) {
            activeBiometricManager.recordAuthTime();
        }
    }

    // -------------------------------------------------------------------------
    // Broadcast receivers
    // -------------------------------------------------------------------------

    /** Simulation toggles — unchanged */
    private final BroadcastReceiver simulationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean simEnabled   = intent.getBooleanExtra("simEnabled",   false);
            boolean simBluetooth = intent.getBooleanExtra("simBluetooth", false);
            boolean simVehicle   = intent.getBooleanExtra("simVehicle",   false);
            if (motionAnalyzer != null) {
                MotionState mo = simVehicle ? MotionState.VEHICLE : MotionState.STILL;
                motionAnalyzer.setSimulation(simEnabled && simVehicle, mo, simVehicle ? 50f : 0f);
            }
            if (bluetoothMonitor != null) {
                bluetoothMonitor.setSimulation(simEnabled && simBluetooth, simBluetooth, "Simulated Device");
            }
        }
    };

    /**
     * Phase 2 / Gap 2.3: receives BIOMETRIC_SUCCESS from MainActivity after the
     * optional pre-journey biometric prompt completes.
     *
     * This is NOT a barrier validation completion — it records auth time only.
     * ValidationController.onBiometricSuccess() does nothing barrier-related.
     */
    private final BroadcastReceiver biometricSuccessReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, T_BIO + " Pre-journey biometric completed");
            if (validationController != null) {
                validationController.onBiometricSuccess();
            }
        }
    };

    /** NFC tag read — unchanged path, forwarded to ValidationController.onNfcSuccess() */
    private final BroadcastReceiver nfcTagReadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String tagId     = intent.getStringExtra("tagId");
            String journeyId = intent.getStringExtra("journeyId");
            Log.d(TAG, "[NFC] Tag read: tagId=" + tagId + " journey=" + journeyId);
            if (validationController != null) {
                validationController.onNfcSuccess(
                    tagId     != null ? tagId     : "unknown",
                    journeyId != null ? journeyId : "unknown");
            }
        }
    };

    // -------------------------------------------------------------------------
    // Scan callback — HOTEL path completely unchanged.
    // Barrier proximity check appended AFTER all existing broadcasts.
    // -------------------------------------------------------------------------

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            try {
                String deviceName = result.getDevice().getName();
                int    rssi       = result.getRssi();

                if (deviceName == null || deviceName.isEmpty()) return;

                long    now      = System.currentTimeMillis();
                Long    lastSeen = lastSeenBeacons.get(deviceName);
                Integer lastRssi = lastRssiValues.get(deviceName);

                boolean shouldBroadcast = lastSeen == null || lastRssi == null ||
                                         lastRssi != rssi  || (now - lastSeen) > 200;

                if (shouldBroadcast) {
                    lastSeenBeacons.put(deviceName, now);
                    lastRssiValues.put(deviceName, rssi);

                    // Context layer notify — unchanged
                    if (contextBuilder != null) contextBuilder.notifyBleBeaconSeen();

                    String mappedName = mapDeviceToDisplayName(deviceName);

                    // LocalBroadcast to UI — unchanged
                    try {
                        Intent bi = new Intent("BEACON_UPDATE");
                        bi.putExtra("beaconName", mappedName);
                        bi.putExtra("rssi", rssi);
                        LocalBroadcastManager.getInstance(BLEScanService.this).sendBroadcast(bi);
                    } catch (Exception e) {
                        Log.e(TAG, "Broadcast error", e);
                    }

                    // WebSocket broadcast — unchanged
                    if (gatewayServer != null) gatewayServer.broadcastBLEEvent(deviceName, rssi);

                    // Barrier proximity — TRANSPORT mode only, after all existing logic
                    if (validationController != null) {
                        validationController.onBarrierProximity(mappedName, rssi);
                    }

                    broadcastTransportDebugState();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied", e);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(simulationReceiver,       new IntentFilter("SIMULATION_UPDATE"));
        lbm.registerReceiver(biometricSuccessReceiver, new IntentFilter("BIOMETRIC_SUCCESS"));
        lbm.registerReceiver(nfcTagReadReceiver,       new IntentFilter("NFC_TAG_READ"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification("Scanning for beacons..."));
        Log.d(TAG, T_MODE + " Service starting in mode: " + modeController.getMode());
        startScanning();
        return START_STICKY;
    }

    private void startScanning() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ba = bm.getAdapter();

        if (ba == null || !ba.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled"); stopSelf(); return;
        }
        bleScanner = ba.getBluetoothLeScanner();
        if (bleScanner == null) {
            Log.e(TAG, "BLE Scanner not available"); stopSelf(); return;
        }

        try {
            gatewayServer = new GatewayServer(DEMO_SUBSCRIPTION_ID);
            gatewayServer.start();

            // ----------------------------------------------------------------
            // Context detection layer — completely unchanged
            // ----------------------------------------------------------------
            ContextConfig contextConfig = new ContextConfig(this);
            motionAnalyzer   = new MotionAnalyzer(this, contextConfig);
            bluetoothMonitor = new BluetoothConnectionMonitor(this);
            contextBuilder   = new ContextBuilder(motionAnalyzer, bluetoothMonitor, contextConfig);
            motionAnalyzer.start();

            contextScheduler = Executors.newSingleThreadScheduledExecutor();
            contextScheduler.scheduleAtFixedRate(() -> {
                try {
                    ContextEvent event = contextBuilder.buildContext();
                    gatewayServer.broadcastContextEvent(event);
                    Intent ui = new Intent("CONTEXT_UPDATE");
                    ui.putExtra("mode",       event.data.mode);
                    ui.putExtra("confidence", event.data.confidence);
                    ui.putExtra("motion",     event.data.signals.motion);
                    ui.putExtra("speed",      event.data.signals.speed);
                    LocalBroadcastManager.getInstance(BLEScanService.this).sendBroadcast(ui);
                } catch (Exception e) {
                    Log.e(TAG, "Context evaluation error", e);
                }
            }, ScoringConfig.CONTEXT_INTERVAL_MS, ScoringConfig.CONTEXT_INTERVAL_MS, TimeUnit.MILLISECONDS);

            // ----------------------------------------------------------------
            // Transport layer init
            // ----------------------------------------------------------------
            transportConfig      = new TransportConfig(this);
            biometricManager     = new BiometricManager(this);
            validationController = new ValidationController(modeController, null, this, transportConfig);
            activeValidationController = validationController;
            activeBiometricManager     = biometricManager;
            validationController.setBiometricManager(biometricManager);
            if (biometricCallbackRef != null) {
                validationController.setBiometricCallback(biometricCallbackRef);
            }
            gatewayServer.setValidationController(validationController);
            validationController.setGatewayServer(gatewayServer);

            // ----------------------------------------------------------------
            // Gap 2.1: NetworkProximityMonitor — PRIMARY BLE trigger in TRANSPORT.
            // Monitors WiFi SSID to detect station proximity.
            // Only activates BLE when user is physically at a station.
            // HOTEL mode: never started, zero impact.
            // ----------------------------------------------------------------
            networkProximityMonitor = new NetworkProximityMonitor(
                this,
                transportConfig,
                new NetworkProximityMonitor.StationProximityListener() {
                    @Override
                    public void onNearStationDetected(String ssid) {
                        Log.d(TAG, "[NET] At station: " + ssid + " — enabling BLE");
                        rfActivation.setUserNearStation(true);
                        applyCurrentMode();   // will call ensureScanRunning() in TRANSPORT
                    }

                    @Override
                    public void onLeftStation() {
                        Log.d(TAG, "[NET] Left station — disabling BLE");
                        rfActivation.setUserNearStation(false);
                        applyCurrentMode();   // will call ensureScanStopped() in TRANSPORT
                    }
                },
                biometricManager,
                biometricCallbackRef   // may be null if Activity not yet in foreground
            );
            // Do NOT start monitor yet — it starts only when TRANSPORT mode activates
            // (ValidationController.commitAdvisory sets rfDetectionRequired=true)
            // This prevents any HOTEL-mode WiFi scanning.

            // ----------------------------------------------------------------
            // BLE scan filters + lifecycle — scan params unchanged
            // ----------------------------------------------------------------
            try {
                List<ScanFilter> filters = new ArrayList<>();
                filters.add(new ScanFilter.Builder().setDeviceName("HotelGate").build());
                filters.add(new ScanFilter.Builder().setDeviceName("HotelKiosk").build());
                filters.add(new ScanFilter.Builder().setDeviceName("HotelElevator").build());
                filters.add(new ScanFilter.Builder().setDeviceName("HotelRoom").build());
                filters.add(new ScanFilter.Builder().setDeviceName("ER26B00001").build());
                filters.add(new ScanFilter.Builder().setDeviceName("ER26B00002").build());
                filters.add(new ScanFilter.Builder().setDeviceName("ER26B00003").build());
                filters.add(new ScanFilter.Builder().setDeviceName("ER26B00004").build());
                filters.add(new ScanFilter.Builder().setDeviceName("BCPro_212364").build());

                ScanSettings placeholder = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

                rfActivation = new RFActivationController(bleScanner, filters, placeholder, scanCallback);
                validationController.setRfActivation(rfActivation);

                // HOTEL → ensureScanRunning() → identical to original startScan() call
                applyCurrentMode();

            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied", e); stopSelf();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to start Gateway Server", e); stopSelf();
        }
    }

    /**
     * Single point of scan lifecycle control.
     *
     * HOTEL mode:
     *   Always running, LOW_LATENCY — original behaviour, completely unchanged.
     *
     * TRANSPORT mode (Gap 2.1 — network proximity is PRIMARY trigger):
     *   BLE scan on  ← userNearStation=true  (set by NetworkProximityMonitor)
     *   BLE scan off ← userNearStation=false (user left station network)
     *   rfDetectionRequired controls whether TRANSPORT mode is active — NOT scan on/off.
     *
     * Advisory rfDetectionRequired=true  → switch to TRANSPORT, start network monitor
     * Advisory rfDetectionRequired=false → switch to HOTEL, stop network monitor
     * Network WiFi match                 → setUserNearStation(true) → ensureScanRunning()
     * Network WiFi lost                  → setUserNearStation(false) → ensureScanStopped()
     */
    public void applyCurrentMode() {
        if (rfActivation == null) return;
        Log.d(TAG, T_MODE + " applyCurrentMode: " + modeController.getMode()
            + " rfRequired=" + rfActivation.isRfDetectionRequired()
            + " nearStation=" + rfActivation.isUserNearStation());

        if (modeController.isHotelMode()) {
            // ----------------------------------------------------------------
            // HOTEL: always on, LOW_LATENCY — completely unchanged behaviour
            // ----------------------------------------------------------------
            rfActivation.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            rfActivation.ensureScanRunning();

            // Stop network monitor if it was running (TRANSPORT→HOTEL revert)
            if (networkProximityMonitor != null) {
                networkProximityMonitor.stop();
            }

        } else if (modeController.isTransportMode() || modeController.isHybridMode()) {
            // ----------------------------------------------------------------
            // TRANSPORT: BALANCED scan, activated ONLY by network proximity (Gap 2.1)
            // ----------------------------------------------------------------
            rfActivation.setScanMode(ScanSettings.SCAN_MODE_BALANCED);

            if (rfActivation.isRfDetectionRequired()) {
                // Advisory has activated TRANSPORT — start network monitor
                // Monitor will call setUserNearStation + applyCurrentMode when at station
                if (networkProximityMonitor != null && !networkProximityMonitor.isNearStation()) {
                    networkProximityMonitor.start();
                }

                // BLE scan: on only when user is physically at station
                if (rfActivation.isUserNearStation()) {
                    rfActivation.ensureScanRunning();
                } else {
                    rfActivation.ensureScanStopped();
                }
            } else {
                // rfDetectionRequired=false → stop everything
                rfActivation.ensureScanStopped();
                if (networkProximityMonitor != null) {
                    networkProximityMonitor.stop();
                }
            }
        }

        broadcastTransportDebugState();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (rfActivation != null)          rfActivation.ensureScanStopped();
        if (validationController != null)  validationController.shutdown();
        if (networkProximityMonitor != null) networkProximityMonitor.stop();
        activeValidationController = null;
        activeBiometricManager     = null;
        if (contextScheduler != null)      contextScheduler.shutdownNow();
        if (motionAnalyzer != null)        motionAnalyzer.stop();
        if (gatewayServer != null)         gatewayServer.stop();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(simulationReceiver);
        lbm.unregisterReceiver(biometricSuccessReceiver);
        lbm.unregisterReceiver(nfcTagReadReceiver);
        lastSeenBeacons.clear();
        lastRssiValues.clear();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // Debug state broadcast — unchanged structure, new nearStation field added
    // -------------------------------------------------------------------------

    private void broadcastTransportDebugState() {
        try {
            boolean sessionActive = validationController != null
                && validationController.isTransportSessionActive();
            boolean bioFresh = biometricManager != null && transportConfig != null
                && biometricManager.isBiometricFresh(transportConfig.getBiometricMaxAgeMs());
            boolean advisoryActive = !modeController.isHotelMode();
            boolean nearStation    = rfActivation != null && rfActivation.isUserNearStation();

            Intent dbg = new Intent("TRANSPORT_DEBUG_UPDATE");
            dbg.putExtra("deviceMode",    modeController.getMode().name());
            dbg.putExtra("sessionActive", sessionActive);
            dbg.putExtra("biometricFresh", bioFresh);
            dbg.putExtra("advisoryActive", advisoryActive);
            dbg.putExtra("nearStation",   nearStation);
            LocalBroadcastManager.getInstance(this).sendBroadcast(dbg);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public DeviceModeController getModeController()         { return modeController; }
    public RFActivationController getRfActivation()         { return rfActivation; }
    public ValidationController getValidationController()   { return validationController; }
    public BiometricManager getBiometricManager()           { return biometricManager; }
    public ContextBuilder getContextBuilder()               { return contextBuilder; }
    public MotionAnalyzer getMotionAnalyzer()               { return motionAnalyzer; }
    public BluetoothConnectionMonitor getBluetoothMonitor() { return bluetoothMonitor; }

    // -------------------------------------------------------------------------
    // Private helpers — unchanged
    // -------------------------------------------------------------------------

    private String mapDeviceToDisplayName(String deviceName) {
        switch (deviceName) {
            case "ER26B00001":
            case "BCPro_212364": return "HotelGate";
            case "ER26B00002":   return "HotelKiosk";
            case "ER26B00003":   return "HotelElevator";
            case "ER26B00004":   return "HotelRoom";
            default:             return deviceName;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "BLE Scan Service", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NotificationManager.class))
            .createNotificationChannel(ch);
    }

    private Notification createNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hotel BLE Scanner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .build();
    }
}
