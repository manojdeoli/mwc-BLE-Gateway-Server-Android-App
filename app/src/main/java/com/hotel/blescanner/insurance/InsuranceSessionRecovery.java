package com.hotel.blescanner.insurance;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persists the absolute minimum metadata needed for safe restart recovery.
 *
 * GAP #2 / GAP #13 — session state is NOT persisted here.
 * Only the three fields below survive a process kill:
 *
 *   lastKnownSessionId       — prevents duplicate session IDs on restart
 *   lastInitialEventSentFlag — prevents duplicate initial events on restart
 *   lastSessionCreationTime  — allows staleness check on restart
 *
 * All other session state (timers, retry counters, beacon history, event IDs)
 * is transient and must be rebuilt from live BLE observations after restart.
 *
 * On restart:
 *   1. Read recovery data.
 *   2. If lastInitialEventSentFlag=true and session is < 24h old, reuse sessionId
 *      and set isInitialEventSent=true to prevent duplicate initial event.
 *   3. Otherwise start fresh.
 *   4. Clear recovery data after it has been consumed.
 */
public class InsuranceSessionRecovery {

    private static final String TAG        = "[INS] SessionRecovery";
    private static final String PREFS_NAME = "insurance_session_recovery";

    // Keys — intentionally minimal
    private static final String KEY_SESSION_ID          = "lastKnownSessionId";
    private static final String KEY_INITIAL_SENT        = "lastInitialEventSentFlag";
    private static final String KEY_SESSION_CREATED     = "lastSessionCreationTime";

    /** Sessions older than this are considered stale and not recovered. */
    private static final long   MAX_RECOVERY_AGE_MS     = 24 * 60 * 60 * 1000L; // 24 hours

    private final SharedPreferences prefs;

    public InsuranceSessionRecovery(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Persists recovery metadata after a session is created and initial event sent. */
    public void save(String sessionId, boolean initialEventSent, long sessionCreationTimeMs) {
        prefs.edit()
            .putString(KEY_SESSION_ID,      sessionId)
            .putBoolean(KEY_INITIAL_SENT,   initialEventSent)
            .putLong(KEY_SESSION_CREATED,   sessionCreationTimeMs)
            .apply();
        Log.d(TAG, "Recovery metadata saved: session=" + InsuranceTelemetryEventFactory.maskId(sessionId));
    }

    /**
     * Attempts to recover a previous session.
     * Returns null if no valid recovery data exists or data is stale.
     */
    public RecoveryData tryRecover() {
        String  sessionId    = prefs.getString(KEY_SESSION_ID, null);
        boolean initialSent  = prefs.getBoolean(KEY_INITIAL_SENT, false);
        long    createdAt    = prefs.getLong(KEY_SESSION_CREATED, 0L);

        if (sessionId == null || createdAt == 0L) return null;

        long ageMs = System.currentTimeMillis() - createdAt;
        if (ageMs > MAX_RECOVERY_AGE_MS) {
            Log.d(TAG, "Recovery data stale (" + (ageMs / 3600_000) + "h) — discarding");
            clear();
            return null;
        }

        Log.d(TAG, "Recovery data found: session=" + InsuranceTelemetryEventFactory.maskId(sessionId)
            + " initialSent=" + initialSent + " ageMs=" + ageMs);
        return new RecoveryData(sessionId, initialSent, createdAt);
    }

    /** Clears recovery data after it has been consumed or on clean stop. */
    public void clear() {
        prefs.edit().clear().apply();
        Log.d(TAG, "Recovery metadata cleared");
    }

    public static class RecoveryData {
        public final String  sessionId;
        public final boolean initialEventSent;
        public final long    sessionCreationTimeMs;

        RecoveryData(String sessionId, boolean initialEventSent, long sessionCreationTimeMs) {
            this.sessionId            = sessionId;
            this.initialEventSent     = initialEventSent;
            this.sessionCreationTimeMs = sessionCreationTimeMs;
        }
    }
}
