package com.hotel.blescanner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.hotel.blescanner.transport.BiometricCallback;
import com.hotel.blescanner.transport.RfidNfcReader;
import com.hotel.blescanner.BuildConfig;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity implements BiometricCallback {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1;

    // -------------------------------------------------------------------------
    // Existing beacon UI — unchanged
    // -------------------------------------------------------------------------

    private Button   startButton;
    private Button   stopButton;
    private TextView ipAddressText;
    private TextView beaconGate;
    private TextView beaconKiosk;
    private TextView beaconElevator;
    private TextView beaconRoom;

    // Context display UI — unchanged
    private TextView contextMode;
    private TextView contextConfidence;
    private TextView contextMotion;
    private TextView contextSpeed;

    // Simulation toggles — unchanged
    private Switch simBluetoothSwitch;
    private Switch simMotionSwitch;

    private boolean isServiceRunning = false;

    // Fix 3.2: tracks the beacon that triggered the current validation so it can
    // be passed to ValidationController.onBiometricSuccess(beaconName)
    private volatile String lastValidationBeacon = "unknown";

    // NFC/RFID reader — parallel validation path alongside biometric
    private RfidNfcReader rfidNfcReader;

    // Fix C: debug transport status TextViews (debug builds only)
    private TextView debugDeviceMode;
    private TextView debugSession;
    private TextView debugBiometric;
    private TextView debugAdvisory;

    // -------------------------------------------------------------------------
    // Broadcast receivers
    // -------------------------------------------------------------------------

    // Existing — unchanged
    private final BroadcastReceiver beaconReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String beaconName = intent.getStringExtra("beaconName");
            int    rssi       = intent.getIntExtra("rssi", 0);
            updateBeaconDisplay(beaconName, rssi);
        }
    };

    // Existing — unchanged
    private final BroadcastReceiver contextReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String mode       = intent.getStringExtra("mode");
            int    confidence = intent.getIntExtra("confidence", 0);
            String motion     = intent.getStringExtra("motion");
            float  speed      = intent.getFloatExtra("speed", 0f);
            updateContextDisplay(mode, confidence, motion, speed);
        }
    };

    // Fix 3.2: receive beaconName from service when a validation is triggered,
    // so we can pass it back on biometric success
    private final BroadcastReceiver validationTriggerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String beacon = intent.getStringExtra("beaconName");
            if (beacon != null) {
                lastValidationBeacon = beacon;
                Log.d(TAG, "[VALIDATION] Validation triggered at: " + beacon);
            }
        }
    };

    // Fix C: receives transport debug state from BLEScanService for the debug panel
    private final BroadcastReceiver transportDebugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String  deviceMode   = intent.getStringExtra("deviceMode");
            boolean sessionActive = intent.getBooleanExtra("sessionActive", false);
            boolean bioFresh     = intent.getBooleanExtra("biometricFresh", false);
            boolean advisory     = intent.getBooleanExtra("advisoryActive", false);
            updateTransportDebugPanel(deviceMode, sessionActive, bioFresh, advisory);
        }
    };

    /**
     * NFC path: receives NFC_ENABLE from ValidationController (via BLEScanService)
     * when rfDetectionRequired=true in the advisory.
     * Calls enableNfcForJourney() on the main thread — NFC foreground dispatch
     * must be enabled from the main thread while the activity is in the foreground.
     */
    private final BroadcastReceiver nfcEnableReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String journeyId = intent.getStringExtra("journeyId");
            enableNfcForJourney(journeyId != null ? journeyId : "unknown");
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Existing views — unchanged
        startButton    = findViewById(R.id.startButton);
        stopButton     = findViewById(R.id.stopButton);
        ipAddressText  = findViewById(R.id.ipAddressText);
        beaconGate     = findViewById(R.id.beaconGate);
        beaconKiosk    = findViewById(R.id.beaconKiosk);
        beaconElevator = findViewById(R.id.beaconElevator);
        beaconRoom     = findViewById(R.id.beaconRoom);

        contextMode       = findViewById(R.id.contextMode);
        contextConfidence = findViewById(R.id.contextConfidence);
        contextMotion     = findViewById(R.id.contextMotion);
        contextSpeed      = findViewById(R.id.contextSpeed);

        simBluetoothSwitch = findViewById(R.id.simBluetoothSwitch);
        simMotionSwitch    = findViewById(R.id.simMotionSwitch);

        // Fix C: transport debug views (present in layout only in debug builds)
        debugDeviceMode = findViewById(R.id.debugDeviceMode);
        debugSession    = findViewById(R.id.debugSession);
        debugBiometric  = findViewById(R.id.debugBiometric);
        debugAdvisory   = findViewById(R.id.debugAdvisory);

        stopButton.setEnabled(false);
        displayIPAddress();

        startButton.setOnClickListener(v -> startScanService());
        stopButton.setOnClickListener(v -> stopScanService());

        View simSection = findViewById(R.id.simSection);
        if (BuildConfig.DEBUG) {
            simSection.setVisibility(View.VISIBLE);
            setupSimulationToggles();
        } else {
            simSection.setVisibility(View.GONE);
        }

        // NFC/RFID reader — initialised once, independent of biometric path
        rfidNfcReader = new RfidNfcReader(this, new RfidNfcReader.RfidResultCallback() {
            @Override
            public void onTagRead(String tagId, String journeyId) {
                // Disable dispatch immediately so subsequent taps are ignored
                rfidNfcReader.disableForegroundDispatch();
                onNfcTagRead(tagId, journeyId);
            }
            @Override
            public void onNfcUnavailable() {
                // NFC not available or disabled — biometric path continues unaffected
                Log.w(TAG, "[NFC] NFC not available on this device");
            }
        });

        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(beaconReceiver,           new IntentFilter("BEACON_UPDATE"));
        lbm.registerReceiver(contextReceiver,          new IntentFilter("CONTEXT_UPDATE"));
        lbm.registerReceiver(validationTriggerReceiver, new IntentFilter("VALIDATION_TRIGGER"));
        lbm.registerReceiver(transportDebugReceiver,   new IntentFilter("TRANSPORT_DEBUG_UPDATE"));
        lbm.registerReceiver(nfcEnableReceiver,        new IntentFilter("NFC_ENABLE"));
        registerBiometricCallback();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(beaconReceiver);
        lbm.unregisterReceiver(contextReceiver);
        lbm.unregisterReceiver(validationTriggerReceiver);
        lbm.unregisterReceiver(transportDebugReceiver);
        lbm.unregisterReceiver(nfcEnableReceiver);
        // Always disable NFC dispatch when leaving foreground — prevents stale dispatch
        if (rfidNfcReader != null) {
            rfidNfcReader.disableForegroundDispatch();
        }
        unregisterBiometricCallback();
    }

    /**
     * Required for NFC foreground dispatch: called by Android when the activity
     * is already on top (launchMode=singleTop) and a new NFC intent arrives.
     * Routes the intent to RfidNfcReader for tag extraction.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (rfidNfcReader != null) {
            rfidNfcReader.handleIntent(intent);
        }
    }

    // -------------------------------------------------------------------------
    // BiometricCallback — Fix 3.4 (service-safe), Fix 3.2 (success feedback)
    // -------------------------------------------------------------------------

    @Override
    public void onBiometricRequired() {
        // Called on scheduler thread — dispatch to main thread
        runOnUiThread(this::launchBiometricPrompt);
    }

    private void launchBiometricPrompt() {
        Log.d(TAG, "[BIOMETRIC] Launching prompt for barrier: " + lastValidationBeacon);
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    super.onAuthenticationSucceeded(r);
                    onBiometricSuccess();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Log.w(TAG, "[BIOMETRIC] Error: " + errString);
                    Toast.makeText(MainActivity.this,
                        "Authentication failed: " + errString, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Log.w(TAG, "[BIOMETRIC] Authentication attempt failed");
                }
            });

        biometricPrompt.authenticate(
            new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Identity Verification")
                .setSubtitle("Verify your identity to proceed")
                .setNegativeButtonText("Cancel")
                .build());
    }

    private void onBiometricSuccess() {
        Log.d(TAG, "[BIOMETRIC] Success at barrier: " + lastValidationBeacon);
        // Fix 3.2: record auth time in BiometricManager via service static method
        BLEScanService.recordBiometricAuthTime();
        // Fix 3.2: notify ValidationController with beaconName so it broadcasts SUCCESS
        if (isServiceRunning) {
            Intent intent = new Intent("BIOMETRIC_SUCCESS");
            intent.putExtra("beaconName", lastValidationBeacon);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
        Toast.makeText(this, "Identity verified", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // NFC path — parallel to biometric, fully independent
    // -------------------------------------------------------------------------

    /**
     * Called by nfcEnableReceiver when ValidationController fires NFC_ENABLE.
     * Enables NFC foreground dispatch scoped to this journey.
     * LocalBroadcastManager delivers on the main thread so NfcAdapter
     * foreground dispatch is always enabled from the correct thread.
     */
    private void enableNfcForJourney(String journeyId) {
        if (rfidNfcReader == null) return;
        if (rfidNfcReader.isDispatchEnabled()) {
            Log.d(TAG, "[NFC] Dispatch already active — ignoring duplicate enable for: " + journeyId);
            return;
        }
        Log.d(TAG, "[NFC] Foreground dispatch enabled for journey: " + journeyId);
        rfidNfcReader.enableForegroundDispatch(journeyId);
    }

    /**
     * Called by RfidNfcReader.RfidResultCallback.onTagRead after a card is read.
     * Mirrors onBiometricSuccess() in structure — sends NFC_TAG_READ LocalBroadcast
     * that BLEScanService.nfcTagReadReceiver picks up and forwards to ValidationController.
     * The AtomicBoolean race guard in ValidationController ensures only one path wins.
     */
    private void onNfcTagRead(String tagId, String journeyId) {
        Log.d(TAG, "[NFC] Tag read: " + tagId + " journey: " + journeyId);
        if (isServiceRunning) {
            Intent intent = new Intent("NFC_TAG_READ");
            intent.putExtra("tagId",     tagId);
            intent.putExtra("journeyId", journeyId);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
        Toast.makeText(this, "Card verified", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Fix C: Transport debug panel (debug builds only)
    // -------------------------------------------------------------------------

    private void updateTransportDebugPanel(String mode, boolean session,
                                           boolean bioFresh, boolean advisory) {
        if (!BuildConfig.DEBUG) return;
        runOnUiThread(() -> {
            if (debugDeviceMode != null)
                debugDeviceMode.setText("[MODE]      " + (mode != null ? mode : "--"));
            if (debugSession != null)
                debugSession.setText(   "[SESSION]   " + (session  ? "ACTIVE"   : "IDLE"));
            if (debugBiometric != null)
                debugBiometric.setText( "[BIOMETRIC] " + (bioFresh ? "FRESH"    : "REQUIRED"));
            if (debugAdvisory != null)
                debugAdvisory.setText(  "[ADVISORY]  " + (advisory ? "ACTIVE"   : "NONE"));
        });
    }

    // -------------------------------------------------------------------------
    // Existing beacon + context display — unchanged
    // -------------------------------------------------------------------------

    private void updateBeaconDisplay(String beaconName, int rssi) {
        runOnUiThread(() -> {
            String display = String.format("%-14s %d dBm", beaconName + ":", rssi);
            switch (beaconName) {
                case "HotelGate":     beaconGate.setText(display);     break;
                case "HotelKiosk":    beaconKiosk.setText(display);    break;
                case "HotelElevator": beaconElevator.setText(display); break;
                case "HotelRoom":     beaconRoom.setText(display);     break;
            }
        });
    }

    private void updateContextDisplay(String mode, int confidence, String motion, float speed) {
        runOnUiThread(() -> {
            contextMode.setText(      String.format("%-12s %s",        "Mode:",       mode   != null ? mode   : "--"));
            contextConfidence.setText(String.format("%-12s %d%%",      "Confidence:", confidence));
            contextMotion.setText(    String.format("%-12s %s",        "Motion:",     motion != null ? motion : "--"));
            contextSpeed.setText(     String.format("%-12s %.1f km/h", "Speed:",      speed));
        });
    }

    // -------------------------------------------------------------------------
    // Simulation toggles — unchanged
    // -------------------------------------------------------------------------

    private void setupSimulationToggles() {
        simBluetoothSwitch.setOnCheckedChangeListener((btn, isChecked) -> applySimulationState());
        simMotionSwitch.setOnCheckedChangeListener(   (btn, isChecked) -> applySimulationState());
    }

    private void applySimulationState() {
        boolean simBluetooth = simBluetoothSwitch.isChecked();
        boolean simVehicle   = simMotionSwitch.isChecked();
        boolean simEnabled   = simBluetooth || simVehicle;
        Intent intent = new Intent("SIMULATION_UPDATE");
        intent.putExtra("simEnabled",   simEnabled);
        intent.putExtra("simBluetooth", simBluetooth);
        intent.putExtra("simVehicle",   simVehicle);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // -------------------------------------------------------------------------
    // Service control — unchanged
    // -------------------------------------------------------------------------

    private void startScanService() {
        try {
            Log.d(TAG, "[MODE] Starting service...");
            Intent serviceIntent = new Intent(this, BLEScanService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            isServiceRunning = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            applySimulationState();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopScanService() {
        stopService(new Intent(this, BLEScanService.class));
        beaconGate.setText("HotelGate:     --");
        beaconKiosk.setText("HotelKiosk:    --");
        beaconElevator.setText("HotelElevator: --");
        beaconRoom.setText("HotelRoom:     --");
        contextMode.setText("Mode:       --");
        contextConfidence.setText("Confidence: --");
        contextMotion.setText("Motion:     --");
        contextSpeed.setText("Speed:      -- km/h");
        isServiceRunning = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    // -------------------------------------------------------------------------
    // Existing helpers — unchanged
    // -------------------------------------------------------------------------

    private void displayIPAddress() {
        WifiManager wifiManager =
            (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        String ip = Formatter.formatIpAddress(wifiInfo.getIpAddress());
        ipAddressText.setText("Server: http://" + ip + ":8080");
    }

    private void checkPermissions() {
        String[] permissions = {
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS
        };
        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    // -------------------------------------------------------------------------
    // BiometricCallback registration — static holder pattern
    // -------------------------------------------------------------------------

    private void registerBiometricCallback() {
        // Always register — the service may be running even if isServiceRunning flag
        // was lost due to activity recreation. setBiometricCallbackRef is safe to call
        // when no service is running (it just stores the ref for later).
        BLEScanService.setBiometricCallbackRef(this);
    }

    private void unregisterBiometricCallback() {
        BLEScanService.setBiometricCallbackRef(null);
    }
}
