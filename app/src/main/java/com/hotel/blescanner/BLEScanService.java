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
import com.hotel.blescanner.config.BeaconConfigManager;
import com.hotel.blescanner.config.TransportConfig;
import com.hotel.blescanner.context.ContextBuilder;
import com.hotel.blescanner.context.ContextConfig;
import com.hotel.blescanner.context.ContextEvent;
import com.hotel.blescanner.context.ScoringConfig;
import com.hotel.blescanner.mode.DeviceModeController;
import com.hotel.blescanner.motion.MotionAnalyzer;
import com.hotel.blescanner.transport.BiometricCallback;
import com.hotel.blescanner.transport.BiometricManager;
import com.hotel.blescanner.transport.NetworkProximityMonitor;
import com.hotel.blescanner.transport.RFActivationController;
import com.hotel.blescanner.transport.ValidationController;
import java.io.IOException;
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
    private static final String T_CONFIG        = "[CONFIG]";
    private static final int    NOTIFICATION_ID = 1;
    private static final int    BIOMETRIC_NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID      = "BLEScanChannel";
    private static final String BIOMETRIC_CHANNEL_ID = "BiometricChannel";
    private static final String DEMO_SUBSCRIPTION_ID = "hotel-demo-subscription";
    /** Intent action used by the full-screen notification to trigger biometric prompt. */
    public static final String ACTION_BIOMETRIC_REQUEST = "com.hotel.blescanner.BIOMETRIC_REQUEST";
    public static final String EXTRA_JOURNEY_ID         = "journeyId";

    // Core BLE + server
    private BluetoothLeScanner     bleScanner;
    private GatewayServer          gatewayServer;
    private RFActivationController rfActivation;

    /**
     * Single source of truth for all beacon identification, mapping, and routing.
     * Replaces all five previously hardcoded beacon locations across this file
     * and GatewayServer. Loaded once at service start; updated via
     * restartScanFilters() when backend sends a new config.
     */
    private BeaconConfigManager beaconConfigManager;

    // Transport controllers
    private ValidationController   validationController;
    private BiometricManager        biometricManager;
    private TransportConfig         transportConfig;
    private NetworkProximityMonitor networkProximityMonitor;

    /**
     * Refinement 2.5: set to true when updateConfigFromJson() is called during
     * an active transport session. The config restart is deferred until the
     * session ends to avoid disrupting mid-validation BLE detection.
     */
    private volatile boolean pendingConfigRestart = false;

    // RSSI dedup maps — unchanged
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
    private static volatile BLEScanService       activeInstance            = null;

    /** Used by GatewayServer to route beacon config updates to the service. */
    public static BLEScanService getActiveInstance() { return activeInstance; }

    public static void setBiometricCallbackRef(BiometricCallback callback) {
        biometricCallbackRef = callback;
        if (activeValidationController != null) {
            activeValidationController.setBiometricCallback(callback);
        }
    }

    public static void recordBiometricAuthTime() {
        if (activeBiometricManager != null) activeBiometricManager.recordAuthTime();
    }

    // -------------------------------------------------------------------------
    // Broadcast receivers
    // -------------------------------------------------------------------------

    /** Pre-journey biometric — now routes to validation success when in TRANSPORT mode */
    private final BroadcastReceiver biometricSuccessReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, T_BIO + " Biometric succeeded");
            if (validationController != null) {
                validationController.onBiometricValidationSuccess();
            }
        }
    };

    /** NFC tag read — unchanged */
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
    // Only mapDeviceToDisplayName() replaced with beaconConfigManager.mapToLogicalName().
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

                    if (contextBuilder != null) contextBuilder.notifyBleBeaconSeen();

                    // Single source of truth — replaces mapDeviceToDisplayName()
                    String mappedName = beaconConfigManager.mapToLogicalName(deviceName);

                    try {
                        Intent bi = new Intent("BEACON_UPDATE");
                        bi.putExtra("beaconName", mappedName);
                        bi.putExtra("rssi", rssi);
                        LocalBroadcastManager.getInstance(BLEScanService.this).sendBroadcast(bi);
                    } catch (Exception e) {
                        Log.e(TAG, "Broadcast error", e);
                    }

                    if (gatewayServer != null) gatewayServer.broadcastBLEEvent(deviceName, rssi);

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
            // BeaconConfigManager — initialise before scan filters are built
            // ----------------------------------------------------------------
            beaconConfigManager = new BeaconConfigManager(this);
            beaconConfigManager.loadConfig();

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
            activeInstance             = this;
            validationController.setBiometricManager(biometricManager);
            validationController.setBeaconConfigManager(beaconConfigManager);
            if (biometricCallbackRef != null) {
                validationController.setBiometricCallback(biometricCallbackRef);
            }
            gatewayServer.setValidationController(validationController);
            gatewayServer.setBeaconConfigManager(beaconConfigManager);
            validationController.setGatewayServer(gatewayServer);
            validationController.setMotionAnalyzer(motionAnalyzer);

            networkProximityMonitor = new NetworkProximityMonitor(
                this, transportConfig,
                new NetworkProximityMonitor.StationProximityListener() {
                    @Override public void onNearStationDetected(String ssid) {
                        Log.d(TAG, "[NET] At station: " + ssid + " — enabling BLE");
                        rfActivation.setUserNearStation(true);
                        applyCurrentMode();
                    }
                    @Override public void onLeftStation() {
                        Log.d(TAG, "[NET] Left station — disabling BLE");
                        rfActivation.setUserNearStation(false);
                        applyCurrentMode();
                    }
                },
                biometricManager, biometricCallbackRef);

            // ----------------------------------------------------------------
            // BLE scan filters — from BeaconConfigManager (replaces hardcoded list)
            // ----------------------------------------------------------------
            try {
                List<ScanFilter> filters = beaconConfigManager.getScanFilters();
                if (beaconConfigManager.requiresBroadScan()) {
                    Log.w(TAG, T_CONFIG + " Broad scan active — PREFIX entry without knownIdentifiers");
                }

                ScanSettings placeholder = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

                rfActivation = new RFActivationController(bleScanner, filters, placeholder, scanCallback);
                validationController.setRfActivation(rfActivation);
                applyCurrentMode();

            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied", e); stopSelf();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to start Gateway Server", e); stopSelf();
        }
    }

    // -------------------------------------------------------------------------
    // Config reload — called by GatewayServer when backend sends new config
    // -------------------------------------------------------------------------

    /**
     * Applies a new beacon config JSON received from the backend.
     * If a transport session is active, the scan restart is deferred until
     * the session ends (refinement 2.5 — no mid-validation disruption).
     *
     * @param json raw JSON config string
     */
    public void onBeaconConfigReceived(String json) {
        boolean applied = beaconConfigManager.updateConfigFromJson(json);
        if (!applied) {
            Log.w(TAG, T_CONFIG + " Config update rejected — keeping existing config");
            return;
        }
        // Wire updated config into GatewayServer and ValidationController immediately
        // (lookup maps are rebuilt; only scan filters need a restart)
        gatewayServer.setBeaconConfigManager(beaconConfigManager);
        validationController.setBeaconConfigManager(beaconConfigManager);
        restartScanFilters();
    }

    /**
     * Restarts BLE scanning with new filters from BeaconConfigManager.
     * Refinement 2.5: deferred if a transport session is active.
     */
    public void restartScanFilters() {
        if (validationController != null && validationController.isTransportSessionActive()) {
            Log.d(TAG, T_CONFIG + " Config update deferred — transport session active");
            pendingConfigRestart = true;
            return;
        }
        applyConfigRestart();
    }

    /**
     * Called by ValidationController when a transport session ends.
     * Applies any deferred config restart (refinement 2.5).
     */
    public void onSessionEnded() {
        if (pendingConfigRestart) {
            Log.d(TAG, T_CONFIG + " Applying deferred config update");
            pendingConfigRestart = false;
            applyConfigRestart();
        }
    }

    private void applyConfigRestart() {
        if (rfActivation == null) return;
        rfActivation.ensureScanStopped();
        List<ScanFilter> newFilters = beaconConfigManager.getScanFilters();
        rfActivation.updateFilters(newFilters);
        if (beaconConfigManager.requiresBroadScan()) {
            Log.w(TAG, T_CONFIG + " Broad scan active after config restart");
        }
        applyCurrentMode();  // restarts scan in correct mode
        Log.d(TAG, T_CONFIG + " Scan restarted with config v" + beaconConfigManager.getLoadedVersion()
            + " (" + newFilters.size() + " filters)");
    }

    // -------------------------------------------------------------------------
    // Scan lifecycle — unchanged logic, same as before
    // -------------------------------------------------------------------------

    public void applyCurrentMode() {
        if (rfActivation == null) return;
        Log.d(TAG, T_MODE + " applyCurrentMode: " + modeController.getMode()
            + " rfRequired=" + rfActivation.isRfDetectionRequired()
            + " nearStation=" + rfActivation.isUserNearStation());

        if (modeController.isHotelMode()) {
            rfActivation.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            rfActivation.ensureScanRunning();
            if (networkProximityMonitor != null) networkProximityMonitor.stop();

        } else if (modeController.isTransportMode() || modeController.isHybridMode()) {
            rfActivation.setScanMode(ScanSettings.SCAN_MODE_BALANCED);
            if (rfActivation.isRfDetectionRequired()) {
                if (networkProximityMonitor != null && !networkProximityMonitor.isNearStation()) {
                    networkProximityMonitor.start();
                }
                if (rfActivation.isUserNearStation()) {
                    rfActivation.ensureScanRunning();
                } else {
                    rfActivation.ensureScanStopped();
                }
            } else {
                rfActivation.ensureScanStopped();
                if (networkProximityMonitor != null) networkProximityMonitor.stop();
            }
        }
        broadcastTransportDebugState();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (rfActivation != null)           rfActivation.ensureScanStopped();
        if (validationController != null)   validationController.shutdown();
        if (networkProximityMonitor != null) networkProximityMonitor.stop();
        activeValidationController = null;
        activeBiometricManager     = null;
        activeInstance             = null;
        if (contextScheduler != null)       contextScheduler.shutdownNow();
        if (motionAnalyzer != null)         motionAnalyzer.stop();
        if (gatewayServer != null)          gatewayServer.stop();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(biometricSuccessReceiver);
        lbm.unregisterReceiver(nfcTagReadReceiver);
        lastSeenBeacons.clear();
        lastRssiValues.clear();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // Debug state broadcast
    // -------------------------------------------------------------------------

    private void broadcastTransportDebugState() {
        try {
            boolean sessionActive  = validationController != null
                && validationController.isTransportSessionActive();
            boolean bioFresh       = biometricManager != null && transportConfig != null
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
    public BeaconConfigManager getBeaconConfigManager()     { return beaconConfigManager; }
    public ContextBuilder getContextBuilder()               { return contextBuilder; }
    public MotionAnalyzer getMotionAnalyzer()               { return motionAnalyzer; }
    public BluetoothConnectionMonitor getBluetoothMonitor() { return bluetoothMonitor; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "BLE Scan Service", NotificationManager.IMPORTANCE_LOW);
        NotificationChannel bioCh = new NotificationChannel(
            BIOMETRIC_CHANNEL_ID, "Identity Verification", NotificationManager.IMPORTANCE_HIGH);
        bioCh.setDescription("Tap to verify your identity to complete journey validation");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(ch);
        nm.createNotificationChannel(bioCh);
    }

    /**
     * Posts a full-screen notification that brings MainActivity to foreground
     * (even from lock screen) so the biometric prompt can be launched.
     * Called by ValidationController when biometricCallback is null
     * (Activity is in background or device is locked).
     *
     * @param journeyId passed to MainActivity via intent extra
     */
    public void postBiometricNotification(String journeyId) {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);

            // Build intent that launches MainActivity and triggers biometric
            android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
            intent.setAction(ACTION_BIOMETRIC_REQUEST);
            intent.putExtra(EXTRA_JOURNEY_ID, journeyId);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);

            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | android.app.PendingIntent.FLAG_IMMUTABLE);

            androidx.core.app.NotificationCompat.Builder builder =
                new androidx.core.app.NotificationCompat.Builder(this, BIOMETRIC_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle("Identity Verification Required")
                    .setContentText("Tap to verify your identity to complete journey validation")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pi, true)   // shows on lock screen
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify(BIOMETRIC_NOTIFICATION_ID, builder.build());
            Log.d(TAG, T_BIO + " Biometric notification posted for journey: " + journeyId);
        } catch (Exception e) {
            Log.e(TAG, T_BIO + " Failed to post biometric notification", e);
        }
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
