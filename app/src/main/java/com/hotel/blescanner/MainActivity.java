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
    // Beacon UI
    // -------------------------------------------------------------------------

    private Button   startButton;
    private Button   stopButton;
    private TextView ipAddressText;
    private TextView beaconGate;
    private TextView beaconKiosk;
    private TextView beaconElevator;
    private TextView beaconRoom;

    // Context display UI
    private TextView contextMode;
    private TextView contextConfidence;
    private TextView contextMotion;
    private TextView contextSpeed;

    private boolean isServiceRunning = false;

    // NFC/RFID reader — barrier validation path
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

    private final BroadcastReceiver beaconReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String beaconName = intent.getStringExtra("beaconName");
            int    rssi       = intent.getIntExtra("rssi", 0);
            updateBeaconDisplay(beaconName, rssi);
        }
    };

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

        // Debug panel views — only populated in debug builds
        debugDeviceMode  = findViewById(R.id.debugDeviceMode);
        debugSession     = findViewById(R.id.debugSession);
        debugBiometric   = findViewById(R.id.debugBiometric);
        debugAdvisory    = findViewById(R.id.debugAdvisory);
        debugNearStation = findViewById(R.id.debugNearStation);

        stopButton.setEnabled(false);
        displayIPAddress();
        startButton.setOnClickListener(v -> startScanService());
        stopButton.setOnClickListener(v -> stopScanService());

        // Transport Debug panel is always visible — shows MODE/SESSION/BIOMETRIC state.
        // Simulation toggles have been removed; simulation is controlled via advisory.
        View simSection = findViewById(R.id.simSection);
        simSection.setVisibility(View.VISIBLE);

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
        lbm.registerReceiver(beaconReceiver,         new IntentFilter("BEACON_UPDATE"));
        lbm.registerReceiver(contextReceiver,        new IntentFilter("CONTEXT_UPDATE"));
        lbm.registerReceiver(transportDebugReceiver, new IntentFilter("TRANSPORT_DEBUG_UPDATE"));
        lbm.registerReceiver(nfcEnableReceiver,      new IntentFilter("NFC_ENABLE"));
        registerBiometricCallback();

        // Handle biometric request from full-screen notification
        // (app brought to foreground from background or lock screen)
        Intent intent = getIntent();
        if (intent != null
                && BLEScanService.ACTION_BIOMETRIC_REQUEST.equals(intent.getAction())) {
            Log.d(TAG, "[BIOMETRIC] Launched from notification — launching prompt");
            // Consume the intent so rotation/resume doesn't re-trigger
            setIntent(new Intent());
            runOnUiThread(this::launchBiometricValidationPrompt);
        }
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
        // Keep biometric callback registered when going to background —
        // ValidationController will use postBiometricNotification() if callback
        // is null, but keeping it registered means foreground-adjacent cases
        // (e.g. notification shade pulled down) still work directly.
        // Callback is only cleared on full stop (onStop) to avoid leaking Activity.
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Null the callback now that Activity is fully stopped —
        // BLEScanService.postBiometricNotification() handles the background case.
        unregisterBiometricCallback();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerBiometricCallback();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (rfidNfcReader != null) rfidNfcReader.handleIntent(intent);
        // Handle biometric request when Activity is already running (singleTop)
        if (intent != null
                && BLEScanService.ACTION_BIOMETRIC_REQUEST.equals(intent.getAction())) {
            Log.d(TAG, "[BIOMETRIC] onNewIntent biometric request — launching prompt");
            runOnUiThread(this::launchBiometricValidationPrompt);
        }
    }

    // -------------------------------------------------------------------------
    // BiometricCallback — pre-journey check only (triggered by NetworkProximityMonitor)
    // NOT triggered at barrier. On success: records auth time only.
    // -------------------------------------------------------------------------

    @Override
    public void onBiometricRequired() {
        runOnUiThread(this::launchBiometricValidationPrompt);
    }

    private void launchBiometricValidationPrompt() {
        Log.d(TAG, "[BIOMETRIC] Launching identity validation prompt");
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                    super.onAuthenticationSucceeded(r);
                    onBiometricValidationSuccess();
                }
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Log.w(TAG, "[BIOMETRIC] Validation skipped: " + errString);
                }
                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Log.w(TAG, "[BIOMETRIC] Validation attempt failed");
                }
            });

        biometricPrompt.authenticate(
            new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Identity Verification")
                .setSubtitle("Verify your identity to complete journey validation")
                .setNegativeButtonText("Cancel")
                .build());
    }

    private void onBiometricValidationSuccess() {
        Log.d(TAG, "[BIOMETRIC] Identity validation succeeded");
        BLEScanService.recordBiometricAuthTime();
        if (isServiceRunning) {
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent("BIOMETRIC_SUCCESS"));
        }
        Toast.makeText(this, "Identity verified", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // NFC barrier validation path
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
    // Debug panel — Transport state diagnostic (debug builds only)
    // -------------------------------------------------------------------------

    private void updateTransportDebugPanel(String mode, boolean session,
                                           boolean bioFresh, boolean advisory,
                                           boolean nearStation) {
        runOnUiThread(() -> {
            if (debugDeviceMode  != null) debugDeviceMode.setText( "[MODE]        " + (mode != null ? mode : "--"));
            if (debugSession     != null) debugSession.setText(    "[SESSION]     " + (session     ? "ACTIVE"   : "IDLE"));
            if (debugBiometric   != null) debugBiometric.setText(  "[BIOMETRIC]   " + (bioFresh    ? "FRESH"    : "STALE"));
            if (debugAdvisory    != null) debugAdvisory.setText(   "[ADVISORY]    " + (advisory    ? "ACTIVE"   : "NONE"));
            if (debugNearStation != null) debugNearStation.setText("[AT STATION]  " + (nearStation ? "YES"      : "NO"));
        });
    }

    // -------------------------------------------------------------------------
    // Beacon + context display
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
    // Service control
    // -------------------------------------------------------------------------

    private void startScanService() {
        try {
            Log.d(TAG, "[MODE] Starting service...");
            ContextCompat.startForegroundService(this, new Intent(this, BLEScanService.class));
            isServiceRunning = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
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
    // Helpers
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
