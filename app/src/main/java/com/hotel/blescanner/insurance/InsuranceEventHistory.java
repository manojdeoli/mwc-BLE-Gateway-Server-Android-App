package com.hotel.blescanner.insurance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Bounded in-memory event timeline for INSURANCE mode diagnostics.
 *
 * Stores the last 50 lifecycle events. Memory only — never persisted.
 * Thread-safe via synchronized access on the deque.
 *
 * Consumed by /health, insuranceStatus, and debug UI.
 */
public class InsuranceEventHistory {

    private static final int MAX_EVENTS = 50;

    public enum HistoryEventType {
        MODE_ACTIVATED,
        VEHICLE_CANDIDATE,
        ASSOCIATION_CONFIRMED,
        INITIAL_EVENT_SENT,
        AUTH_CHANGED,
        GPS_LOST,
        BEACON_LOST,
        SESSION_ENDED,
        RETRY_TRIGGERED,
        QUEUE_OVERFLOW
    }

    public static class Entry {
        public final HistoryEventType type;
        public final String           detail;
        public final long             timestampMs;

        Entry(HistoryEventType type, String detail) {
            this.type        = type;
            this.detail      = detail;
            this.timestampMs = System.currentTimeMillis();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event",     type.name());
            m.put("detail",    detail);
            m.put("timestamp", InsuranceTelemetryEventFactory.toIso8601(timestampMs));
            return m;
        }
    }

    private final Deque<Entry> history = new ArrayDeque<>(MAX_EVENTS);

    public void record(HistoryEventType type, String detail) {
        synchronized (history) {
            if (history.size() >= MAX_EVENTS) history.pollFirst();
            history.addLast(new Entry(type, detail));
        }
    }

    /** Returns a snapshot list (newest last) for serialisation. */
    public List<Map<String, Object>> toList() {
        synchronized (history) {
            List<Map<String, Object>> out = new ArrayList<>(history.size());
            for (Entry e : history) out.add(e.toMap());
            return out;
        }
    }

    public void clear() {
        synchronized (history) { history.clear(); }
    }
}
