package com.hotel.blescanner.config;

import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for all BLE beacon identification, mapping, and routing.
 *
 * Replaces five hardcoded locations:
 *   1. BLEScanService scan filter list
 *   2. BLEScanService.mapDeviceToDisplayName()
 *   3. GatewayServer.mapDeviceToZone()
 *   4. GatewayServer.isAllowedDevice()
 *   5. GatewayServer.mapBeaconToZone()
 *
 * Also replaces TransportConfig.getBarrierBeacons() for barrier identification
 * (TransportConfig.getBarrierBeacons() is deprecated but retained).
 *
 * Config loading priority:
 *   1. SharedPreferences (persisted from a previous updateConfigFromJson() call)
 *   2. Hardcoded default config (getDefaultConfig()) — always safe fallback
 *
 * Validation rules (refinement 2.3):
 *   Per-entry: identifier, logicalName, zone, matchType must all be non-null/non-empty.
 *   Post-parse: result must not be empty.
 *   Post-parse: at least one entry must have isBarrier=true.
 *   Post-parse: no two entries share a logicalName with different zone values (2.2).
 *
 * ScanFilter strategy (refinement 2.1 — three tiers):
 *   Tier 1: EXACT entries           → named ScanFilter per entry (tight, efficient).
 *   Tier 2: PREFIX + knownIdentifiers → expand to named ScanFilter per known ID.
 *   Tier 3: PREFIX, no knownIdentifiers → requiresBroadScan()=true, null filter list.
 *
 * Logging uses structured tags [CONFIG] and [MATCH] (refinement 2.6).
 * Config includes a version field for audit and rollback (refinement 2.7).
 */
public class BeaconConfigManager {

    private static final String TAG        = "BeaconConfigManager";
    private static final String T_CONFIG   = "[CONFIG]";
    private static final String T_MATCH    = "[MATCH]";

    private static final String PREFS_NAME  = "beacon_config";
    private static final String PREFS_JSON  = "beacon_config_json";
    private static final String PREFS_VER   = "beacon_config_version";

    private final SharedPreferences prefs;
    private final Gson              gson = new Gson();

    // -------------------------------------------------------------------------
    // Runtime lookup maps — rebuilt on every load/update (O(1) lookups)
    // -------------------------------------------------------------------------

    /** Raw identifier (exact) → BeaconEntry */
    private Map<String, BeaconEntry> exactMap    = new HashMap<>();

    /**
     * Prefix string → BeaconEntry. Iterated in insertion order so longer
     * prefixes are checked before shorter ones if both are present.
     */
    private Map<String, BeaconEntry> prefixMap   = new LinkedHashMap<>();

    /** logicalName → zone description */
    private Map<String, String>      zoneMap     = new HashMap<>();

    /** logicalNames that have isBarrier=true */
    private Set<String>              barrierSet  = new HashSet<>();

    /**
     * All raw identifiers that are "allowed" (known to the config).
     * Includes EXACT identifiers and all knownIdentifiers from PREFIX entries.
     * Used by isAllowedDevice().
     */
    private Set<String>              allowedRaw  = new HashSet<>();

    /** True if any PREFIX entry has no knownIdentifiers — broad scan required. */
    private boolean broadScanRequired = false;

    /** Version string from the last successfully loaded config. */
    private String loadedVersion = "default";

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public BeaconConfigManager(Context context) {
        this.prefs = (context != null)
            ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            : null;
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    /**
     * Loads config from SharedPreferences if present and valid.
     * Falls back to getDefaultConfig() on any failure.
     * Must be called once before any other method.
     */
    public void loadConfig() {
        if (prefs != null) {
            String json = prefs.getString(PREFS_JSON, null);
            if (json != null && !json.trim().isEmpty()) {
                Log.d(TAG, T_CONFIG + " Loading config from SharedPreferences");
                BeaconConfig parsed = parseAndValidate(json);
                if (parsed != null) {
                    buildLookupMaps(parsed);
                    loadedVersion = parsed.version != null ? parsed.version : "unknown";
                    Log.d(TAG, T_CONFIG + " Loaded remote config " + loadedVersion
                        + " (" + parsed.beacons.size() + " entries, "
                        + barrierSet.size() + " barriers)");
                    if (broadScanRequired) {
                        Log.w(TAG, T_CONFIG + " WARNING — broad BLE scan active"
                            + " (PREFIX entry without knownIdentifiers)");
                    }
                    return;
                }
                Log.w(TAG, T_CONFIG + " Stored config invalid — falling back to defaults");
            }
        }
        applyDefaultConfig();
    }

    // -------------------------------------------------------------------------
    // Primary API
    // -------------------------------------------------------------------------

    /**
     * Returns the ScanFilter list for BluetoothLeScanner.startScan().
     *
     * Tier 1: EXACT entries produce one named ScanFilter each.
     * Tier 2: PREFIX entries with knownIdentifiers are expanded to named filters.
     * Tier 3: Any PREFIX entry without knownIdentifiers → returns empty list
     *         (caller must pass null to startScan for broad scan).
     *
     * If requiresBroadScan() is true, the caller should pass null as filters
     * to BluetoothLeScanner.startScan() to capture all devices, then rely on
     * mapToLogicalName() in the callback to filter in software.
     */
    public List<ScanFilter> getScanFilters() {
        if (broadScanRequired) return Collections.emptyList();
        List<ScanFilter> result = new ArrayList<>();
        for (String id : allowedRaw) {
            result.add(new ScanFilter.Builder().setDeviceName(id).build());
        }
        return result;
    }

    /**
     * Returns true when any PREFIX entry has no knownIdentifiers, requiring
     * a broad BLE scan with in-callback filtering.
     * Caller should log a warning and pass null filters to startScan().
     */
    public boolean requiresBroadScan() {
        return broadScanRequired;
    }

    /**
     * Maps a raw advertised device name to its canonical logical name.
     *
     * Resolution order:
     *   1. exactMap lookup (O(1))
     *   2. prefixMap scan — deviceName.startsWith(prefix) (O(P), P = prefix count)
     *   3. Passthrough — returns deviceName unchanged if no match
     *
     * Never returns null.
     */
    public String mapToLogicalName(String deviceName) {
        if (deviceName == null) return "";

        BeaconEntry exact = exactMap.get(deviceName);
        if (exact != null) {
            Log.d(TAG, T_MATCH + " \"" + deviceName + "\" → \"" + exact.logicalName + "\" [EXACT]");
            return exact.logicalName;
        }

        for (Map.Entry<String, BeaconEntry> e : prefixMap.entrySet()) {
            if (deviceName.startsWith(e.getKey())) {
                Log.d(TAG, T_MATCH + " \"" + deviceName + "\" → \""
                    + e.getValue().logicalName + "\" [PREFIX:" + e.getKey() + "]");
                return e.getValue().logicalName;
            }
        }

        Log.d(TAG, T_MATCH + " \"" + deviceName + "\" → passthrough [NO_MATCH]");
        return deviceName;
    }

    /**
     * Returns the zone description for a logical name.
     * Returns empty string if no entry defines this logical name.
     */
    public String getZone(String logicalName) {
        if (logicalName == null) return "";
        String zone = zoneMap.get(logicalName);
        return zone != null ? zone : "";
    }

    /**
     * Returns true if the raw device name is known to this config.
     * Checks exactMap keys and all knownIdentifiers from PREFIX entries.
     * Used by GatewayServer to gate WebSocket broadcasts.
     */
    public boolean isAllowedDevice(String deviceName) {
        if (deviceName == null) return false;
        if (allowedRaw.contains(deviceName)) return true;
        // Also accept if it maps successfully (covers PREFIX without knownIdentifiers)
        if (broadScanRequired) {
            return !mapToLogicalName(deviceName).equals(deviceName);
        }
        return false;
    }

    /**
     * Returns true if the logical name belongs to a barrier beacon.
     * Replaces TransportConfig.getBarrierBeacons() — BeaconConfigManager is now
     * the single source of truth for barrier identity.
     */
    public boolean isBarrierBeacon(String logicalName) {
        return logicalName != null && barrierSet.contains(logicalName);
    }

    /** Returns the version string of the currently loaded config. */
    public String getLoadedVersion() {
        return loadedVersion;
    }

    // -------------------------------------------------------------------------
    // Config update (Phase 8 — remote config from backend)
    // -------------------------------------------------------------------------

    /**
     * Parses, validates and applies a new JSON config string.
     * On success: rebuilds lookup maps, persists to SharedPreferences, returns true.
     * On failure: keeps existing maps, logs [CONFIG] ERROR, returns false.
     *
     * The caller (BLEScanService) decides whether to restart the BLE scan after this.
     *
     * @param json raw JSON string, expected to match BeaconConfig schema
     * @return true if config was successfully applied
     */
    public boolean updateConfigFromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            Log.e(TAG, T_CONFIG + " updateConfigFromJson — null or empty JSON");
            return false;
        }
        BeaconConfig parsed = parseAndValidate(json);
        if (parsed == null) return false;

        buildLookupMaps(parsed);
        loadedVersion = parsed.version != null ? parsed.version : "unknown";

        if (prefs != null) {
            prefs.edit()
                .putString(PREFS_JSON,    json)
                .putString(PREFS_VER,     loadedVersion)
                .apply();
        }

        Log.d(TAG, T_CONFIG + " Loaded remote config " + loadedVersion
            + " (" + parsed.beacons.size() + " entries, "
            + barrierSet.size() + " barriers)");
        if (broadScanRequired) {
            Log.w(TAG, T_CONFIG + " WARNING — broad BLE scan active after config update");
        }
        return true;
    }

    /**
     * Clears the persisted config from SharedPreferences.
     * Next loadConfig() call will use the hardcoded default config.
     */
    public void resetToDefaults() {
        if (prefs != null) prefs.edit().remove(PREFS_JSON).remove(PREFS_VER).apply();
        applyDefaultConfig();
        Log.d(TAG, T_CONFIG + " Reset to default config");
    }

    // -------------------------------------------------------------------------
    // Default config — hardcoded fallback (Phase 3 / Phase 9)
    // -------------------------------------------------------------------------

    /**
     * Returns the hardcoded default beacon configuration.
     *
     * These values are identical to what was previously hardcoded in five places:
     *   BLEScanService scan filters, mapDeviceToDisplayName()
     *   GatewayServer mapDeviceToZone(), isAllowedDevice(), mapBeaconToZone()
     *
     * DO NOT REMOVE these entries — they are the fallback guarantee.
     */
    private BeaconConfig getDefaultConfig() {
        List<BeaconEntry> entries = new ArrayList<>();

        // Friendly logical names (Hotel deployment)
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "HotelGate",
            "HotelGate",     "Hotel Entry Gate", true));
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "HotelKiosk",
            "HotelKiosk",    "Check-in Kiosk",   false));
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "HotelElevator",
            "HotelElevator", "Elevator Lobby",   false));
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "HotelRoom",
            "HotelRoom",     "Room 1337",         false));

        // Hardware IDs → same logical names (Hotel deployment)
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "ER26B00001",
            "HotelGate",     "Hotel Entry Gate", true));
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "ER26B00002",
            "HotelKiosk",    "Check-in Kiosk",   false));
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "ER26B00003",
            "HotelElevator", "Elevator Lobby",   false));
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "ER26B00004",
            "HotelRoom",     "Room 1337",         false));

        // Alternative hardware ID → HotelGate
        entries.add(new BeaconEntry(BeaconEntry.MatchType.EXACT, "BCPro_212364",
            "HotelGate",     "Hotel Entry Gate", true));

        BeaconConfig config = new BeaconConfig();
        config.version = "default";
        config.beacons = entries;
        return config;
    }

    // -------------------------------------------------------------------------
    // Private — parse, validate, build
    // -------------------------------------------------------------------------

    /**
     * Parses JSON into a BeaconConfig and runs all validation checks.
     * Returns null on any failure (caller falls back to defaults).
     */
    private BeaconConfig parseAndValidate(String json) {
        BeaconConfig config;
        try {
            config = gson.fromJson(json, BeaconConfig.class);
        } catch (JsonSyntaxException e) {
            Log.e(TAG, T_CONFIG + " JSON parse failed: " + e.getMessage());
            return null;
        }

        if (config == null || config.beacons == null) {
            Log.e(TAG, T_CONFIG + " ERROR — null config or null beacons list");
            return null;
        }

        // --- Per-entry validation: remove invalid entries, keep rest ---
        List<BeaconEntry> valid = new ArrayList<>();
        for (BeaconEntry entry : config.beacons) {
            if (!entry.isValid()) {
                Log.w(TAG, T_CONFIG + " WARNING — skipping invalid entry: "
                    + "matchType=" + entry.matchType
                    + " id=" + entry.identifier
                    + " logical=" + entry.logicalName
                    + " zone=" + entry.zone);
            } else {
                valid.add(entry);
            }
        }
        config.beacons = valid;

        // --- Post-parse checks ---

        // 1. Must not be empty
        if (config.beacons.isEmpty()) {
            Log.e(TAG, T_CONFIG + " ERROR — empty config after validation → fallback");
            return null;
        }

        // 2. At least one barrier beacon (refinement 2.3)
        boolean hasBarrier = false;
        for (BeaconEntry e : config.beacons) {
            if (e.isBarrier) { hasBarrier = true; break; }
        }
        if (!hasBarrier) {
            Log.e(TAG, T_CONFIG + " ERROR — no barrier beacons in config → fallback");
            return null;
        }

        // 3. logicalName → zone consistency: same logicalName must have same zone (2.2)
        Map<String, String> logicalToZone = new HashMap<>();
        for (BeaconEntry e : config.beacons) {
            String existing = logicalToZone.get(e.logicalName);
            if (existing == null) {
                logicalToZone.put(e.logicalName, e.zone);
            } else if (!existing.equals(e.zone)) {
                Log.e(TAG, T_CONFIG + " ERROR — zone collision for logicalName \""
                    + e.logicalName + "\": [\"" + existing + "\", \"" + e.zone + "\"] → fallback");
                return null;
            }
        }

        return config;
    }

    /**
     * Builds all O(1) lookup maps from a validated BeaconConfig.
     * Completely replaces previous maps — atomic from caller's perspective
     * since all maps are replaced together before any lookup occurs.
     */
    private void buildLookupMaps(BeaconConfig config) {
        Map<String, BeaconEntry> newExact    = new HashMap<>();
        Map<String, BeaconEntry> newPrefix   = new LinkedHashMap<>();
        Map<String, String>      newZone     = new HashMap<>();
        Set<String>              newBarrier  = new HashSet<>();
        Set<String>              newAllowed  = new HashSet<>();
        boolean                  needsBroad  = false;

        for (BeaconEntry entry : config.beacons) {
            if (entry.matchType == BeaconEntry.MatchType.EXACT) {
                newExact.put(entry.identifier, entry);
                newAllowed.add(entry.identifier);
            } else {
                // PREFIX
                newPrefix.put(entry.identifier, entry);
                if (entry.knownIdentifiers != null && !entry.knownIdentifiers.isEmpty()) {
                    // Expand to known IDs for tight scan filtering (refinement 2.1 tier 2)
                    newAllowed.addAll(entry.knownIdentifiers);
                } else {
                    // No known IDs — broad scan required (refinement 2.1 tier 3)
                    needsBroad = true;
                }
            }

            // Zone map — same logicalName always has same zone (validated above)
            newZone.put(entry.logicalName, entry.zone);

            if (entry.isBarrier) {
                newBarrier.add(entry.logicalName);
            }
        }

        // Atomic replacement
        this.exactMap          = newExact;
        this.prefixMap         = newPrefix;
        this.zoneMap           = newZone;
        this.barrierSet        = newBarrier;
        this.allowedRaw        = newAllowed;
        this.broadScanRequired = needsBroad;
    }

    private void applyDefaultConfig() {
        BeaconConfig def = getDefaultConfig();
        buildLookupMaps(def);
        loadedVersion = def.version;
        Log.d(TAG, T_CONFIG + " Loaded default config ("
            + def.beacons.size() + " entries, "
            + barrierSet.size() + " barriers)");
    }

    // -------------------------------------------------------------------------
    // JSON root model (refinement 2.7 — version field)
    // -------------------------------------------------------------------------

    /**
     * Root JSON object for beacon configuration.
     *
     * Format:
     * {
     *   "version": "v1",
     *   "beacons": [
     *     {
     *       "matchType": "EXACT",
     *       "identifier": "ER26B00001",
     *       "logicalName": "StationGate",
     *       "zone": "Station Entry Gate",
     *       "isBarrier": true
     *     },
     *     {
     *       "matchType": "PREFIX",
     *       "identifier": "ER26",
     *       "knownIdentifiers": ["ER26B00001","ER26B00002","ER26B00003","ER26B00004"],
     *       "logicalName": "StationGate",
     *       "zone": "Station Entry Gate",
     *       "isBarrier": true
     *     }
     *   ]
     * }
     */
    public static class BeaconConfig {
        /** Version string for audit, logging, and rollback tracking (2.7). */
        public String           version;
        public List<BeaconEntry> beacons;
    }
}
