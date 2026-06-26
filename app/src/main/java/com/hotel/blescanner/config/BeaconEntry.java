package com.hotel.blescanner.config;

import java.util.List;

/**
 * Represents a single beacon entry in the BeaconConfigManager.
 *
 * matchType = EXACT  : identifier must equal the advertised BLE device name exactly.
 * matchType = PREFIX : identifier is a prefix; device name must start with it.
 *                      If knownIdentifiers is populated, those are used for tight
 *                      ScanFilter generation (no broad scan). If absent, a broad
 *                      scan is used with in-callback prefix filtering.
 *
 * logicalName : canonical name used system-wide (UI, WebSocket events, barrier check).
 * zone        : explicit human-readable physical location. Never derived by heuristics.
 * isBarrier   : true = this beacon triggers barrier proximity evaluation in Transport mode.
 *
 * Gson deserialises this from JSON config. All fields are public for Gson compatibility.
 */
public class BeaconEntry {

    /** How to match the advertised device name against this entry. */
    public enum MatchType { EXACT, PREFIX }

    /** EXACT or PREFIX — determines matching strategy. */
    public MatchType   matchType;

    /**
     * The BLE device name or prefix to match.
     * EXACT: full advertised name, e.g. "ER26B00001" or "HotelGate".
     * PREFIX: prefix string, e.g. "ER26" matches "ER26B00001", "ER26B00099", etc.
     */
    public String      identifier;

    /**
     * Optional: known full identifiers that this PREFIX entry expands to.
     * When present, BeaconConfigManager generates one named ScanFilter per entry,
     * avoiding the battery cost of a broad scan (refinement 2.1).
     *
     * Example: identifier="ER26", knownIdentifiers=["ER26B00001","ER26B00002",...]
     * Ignored for EXACT entries.
     */
    public List<String> knownIdentifiers;

    /**
     * Canonical logical name used across the entire system.
     * All flows (UI, WebSocket events, barrier check) operate on this name,
     * never on the raw hardware identifier.
     */
    public String      logicalName;

    /**
     * Explicit zone description for this beacon.
     * Never derived by keyword heuristics — always explicitly set per entry.
     * Multiple entries sharing the same logicalName must agree on this value.
     */
    public String      zone;

    /**
     * True if proximity to this beacon should trigger barrier evaluation
     * in TRANSPORT mode. Replaces TransportConfig.getBarrierBeacons().
     */
    public boolean     isBarrier;

    /** No-arg constructor required by Gson. */
    public BeaconEntry() {}

    /** Convenience constructor for building the default config in code. */
    public BeaconEntry(MatchType matchType, String identifier,
                       String logicalName, String zone, boolean isBarrier) {
        this.matchType   = matchType;
        this.identifier  = identifier;
        this.logicalName = logicalName;
        this.zone        = zone;
        this.isBarrier   = isBarrier;
    }

    /** Returns true if all mandatory fields are populated. */
    public boolean isValid() {
        return matchType   != null && !matchType.name().isEmpty()
            && identifier  != null && !identifier.trim().isEmpty()
            && logicalName != null && !logicalName.trim().isEmpty()
            && zone        != null && !zone.trim().isEmpty();
    }
}
