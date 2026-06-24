package com.hotel.blescanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
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
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity implements BiometricCallback {

    private static final String TAG                  = "MainActivity";
    private static final int    PERMISSION_REQUEST_CODE = 1;

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

    // NFC/RFID reader — barrier validation path, unchanged
    private RfidNfcReader rfidNfcReader;

    // Debug transport panel (debug builds only)
    private TextView debugDeviceMode;
    private TextView debugSession;
    private TextView debugBiometric;
    private TextView debugAdvisory;
    private TextView debugNearStation;

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

    // Transport debug panel receiver — updated with nearStation field
    private final BroadcastReceiver transportDebugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String  deviceMode  = intent.getStringExtra("deviceMode");
            boolean session     = intent.getBooleanExtra("sessionActive",  false);
            boolean bioFresh    = intent.getBooleanExtra("biometricFresh", false);
            boolean advisory    = intent.getBooleanExtra("advisoryActive", false);
            boolean nearStation = intent.getBooleanExtra("nearStation",    false);
            updateTransportDebugPanel(deviceMode, session, bioFresh, advisory, nearStation);
        }
    };

    /**
     * NFC_ENABLE from ValidationController — triggers NFC foreground dispatch.
     * Unchanged from previous implementation.
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

        // Debug panel views
        debugDeviceMode  = findViewById(R.id.debugDeviceMode);
        debugSession     = findViewById(R.id.debugSession);
        debugBiometric   = findViewById(R.id.debugBiometric);
        debugAdvisory    = findViewById(R.id.debugAdvisory);
        debugNearStation = findViewById(R.id.debugNearStation);

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

        // NFC reader — barrier validation path, unchanged
        rfidNfcReader = new RfidNfcReader(this, new RfidNfcReader.RfidResultCallback() {
            @Override
            public void onTagRead(String tagId, String journeyId) {
                rfidNfcReader.disableForegroundDispatch();
                onNfcTagRead(tagId, journeyId);
            }
            @Override
            public void onNfcUnavailable() {
                Log.w(TAG, "[NFC] NFC not available on this device");
            }
        });

        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(beaconReceiver,        new IntentFilter("BEACON_UPDATE"));
        lbm.registerReceiver(contextReceiver,       new IntentFilter("CONTEXT_UPDATE"));
        lbm.registerReceiver(transportDebugReceiver, new IntentFilter("TRANSPORT_DEBUG_UPDATE"));
        lbm.registerReceiver(nfcEnableReceiver,     new IntentFilter("NFC_ENABLE"));
        registerBiometricCallback();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(beaconReceiver);
        lbm.unregisterReceiver(contextReceiver);
        lbm.unregisterReceiver(transportDebugReceiver);
        lbm.unregisterReceiver(nfcEnableReceiver);
        if (rfidNfcReader != null) rfidNfcReader.disableForegroundDispatch();
        unregisterBiometricCallback();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (rfidNfcReader != null) rfidNfcReader.handleIntent(intent);
    }

    // -------------------------------------------------------------------------
    // BiometricCallback — Phase 2 / Gap 2.3: pre-journey check only
    //
    // This prompt is triggered by NetworkProximityMonitor when the user
    // arrives at a station. It is NOT triggered at the barrier.
    // On success: records auth time only — no barrier or validation broadcast.
    // -------------------------------------------------------------------------

    @Override
    public void onBiometricRequired() {
        // Called on NetworkProximityMonitor thread — dispatch to main thread
        runOnUiThread(this::launchPreJourneyBiometricPrompt);
    }

    private void launchPreJourneyBiometricPrompt() {
        Log.d(TAG, "[BIOMETRIC] Launching pre-journey freshness check");
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    super.onAuthenticationSucceeded(r);
                    onPreJourneyBiometricSuccess();
                }
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    // Non-blocking — user can still proceed through barrier via NFC
                    Log.w(TAG, "[BIOMETRIC] Pre-journey check skipped: " + errString);
                }
                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Log.w(TAG, "[BIOMETRIC] Pre-journey attempt failed");
                }
            });

        biometricPrompt.authenticate(
            new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Journey Verification")
                .setSubtitle("Verify your identity before travelling")
                .setNegativeButtonText("Skip")
                .build());
    }

    /**
     * Phase 2: pre-journey biometric success.
     * Records the auth timestamp ONLY.
     * Does NOT broadcast any barrier event.
     * Does NOT affect pendingValidationRequired.
     * Biometric is NOT a barrier step.
     */
    private void onPreJourneyBiometricSuccess() {
        Log.d(TAG, "[BIOMETRIC] Pre-journey verification succeeded");
        // Record auth time via static service method — no binding needed
        BLEScanService.recordBiometricAuthTime();
        // Notify service for logging only — ValidationController.onBiometricSuccess()
        // does nothing barrier-related in the new design
        if (isServiceRunning) {
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("BIOMETRIC_SUCCESS"));
        }
        Toast.makeText(this, "Identity verified", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // NFC barrier validation path — unchanged
    // -------------------------------------------------------------------------

    private void enableNfcForJourney(String journeyId) {
        if (rfidNfcReader == null) return;
        if (rfidNfcReader.isDispatchEnabled()) {
            Log.d(TAG, "[NFC] Dispatch already active for: " + journeyId);
            return;
        }
        Log.d(TAG, "[NFC] Enabling foreground dispatch for journey: " + journeyId);
        rfidNfcReader.enableForegroundDispatch(journeyId);
    }

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
    // Debug panel — updated with nearStation field
    // -------------------------------------------------------------------------

    private void updateTransportDebugPanel(String mode, boolean session,
                                           boolean bioFresh, boolean advisory,
                                           boolean nearStation) {
        if (!BuildConfig.DEBUG) return;
        runOnUiThread(() -> {
            if (debugDeviceMode  != null) debugDeviceMode.setText( "[MODE]        " + (mode != null ? mode : "--"));
            if (debugSession     != null) debugSession.setText(    "[SESSION]     " + (session     ? "ACTIVE"   : "IDLE"));
            if (debugBiometric   != null) debugBiometric.setText(  "[BIOMETRIC]   " + (bioFresh    ? "FRESH"    : "STALE"));
            if (debugAdvisory    != null) debugAdvisory.setText(   "[ADVISORY]    " + (advisory    ? "ACTIVE"   : "NONE"));
            if (debugNearStation != null) debugNearStation.setText("[AT STATION]  " + (nearStation ? "YES"      : "NO"));
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
            ContextCompat.startForegroundService(this, new Intent(this, BLEScanService.class));
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
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        ipAddressText.setText("Server: http://" + Formatter.formatIpAddress(wi.getIpAddress()) + ":8080");
    }

    private void checkPermissions() {
        String[] permissions = {
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS
        };
        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }
        if (!allGranted) ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    private void registerBiometricCallback()   { BLEScanService.setBiometricCallbackRef(this); }
    private void unregisterBiometricCallback() { BLEScanService.setBiometricCallbackRef(null); }
}
