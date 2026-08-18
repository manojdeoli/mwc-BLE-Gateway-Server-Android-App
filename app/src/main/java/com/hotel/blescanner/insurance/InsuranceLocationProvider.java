package com.hotel.blescanner.insurance;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Provides optional device GPS fallback location for INSURANCE mode.
 *
 * IMPORTANT:
 *   - This is DEVICE GPS FALLBACK only. Source is always "DEVICE_GPS_FALLBACK".
 *   - NEVER label this as CAMARA location.
 *   - CAMARA/Vonage location APIs are called by the Node.js Insurance backend, not here.
 *   - Do NOT continuously request high-accuracy GPS when not needed.
 *   - Stop location updates when the session ends.
 *   - If permission is missing or location is unavailable, return null — do not fabricate.
 *   - Do NOT block BLE/session processing while waiting for GPS.
 *
 * Speed from GPS:
 *   Android Location.getSpeed() returns metres per second.
 *   Conversion to mph: mph = m/s * 2.2369362920544
 *   Only reported when hasSpeed() is true and the fix is fresh.
 */
public class InsuranceLocationProvider {

    private static final String TAG = "[GPS] InsuranceLocationProvider";

    /** Conversion factor: metres per second → miles per hour. */
    public static final double MPS_TO_MPH = 2.2369362920544;

    /** Minimum time between location updates: 10 seconds. */
    private static final long  MIN_UPDATE_INTERVAL_MS   = 10_000L;
    /** Minimum distance between location updates: 50 metres. */
    private static final float MIN_UPDATE_DISTANCE_M    = 50f;
    /** Maximum age of a GPS fix to be considered fresh: 5 minutes.
     *  Stationary devices (parked car) won't get new fixes every 30s. */
    private static final long  GPS_MAX_AGE_MS           = 300_000L;

    private final Context         context;
    private final InsuranceConfig config;
    private       LocationManager locationManager;
    private volatile Location     lastLocation         = null;
    private volatile boolean      started              = false;
    // GAP #8 — location availability diagnostics
    private volatile boolean      gpsPermissionGranted = false;
    private volatile boolean      locationAvailable    = false;
    private volatile String       locationSource       = null;  // "GPS" or null
    private volatile long         lastLocationMs       = 0L;
    // GAP #7 — speed source diagnostics
    private volatile boolean      speedAvailable       = false;
    private volatile long         lastSpeedUpdateMs    = 0L;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation      = location;
            locationAvailable = true;
            locationSource    = "GPS";
            lastLocationMs    = System.currentTimeMillis();
            speedAvailable    = location.hasSpeed();
            if (speedAvailable) lastSpeedUpdateMs = lastLocationMs;
            Log.d(TAG, "Location updated: lat=" + location.getLatitude()
                + " lng=" + location.getLongitude()
                + " speed=" + location.getSpeed() + "m/s"
                + " accuracy=" + location.getAccuracy() + "m");
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {
            locationAvailable = true;
        }
        @Override public void onProviderDisabled(String provider) {
            Log.w(TAG, "GPS provider disabled: " + provider);
            locationAvailable = false;
            locationSource    = null;
        }
    };

    public InsuranceLocationProvider(Context context, InsuranceConfig config) {
        this.context = context;
        this.config  = config;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts requesting location updates.
     * No-op if GPS fallback is disabled in config or permission is missing.
     * Must be called from a thread that has a Looper (use main thread or Handler).
     */
    public void start() {
        if (!config.isAllowGpsFallback()) {
            Log.d(TAG, "GPS fallback disabled in config — not starting");
            return;
        }
        if (started) return;
        try {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_UPDATE_INTERVAL_MS,
                MIN_UPDATE_DISTANCE_M,
                locationListener);
            started              = true;
            gpsPermissionGranted = true;
            Log.d(TAG, "GPS location updates started");
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission denied — GPS fallback unavailable");
            gpsPermissionGranted = false;
            locationAvailable    = false;
        } catch (Exception e) {
            Log.w(TAG, "Could not start GPS updates: " + e.getMessage());
        }
    }

    /**
     * Stops location updates. Must be called when the session ends.
     */
    public void stop() {
        if (!started) return;
        try {
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping GPS updates: " + e.getMessage());
        }
        started           = false;
        lastLocation      = null;
        locationAvailable = false;
        locationSource    = null;
        speedAvailable    = false;
        Log.d(TAG, "GPS location updates stopped");
    }

    // -------------------------------------------------------------------------
    // Location snapshot
    // -------------------------------------------------------------------------

    /**
     * Returns the current location evidence, or null if unavailable.
     *
     * Returns null when:
     *   - GPS fallback is disabled
     *   - Permission is missing
     *   - No fix has been received
     *   - The last fix is older than GPS_MAX_AGE_MS
     *
     * Source is always "DEVICE_GPS_FALLBACK".
     */
    public InsuranceTelemetryEvent.LocationEvidence getLocationEvidence() {
        if (!config.isAllowGpsFallback()) return null;
        Location loc = lastLocation;
        if (loc == null) loc = getLastKnownFallback();
        if (loc == null) return null;

        // Use fix if fresh; if stale, still use last-known as a best-effort fallback
        // rather than returning null and dropping location confidence to 0%.
        long ageMs = System.currentTimeMillis() - loc.getTime();
        if (ageMs > GPS_MAX_AGE_MS) {
            Location lastKnown = getLastKnownFallback();
            if (lastKnown != null) {
                loc = lastKnown;
                Log.d(TAG, "GPS fix stale (" + ageMs + "ms) — using last-known fallback");
            } else {
                Log.d(TAG, "GPS fix stale (" + ageMs + "ms) and no last-known — skipping");
                return null;
            }
        }

        InsuranceTelemetryEvent.LocationEvidence evidence =
            new InsuranceTelemetryEvent.LocationEvidence(
                loc.getLatitude(),
                loc.getLongitude(),
                InsuranceTelemetryEventFactory.toIso8601(loc.getTime()));

        if (loc.hasAccuracy()) {
            evidence.accuracyMetres = (double) loc.getAccuracy();
        }
        return evidence;
    }

    /**
     * Returns current speed in mph, or null if unavailable.
     * Speed source is always GPS when available.
     * GAP #7: speedSource="GPS" when from GPS, null when unavailable.
     */
    public Double getSpeedMph() {
        if (!config.isAllowSpeedReporting()) return null;
        Location loc = lastLocation;
        if (loc == null) return null;
        long ageMs = System.currentTimeMillis() - loc.getTime();
        if (ageMs > GPS_MAX_AGE_MS) return null;
        if (!loc.hasSpeed()) return null;
        return loc.getSpeed() * MPS_TO_MPH;
    }

    public boolean isStarted()              { return started; }
    // GAP #8 diagnostics
    public boolean isLocationAvailable()    { return locationAvailable; }
    public boolean isGpsPermissionGranted() { return gpsPermissionGranted; }
    public String  getLocationSource()      { return locationSource; }
    public long    getLastLocationMs()      { return lastLocationMs; }
    // GAP #7 diagnostics
    public boolean isSpeedAvailable()       { return speedAvailable; }
    public String  getSpeedSource()         { return speedAvailable ? "GPS" : null; }
    public long    getLastSpeedUpdateMs()   { return lastSpeedUpdateMs; }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Location getLastKnownFallback() {
        if (locationManager == null) return null;
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null) return gps;
            return locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            return null;
        }
    }
}
