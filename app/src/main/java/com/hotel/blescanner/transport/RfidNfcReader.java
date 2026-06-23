package com.hotel.blescanner.transport;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.util.Log;

/**
 * Manages NFC foreground dispatch for RFID card reading at transport barriers.
 *
 * Responsibilities:
 *   - Enable/disable NFC foreground dispatch scoped to a specific journeyId
 *   - Read the card UID from any supported ISO 14443 / ISO 15693 / FeliCa tag
 *   - Deliver the UID to the caller via {@link RfidResultCallback}
 *   - Gracefully fall through when NFC is unavailable — biometric path is unaffected
 *
 * Supported standards:
 *   IsoDep  — ISO 14443-4  (smart cards, modern access cards)
 *   NfcA    — ISO 14443-3A (MIFARE Classic, MIFARE Ultralight, DESFire)
 *   NfcB    — ISO 14443-3B (some transit / national ID cards)
 *   NfcF    — FeliCa       (Suica, Pasmo, Asian transit)
 *   NfcV    — ISO 15693    (vicinity cards, longer read range)
 *
 * Only the hardware UID is read via {@code tag.getId()}. No NDEF, APDU or
 * card-specific protocol commands are issued — the app is a pure UID relay.
 *
 * Thread safety:
 *   pendingJourneyId is volatile — written on main thread, read on main thread
 *   (NFC callbacks arrive on the main thread via Activity.onNewIntent).
 *   All methods must be called from the main thread.
 */
public class RfidNfcReader {

    private static final String TAG = "RfidNfcReader";

    /**
     * Callback interface delivered to the constructor.
     * Both methods are always called on the main thread.
     */
    public interface RfidResultCallback {
        /**
         * A card was successfully read.
         *
         * @param tagId    uppercase hex UID string, e.g. "A3F204BC"
         * @param journeyId the journey this read belongs to
         */
        void onTagRead(String tagId, String journeyId);

        /**
         * NFC is unavailable on this device or is currently disabled.
         * The caller should allow the biometric path to proceed independently.
         */
        void onNfcUnavailable();
    }

    // -------------------------------------------------------------------------
    // Tech list — covers all five supported standards
    // -------------------------------------------------------------------------

    private static final String[][] TECH_LIST = new String[][] {
        new String[] { IsoDep.class.getName() },
        new String[] { NfcA.class.getName()   },
        new String[] { NfcB.class.getName()   },
        new String[] { NfcF.class.getName()   },
        new String[] { NfcV.class.getName()   }
    };

    // -------------------------------------------------------------------------
    // Intent filters — cover all three NFC tag discovery actions
    // -------------------------------------------------------------------------

    private static final IntentFilter[] INTENT_FILTERS = new IntentFilter[] {
        new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
        new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
    };

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Activity           activity;
    private final RfidResultCallback callback;
    private final NfcAdapter         nfcAdapter;

    /**
     * Set when foreground dispatch is enabled, cleared when disabled.
     * Volatile: written and read on main thread, but also read from
     * handleIntent() which may arrive via onNewIntent on main thread.
     */
    private volatile String pendingJourneyId = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public RfidNfcReader(Activity activity, RfidResultCallback callback) {
        this.activity   = activity;
        this.callback   = callback;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enables NFC foreground dispatch scoped to the given journey.
     *
     * Must be called from the main thread (typically from onResume or a
     * runOnUiThread block). If NFC is unavailable or disabled,
     * {@link RfidResultCallback#onNfcUnavailable()} is called immediately
     * and the biometric path continues unaffected.
     *
     * @param journeyId identifier of the validation session, passed back in onTagRead
     */
    public void enableForegroundDispatch(String journeyId) {
        if (nfcAdapter == null) {
            Log.w(TAG, "[NFC] NfcAdapter not available on this device");
            callback.onNfcUnavailable();
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            Log.w(TAG, "[NFC] NFC is disabled in device settings");
            callback.onNfcUnavailable();
            return;
        }

        pendingJourneyId = journeyId;

        PendingIntent pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE   // required for NFC foreground dispatch on API 31+
        );

        try {
            nfcAdapter.enableForegroundDispatch(
                activity,
                pendingIntent,
                INTENT_FILTERS,
                TECH_LIST
            );
            Log.d(TAG, "[NFC] Foreground dispatch enabled for journey: " + journeyId);
        } catch (Exception e) {
            Log.e(TAG, "[NFC] Failed to enable foreground dispatch", e);
            pendingJourneyId = null;
            callback.onNfcUnavailable();
        }
    }

    /**
     * Disables NFC foreground dispatch and clears the pending journey.
     * Safe to call multiple times. Must be called from the main thread.
     */
    public void disableForegroundDispatch() {
        pendingJourneyId = null;
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.disableForegroundDispatch(activity);
            Log.d(TAG, "[NFC] Foreground dispatch disabled");
        } catch (Exception e) {
            Log.w(TAG, "[NFC] Error disabling foreground dispatch", e);
        }
    }

    /**
     * Handles an incoming NFC intent from {@code MainActivity.onNewIntent()}.
     *
     * Extracts the tag UID, converts it to an uppercase hex string and
     * delivers it to {@link RfidResultCallback#onTagRead}.
     *
     * Silently ignored if:
     *   - the intent action is not a tag discovery action
     *   - no pendingJourneyId is set (dispatch not enabled)
     *   - the tag has no UID (malformed/unreadable tag)
     */
    public void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (!NfcAdapter.ACTION_TAG_DISCOVERED .equals(action) &&
            !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action) &&
            !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return;
        }

        String journeyId = pendingJourneyId;
        if (journeyId == null) {
            Log.w(TAG, "[NFC] Tag detected but no pending journey — ignoring");
            return;
        }

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            Log.w(TAG, "[NFC] Tag intent received but EXTRA_TAG is null");
            return;
        }

        byte[] idBytes = tag.getId();
        if (idBytes == null || idBytes.length == 0) {
            Log.w(TAG, "[NFC] Tag has no UID — ignoring");
            return;
        }

        String tagId = bytesToHex(idBytes);
        Log.d(TAG, "[NFC] Tag read: uid=" + tagId + " journey=" + journeyId);
        callback.onTagRead(tagId, journeyId);
    }

    /**
     * Returns true if foreground dispatch is currently active.
     * Used by MainActivity to guard duplicate enable calls.
     */
    public boolean isDispatchEnabled() {
        return pendingJourneyId != null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a byte array to an uppercase hex string without separators.
     * e.g. [0xA3, 0xF2, 0x04, 0xBC] → "A3F204BC"
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
