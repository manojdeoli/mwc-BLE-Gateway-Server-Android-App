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
import com.hotel.blescanner.insurance.InsuranceConfig;
import com.hotel.blescanner.insurance.InsuranceLocationProvider;
import com.hotel.blescanner.insurance.InsuranceSessionManager;
import com.hotel.blescanner.insurance.InsuranceTelemetryEventFactory;
import com.hotel.blescanner.insurance.InsuranceTelemetryPublisher;
import com.hotel.blescanner.insurance.VehicleAssociationController;
import com.hotel.blescanner.insurance.InsuranceBackendMonitor;
import com.hotel.blescanner.mode.DeviceMode;
import com.hotel.blescanner.mode.DeviceModePrefs;
import com.hotel.blescanner.mode.DeviceModeController;
import com.hotel.blescanner.motion.MotionAnalyzer;
import com.hotel.blescanner.transport.BiometricCallback;
import com.hotel.blescanner.transport.BiometricManager;
import com.hotel.blescanner.transport.NetworkProximityMonitor;
import com.hotel.blescanner.transport.RFActivationController;
import com.hotel.blescanner.transport.ValidationController;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
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

    // Insurance fields — null unless INSURANCE mode is active
    private InsuranceConfig         insuranceConfig         = null;
    private InsuranceSessionManager insuranceSessionManager = null;

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
        if (activeBiometricManager != null) {
            activeBiometricManager.recordAuthTime();
            // Refresh debug panel immediately so BIOMETRIC shows FRESH
            BLEScanService svc = activeInstance;
            if (svc != null) svc.broadcastTransportDebugState();
        }
    }

    // -------------------------------------------------------------------------
    // Broadcast receivers
    // -------------------------------------------------------------------------

    /** Records device unlock time in BiometricManager for OS-level freshness check */
    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (biometricManager != null) {
                biometricManager.recordOsUnlockTime();
                Log.d(TAG, T_BIO + " Device unlocked — OS unlock time recorded");
                broadcastTransportDebugState();
            }
        }
    };

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

                    if (modeController.isInsuranceMode() && insuranceSessionManager != null) {
                        insuranceSessionManager.onBeaconDetected(deviceName, rssi);
                        // Broadcast so UI can show vehicle beacon RSSI in Insurance mode
                        InsuranceConfig cfg = insuranceConfig;
                        if (cfg != null && (deviceName.equals(cfg.getRegisteredVehicleBeaconId())
                                || deviceName.equals(cfg.getPhysicalBeaconId()))) {
                            Intent vi = new Intent("INSURANCE_BEACON_UPDATE");
                            vi.putExtra("beaconName", deviceName);
                            vi.putExtra("rssi", rssi);
                            vi.putExtra("sessionState",
                                insuranceSessionManager.getSessionState().name());
                            LocalBroadcastManager.getInstance(BLEScanService.this).sendBroadcast(vi);
                        }
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
        // ACTION_USER_PRESENT fires when user unlocks the device (fingerprint/PIN/pattern).
        // Must be registered dynamically — not supported in manifest.
        // Android 14+ requires RECEIVER_EXPORTED for system broadcasts.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(userPresentReceiver,
                new IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_EXPORTED);
        } else {
            registerReceiver(userPresentReceiver,
                new IntentFilter(Intent.ACTION_USER_PRESENT));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // On Android 14+ (API 34+), startForeground() with a location type requires
        // ACCESS_FINE_LOCATION to already be granted — otherwise the system throws
        // a SecurityException and crashes the service immediately.
        // Use location type only when the permission is actually granted.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            boolean locationGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
            int serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (locationGranted) {
                serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIFICATION_ID, createNotification("Scanning for beacons..."), serviceType);
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Scanning for beacons..."));
        }
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
            gatewayServer = new GatewayServer(DEMO_SUBSCRIPTION_ID, this);
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
            fetchRemoteBeaconConfig(); // overlay remote config if server has one — after transportConfig is ready
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
            // Re-send pending biometric result immediately when client reconnects
            gatewayServer.setClientConnectedListener(
                () -> validationController.onClientReconnected());

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

                // Auto-activate based on last mode saved by the connecting app
                DeviceModePrefs modePrefs = new DeviceModePrefs(this);
                if (modePrefs.getLastMode() == DeviceMode.INSURANCE) {
                    InsuranceConfig storedCfg = new InsuranceConfig(this);
                    if (storedCfg.isEnabled() && storedCfg.isValid()) {
                        Log.d(TAG, "[INS] Last app was Insurance — auto-activating INSURANCE mode");
                        activateInsuranceMode(storedCfg);
                    } else {
                        Log.w(TAG, "[INS] Last mode was INSURANCE but config invalid — defaulting to HOTEL");
                        applyCurrentMode();
                    }
                } else {
                    Log.d(TAG, T_MODE + " Last app was Hotel — starting in HOTEL mode");
                    applyCurrentMode();
                }

            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied", e); stopSelf();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to start Gateway Server", e); stopSelf();
        }
    }

    // -------------------------------------------------------------------------
    // Remote beacon config fetch — runs once at startup on a background thread
    // Fetches beacon_config.json from the web server (same PC as the web app).
    // Server URL is read from TransportConfig; falls back to stored/default config
    // if server is unreachable or returns 404.
    // -------------------------------------------------------------------------

    private void fetchRemoteBeaconConfig() {
        String serverUrl = transportConfig.getBeaconConfigServerUrl();
        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.d(TAG, T_CONFIG + " No beacon config server URL configured — skipping remote fetch");
            return;
        }
        final String url = serverUrl.replaceAll("/+$", "") + "/config/beacons";
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Log.d(TAG, T_CONFIG + " Fetching remote beacon config from: " + url);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                int status = conn.getResponseCode();
                if (status == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    String json = sb.toString();
                    boolean applied = beaconConfigManager.updateConfigFromJson(json);
                    if (applied) {
                        Log.d(TAG, T_CONFIG + " Remote beacon config applied: v"
                            + beaconConfigManager.getLoadedVersion());
                        if (gatewayServer != null)
                            gatewayServer.setBeaconConfigManager(beaconConfigManager);
                        if (validationController != null)
                            validationController.setBeaconConfigManager(beaconConfigManager);
                    }
                } else if (status == 404) {
                    Log.d(TAG, T_CONFIG + " No custom config on server (404) — using defaults");
                } else {
                    Log.w(TAG, T_CONFIG + " Remote config fetch returned HTTP " + status);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, T_CONFIG + " Remote config fetch failed: " + e.getMessage()
                    + " — using stored/default config");
            }
        });
    }

    public void onServerUrlReceived(String url) {
        transportConfig.set("BEACON_CONFIG_SERVER_URL", url);
        Log.d(TAG, T_CONFIG + " Beacon config server URL stored: " + url);
        fetchRemoteBeaconConfig();
    }

    /**
     * Called by GatewayServer when the backend sends a requestResync message.
     * Re-broadcasts the last known insurance status event so the backend
     * liveTrips cache is repopulated after a WiFi cycle or backend restart.
     */
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
        } else if (modeController.isInsuranceMode()) {
            if (networkProximityMonitor != null) networkProximityMonitor.stop();
            // Use broad scan (no filters) in INSURANCE mode so the vehicle beacon
            // is always received regardless of how it advertises its name.
            // Software filtering happens in the scan callback via deviceName comparison.
            rfActivation.setScanMode(insuranceConfig != null
                ? insuranceConfig.getVerificationScanMode()
                : ScanSettings.SCAN_MODE_BALANCED);
            rfActivation.startBroadScan();
        }
        broadcastTransportDebugState();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (rfActivation != null)           rfActivation.ensureScanStopped();
        if (validationController != null)   validationController.shutdown();
        if (networkProximityMonitor != null) networkProximityMonitor.stop();
        if (insuranceSessionManager != null) insuranceSessionManager.stop();
        activeValidationController = null;
        activeBiometricManager     = null;
        activeInstance             = null;
        if (contextScheduler != null)       contextScheduler.shutdownNow();
        if (motionAnalyzer != null)         motionAnalyzer.stop();
        if (gatewayServer != null)          gatewayServer.stop();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(biometricSuccessReceiver);
        lbm.unregisterReceiver(nfcTagReadReceiver);
        unregisterReceiver(userPresentReceiver);
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
    // Insurance mode wiring
    // -------------------------------------------------------------------------

    /**
     * Called by GatewayServer when an inbound WebSocket message with type
     * "insuranceConfig" is received. Parses the config and activates INSURANCE mode.
     */
    public void onInsuranceConfigReceived(String json) {
        InsuranceConfig cfg = new InsuranceConfig(this);
        cfg.loadFromJson(json);
        if (!cfg.isValid()) {
            Log.w(TAG, "[INS] Received invalid insurance config — ignoring");
            return;
        }
        // Skip re-activation if already in INSURANCE mode with the same beacon ID.
        // This prevents flickering caused by the web client re-sending insuranceConfig
        // on every WebSocket reconnect.
        if (modeController.isInsuranceMode() && insuranceConfig != null
                && cfg.getRegisteredVehicleBeaconId().equals(
                        insuranceConfig.getRegisteredVehicleBeaconId())) {
            Log.d(TAG, "[INS] insuranceConfig received — already active with same beacon, skipping restart");
            broadcastTransportDebugState(); // ensure UI stays in sync after WS reconnect
            return;
        }
        cfg.setEnabled(true);
        activateInsuranceMode(cfg);
    }

    private void activateInsuranceMode(InsuranceConfig cfg) {
        if (modeController.isInsuranceMode() && insuranceSessionManager != null) {
            Log.d(TAG, "[INS] Already in INSURANCE mode — restarting with new config");
            insuranceSessionManager.stop();
        }
        // Persist INSURANCE mode so service restarts auto-activate without needing WS message
        new DeviceModePrefs(this).saveMode(DeviceMode.INSURANCE);
        insuranceConfig = cfg;

        InsuranceLocationProvider locationProvider = new InsuranceLocationProvider(this, cfg);
        VehicleAssociationController vehicleController = new VehicleAssociationController(cfg);
        InsuranceTelemetryPublisher publisher = new InsuranceTelemetryPublisher(cfg, gatewayServer);
        InsuranceTelemetryEventFactory factory = new InsuranceTelemetryEventFactory(cfg, biometricManager);
        insuranceSessionManager = new InsuranceSessionManager(
            cfg, biometricManager, vehicleController, factory, publisher, locationProvider, this);

        insuranceSessionManager.setStatusListener(
            (state, sid, beaconDetected, freshness, pubState) ->
                gatewayServer.broadcastInsuranceStatus(
                    sid, state.name(), beaconDetected, freshness.name(), pubState.name()));

        gatewayServer.setInsuranceHealthProvider(new GatewayServer.InsuranceHealthProvider() {
            @Override
            public java.util.Map<String, Object> getInsuranceHealthBlock() {
                java.util.Map<String, Object> block = new java.util.HashMap<>();
                // GAP #10 — enriched insurance health block
                block.put("enabled",        true);
                block.put("configured",     cfg.isValid());
                block.put("sessionState",   insuranceSessionManager.getSessionState().name());
                block.put("sessionId",      insuranceSessionManager.getSessionId() != null
                    ? InsuranceTelemetryEventFactory.maskId(insuranceSessionManager.getSessionId())
                    : null);
                block.put("sessionActive",  insuranceSessionManager.isSessionActive());
                block.put("authFreshness",  insuranceSessionManager.getFreshnessState().name());

                // Publisher / queue
                InsuranceTelemetryPublisher pub = insuranceSessionManager.getPublisher();
                block.put("publisherState",        pub.getPublisherState().name());
                block.put("lastPublishStatus",     pub.getLastPublishStatus());
                block.put("lastSuccessfulPublish",  pub.getLastSuccessMs() > 0
                    ? InsuranceTelemetryEventFactory.toIso8601(pub.getLastSuccessMs()) : null);
                block.put("pendingEvents",         pub.getPendingCount());

                // Backend reachability (GAP #3)
                block.putAll(pub.getBackendMonitor().toHealthBlock());

                // GPS / speed (GAP #7, #8)
                InsuranceLocationProvider loc = insuranceSessionManager.getLocationProvider();
                block.put("gpsAvailable",          loc.isLocationAvailable());
                block.put("gpsPermissionGranted",  loc.isGpsPermissionGranted());
                block.put("speedAvailable",        loc.isSpeedAvailable());
                block.put("speedSource",           loc.getSpeedSource());

                // Beacon / association (GAP #5)
                VehicleAssociationController vc = insuranceSessionManager.getVehicleController();
                block.put("beaconDetected",         vc.isBeaconDetected());
                block.put("associationState",       vc.getState().name());
                block.put("advertisementCount",     vc.getAdvertisementCount());
                block.put("averageRssi",            vc.getAverageRssi());
                block.put("associationDurationSeconds", vc.getAssociationDurationMs() / 1000L);

                // Scan profile (GAP #9)
                block.put("scanProfile",           vc.getCurrentScanProfile());
                block.put("scanTransitionReason",  vc.getScanTransitionReason());

                // Event history (GAP #11)
                block.put("recentEvents", insuranceSessionManager.getEventHistory());

                return block;
            }
            @Override
            public String getDeviceModeName() { return DeviceMode.INSURANCE.name(); }
        });

        // GAP #1 — mode set BEFORE start(); start() enters WAITING_FOR_VEHICLE, not a session
        modeController.setDeviceMode(DeviceMode.INSURANCE, "insuranceConfig received");
        insuranceSessionManager.start();
        applyCurrentMode();
        Log.d(TAG, "[INS] INSURANCE mode activated — waiting for vehicle");
    }

    private void deactivateInsuranceMode() {
        if (insuranceSessionManager != null) {
            insuranceSessionManager.stop();
            insuranceSessionManager = null;
        }
        insuranceConfig = null;
        gatewayServer.setInsuranceHealthProvider(null);
        modeController.setDeviceMode(DeviceMode.HOTEL, "insurance deactivated");
        applyCurrentMode();
        Log.d(TAG, "[INS] INSURANCE mode deactivated — reverted to HOTEL");
    }

    public void onResyncRequested() {
        if (insuranceSessionManager != null) {
            insuranceSessionManager.publishResync();
        } else {
            Log.d(TAG, "[INS] Resync requested but no active session");
        }
    }

    public void onNetworkReconnected() {
        Log.d(TAG, T_CONFIG + " Network reconnected — triggering state resync");
        if (gatewayServer != null) gatewayServer.broadcastStateResync();
    }

    public void resetToHotelMode() {
        new DeviceModePrefs(this).saveMode(DeviceMode.HOTEL);
        deactivateInsuranceMode();
        Log.d(TAG, "[INS] Manual reset to HOTEL mode");
    }

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
            .setContentTitle("BLE Scanner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .build();
    }
}
