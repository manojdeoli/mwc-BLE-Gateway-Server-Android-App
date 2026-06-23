package com.hotel.blescanner.transport;

/**
 * Callback interface that decouples the service-side validation logic from the
 * Activity-side biometric UI.
 *
 * Implementation contract:
 *   - MainActivity implements this interface.
 *   - MainActivity registers itself via ValidationController.setBiometricCallback()
 *     in onResume() and unregisters in onPause().
 *   - When onBiometricRequired() is called, MainActivity launches BiometricPrompt.
 *   - On successful authentication, MainActivity calls
 *     BiometricManager.recordAuthTime() and ValidationController.onBiometricSuccess().
 *
 * The service never holds an Activity reference directly — only this interface,
 * which is nulled when the Activity leaves the foreground (Fix 3.4).
 */
public interface BiometricCallback {
    /**
     * Invoked when the validation flow requires biometric authentication.
     * Always called on the validation scheduler thread — implementations
     * must post to the main thread before launching BiometricPrompt.
     */
    void onBiometricRequired();
}
