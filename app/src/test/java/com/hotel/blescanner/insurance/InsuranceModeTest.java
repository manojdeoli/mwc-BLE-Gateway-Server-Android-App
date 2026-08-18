package com.hotel.blescanner.insurance;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for INSURANCE mode hardening (GAP #14).
 *
 * Tests run on the JVM — no Android framework required.
 * Components under test: VehicleAssociationController, InsuranceBackendMonitor,
 * InsuranceEventHistory, InsuranceSessionRecovery (logic only),
 * QueueOverflowPolicy selection logic.
 */
public class InsuranceModeTest {

    // -------------------------------------------------------------------------
    // Minimal stub InsuranceConfig (no SharedPreferences needed for unit tests)
    // -------------------------------------------------------------------------

    private static InsuranceConfig stubConfig() {
        return new InsuranceConfig(null);  // null context → uses compile-time defaults
    }

    // =========================================================================
    // GAP #1 — Session must not start on mode activation
    // =========================================================================

    @Test
    public void testInsuranceSessionState_hasWaitingForVehicle() {
        // WAITING_FOR_VEHICLE must exist in the enum
        InsuranceSessionState state = InsuranceSessionState.WAITING_FOR_VEHICLE;
        assertNotNull(state);
    }

    @Test
    public void testVehicleController_startsIdle() {
        VehicleAssociationController ctrl = new VehicleAssociationController(stubConfig());
        assertEquals(InsuranceSessionState.IDLE, ctrl.getState());
        assertFalse(ctrl.isBeaconDetected());
    }

    // =========================================================================
    // GAP #1 — Vehicle-association-driven session creation
    // =========================================================================

    @Test
    public void testVehicleController_candidateOnFirstBeacon() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);

        ctrl.onBeaconDetected(cfg.getRegisteredVehicleBeaconId(), -60);

        assertEquals(InsuranceSessionState.CANDIDATE_VEHICLE_DETECTED, ctrl.getState());
        assertFalse(ctrl.isBeaconDetected());  // not yet associated
    }

    @Test
    public void testVehicleController_associatedAfterMinAdvCount() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        for (int i = 0; i < cfg.getMinAdvCount(); i++) {
            ctrl.onBeaconDetected(beaconId, -60);
        }

        assertEquals(InsuranceSessionState.VEHICLE_ASSOCIATED, ctrl.getState());
        assertTrue(ctrl.isBeaconDetected());
    }

    @Test
    public void testVehicleController_weakRssiIgnored() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);

        // RSSI weaker than threshold — should be ignored
        ctrl.onBeaconDetected(cfg.getRegisteredVehicleBeaconId(), cfg.getMinRssi() - 1);

        assertEquals(InsuranceSessionState.IDLE, ctrl.getState());
    }

    @Test
    public void testVehicleController_wrongBeaconIgnored() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);

        ctrl.onBeaconDetected("WRONG-BEACON-ID", -60);

        assertEquals(InsuranceSessionState.IDLE, ctrl.getState());
    }

    @Test
    public void testVehicleController_degradedOnAbsenceWithinGrace() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);
        assertEquals(InsuranceSessionState.VEHICLE_ASSOCIATED, ctrl.getState());

        // Absent for less than grace period
        ctrl.onBeaconAbsent(cfg.getBeaconLossGraceMs() - 1000);
        assertEquals(InsuranceSessionState.ASSOCIATION_DEGRADED, ctrl.getState());
    }

    @Test
    public void testVehicleController_disconnectedOnAbsenceBeyondGrace() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);
        ctrl.onBeaconAbsent(cfg.getBeaconLossGraceMs() + 5000);

        assertEquals(InsuranceSessionState.VEHICLE_DISCONNECTED, ctrl.getState());
    }

    @Test
    public void testVehicleController_restoredFromDegraded() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);
        ctrl.onBeaconAbsent(cfg.getBeaconLossGraceMs() - 1000);
        assertEquals(InsuranceSessionState.ASSOCIATION_DEGRADED, ctrl.getState());

        ctrl.onBeaconDetected(beaconId, -55);
        assertEquals(InsuranceSessionState.VEHICLE_ASSOCIATED, ctrl.getState());
    }

    @Test
    public void testVehicleController_resetClearsAllState() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);
        ctrl.reset();

        assertEquals(InsuranceSessionState.IDLE, ctrl.getState());
        assertFalse(ctrl.isBeaconDetected());
        assertEquals(0, ctrl.getAdvertisementCount());
    }

    // =========================================================================
    // GAP #3 — Backend reachability monitor
    // =========================================================================

    @Test
    public void testBackendMonitor_startsUnknown() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        assertEquals(InsuranceBackendMonitor.ReachabilityState.UNKNOWN, monitor.getState());
    }

    @Test
    public void testBackendMonitor_availableAfterSuccess() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        monitor.onPublishSuccess();
        assertEquals(InsuranceBackendMonitor.ReachabilityState.AVAILABLE, monitor.getState());
    }

    @Test
    public void testBackendMonitor_degradedAfterOneFailure() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        monitor.onPublishFailure("TIMEOUT");
        assertEquals(InsuranceBackendMonitor.ReachabilityState.DEGRADED, monitor.getState());
    }

    @Test
    public void testBackendMonitor_unreachableAfterThreeFailures() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        monitor.onPublishFailure("TIMEOUT");
        monitor.onPublishFailure("TIMEOUT");
        monitor.onPublishFailure("TIMEOUT");
        assertEquals(InsuranceBackendMonitor.ReachabilityState.UNREACHABLE, monitor.getState());
    }

    @Test
    public void testBackendMonitor_recoversToAvailableAfterSuccess() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        monitor.onPublishFailure("TIMEOUT");
        monitor.onPublishFailure("TIMEOUT");
        monitor.onPublishSuccess();
        assertEquals(InsuranceBackendMonitor.ReachabilityState.AVAILABLE, monitor.getState());
    }

    @Test
    public void testBackendMonitor_healthBlockContainsReachability() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        monitor.onPublishSuccess();
        java.util.Map<String, Object> block = monitor.toHealthBlock();
        assertTrue(block.containsKey("backendReachability"));
        assertEquals("AVAILABLE", block.get("backendReachability"));
        assertTrue(block.containsKey("lastSuccessfulPublish"));
    }

    @Test
    public void testBackendMonitor_resetRestoresUnknown() {
        InsuranceBackendMonitor monitor = new InsuranceBackendMonitor();
        monitor.onPublishSuccess();
        monitor.reset();
        assertEquals(InsuranceBackendMonitor.ReachabilityState.UNKNOWN, monitor.getState());
    }

    // =========================================================================
    // GAP #4 — Queue overflow policy enum
    // =========================================================================

    @Test
    public void testQueueOverflowPolicy_allValuesExist() {
        assertNotNull(QueueOverflowPolicy.DROP_OLDEST);
        assertNotNull(QueueOverflowPolicy.DROP_NEWEST);
        assertNotNull(QueueOverflowPolicy.KEEP_LATEST_STATE);
    }

    // =========================================================================
    // GAP #5 — Association evidence diagnostics
    // =========================================================================

    @Test
    public void testVehicleController_advertisementCountTracked() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        ctrl.onBeaconDetected(beaconId, -60);
        ctrl.onBeaconDetected(beaconId, -62);

        assertTrue(ctrl.getAdvertisementCount() >= 1);
    }

    @Test
    public void testVehicleController_averageRssiComputed() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        ctrl.onBeaconDetected(beaconId, -60);
        ctrl.onBeaconDetected(beaconId, -70);

        // Average should be between -70 and -60
        double avg = ctrl.getAverageRssi();
        assertTrue(avg <= -60 && avg >= -70);
    }

    @Test
    public void testVehicleController_associationDurationZeroWhenNotAssociated() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        assertEquals(0L, ctrl.getAssociationDurationMs());
    }

    @Test
    public void testVehicleController_associationDurationPositiveWhenAssociated() throws InterruptedException {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);
        Thread.sleep(50);

        assertTrue(ctrl.getAssociationDurationMs() > 0);
    }

    // =========================================================================
    // GAP #6 — AssociationEvidenceSnapshot
    // =========================================================================

    @Test
    public void testAssociationEvidenceSnapshot_constructsCorrectly() {
        AssociationEvidenceSnapshot snap = new AssociationEvidenceSnapshot(
            BiometricFreshnessState.FRESH, 60_000L,
            InsuranceSessionState.VEHICLE_ASSOCIATED,
            5, 2, -63.0, 420_000L, System.currentTimeMillis(),
            true, true, "GPS", System.currentTimeMillis(),
            InsuranceSessionState.VEHICLE_ASSOCIATED);

        assertEquals(BiometricFreshnessState.FRESH, snap.biometricState);
        assertEquals(5, snap.advertisementCount);
        assertEquals(-63.0, snap.averageRssi, 0.01);
        assertEquals("GPS", snap.speedSource);
        assertTrue(snap.locationAvailable);
    }

    // =========================================================================
    // GAP #9 — Scan profile observability
    // =========================================================================

    @Test
    public void testVehicleController_scanProfileUpdated() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);

        ctrl.setScanProfile("VEHICLE_ASSOCIATED", "Association confirmed");

        assertEquals("VEHICLE_ASSOCIATED", ctrl.getCurrentScanProfile());
        assertEquals("Association confirmed", ctrl.getScanTransitionReason());
        assertTrue(ctrl.getScanProfileStartMs() > 0);
    }

    // =========================================================================
    // GAP #11 — Event history
    // =========================================================================

    @Test
    public void testEventHistory_recordsAndRetrieves() {
        InsuranceEventHistory history = new InsuranceEventHistory();
        history.record(InsuranceEventHistory.HistoryEventType.MODE_ACTIVATED, "test");
        history.record(InsuranceEventHistory.HistoryEventType.ASSOCIATION_CONFIRMED, "rssi=-60");

        java.util.List<java.util.Map<String, Object>> list = history.toList();
        assertEquals(2, list.size());
        assertEquals("MODE_ACTIVATED", list.get(0).get("event"));
        assertEquals("ASSOCIATION_CONFIRMED", list.get(1).get("event"));
    }

    @Test
    public void testEventHistory_boundedAt50() {
        InsuranceEventHistory history = new InsuranceEventHistory();
        for (int i = 0; i < 60; i++) {
            history.record(InsuranceEventHistory.HistoryEventType.RETRY_TRIGGERED, "i=" + i);
        }
        assertEquals(50, history.toList().size());
    }

    @Test
    public void testEventHistory_clearWorks() {
        InsuranceEventHistory history = new InsuranceEventHistory();
        history.record(InsuranceEventHistory.HistoryEventType.MODE_ACTIVATED, "test");
        history.clear();
        assertEquals(0, history.toList().size());
    }

    @Test
    public void testEventHistory_entryHasTimestamp() {
        InsuranceEventHistory history = new InsuranceEventHistory();
        history.record(InsuranceEventHistory.HistoryEventType.GPS_LOST, "provider disabled");
        java.util.Map<String, Object> entry = history.toList().get(0);
        assertNotNull(entry.get("timestamp"));
        assertNotNull(entry.get("detail"));
    }

    // =========================================================================
    // GAP #12 — Extension points
    // =========================================================================

    @Test
    public void testAssociationEvidenceProvider_bleBeaconProvider() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        BLEBeaconEvidenceProvider provider = new BLEBeaconEvidenceProvider(ctrl);

        assertEquals("BLE_BEACON", provider.getProviderName());
        AssociationEvidenceProvider.AssociationEvidence evidence = provider.getEvidence();
        assertNotNull(evidence);
        assertFalse(evidence.evidencePresent);  // not yet associated
    }

    @Test
    public void testAssociationEvidenceProvider_bleBeaconPresentWhenAssociated() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();
        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);

        BLEBeaconEvidenceProvider provider = new BLEBeaconEvidenceProvider(ctrl);
        assertTrue(provider.getEvidence().evidencePresent);
    }

    // =========================================================================
    // GAP #13 — Restart recovery (logic only — no SharedPreferences)
    // =========================================================================

    @Test
    public void testSessionRecoveryData_constructsCorrectly() {
        InsuranceSessionRecovery.RecoveryData data =
            new InsuranceSessionRecovery.RecoveryData("session-123", true, 1_000_000L);

        assertEquals("session-123", data.sessionId);
        assertTrue(data.initialEventSent);
        assertEquals(1_000_000L, data.sessionCreationTimeMs);
    }

    // =========================================================================
    // GAP #7 — Speed source validation
    // =========================================================================

    @Test
    public void testInsuranceSessionState_allStatesPresent() {
        // Verify all required states from the spec exist
        InsuranceSessionState[] states = InsuranceSessionState.values();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (InsuranceSessionState s : states) names.add(s.name());

        assertTrue(names.contains("IDLE"));
        assertTrue(names.contains("WAITING_FOR_VEHICLE"));
        assertTrue(names.contains("CANDIDATE_VEHICLE_DETECTED"));
        assertTrue(names.contains("VEHICLE_ASSOCIATED"));
        assertTrue(names.contains("ASSOCIATION_DEGRADED"));
        assertTrue(names.contains("VEHICLE_DISCONNECTED"));
        assertTrue(names.contains("SESSION_ENDED"));
    }

    // =========================================================================
    // Duplicate session / event guard
    // =========================================================================

    @Test
    public void testVehicleController_listenerCalledOnTransition() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        final int[] callCount = {0};
        ctrl.setListener((newState, prevState, snapshot) -> callCount[0]++);

        ctrl.onBeaconDetected(beaconId, -60);  // IDLE → CANDIDATE
        assertEquals(1, callCount[0]);
    }

    @Test
    public void testVehicleController_noListenerCallWhenSameState() {
        InsuranceConfig cfg = stubConfig();
        VehicleAssociationController ctrl = new VehicleAssociationController(cfg);
        String beaconId = cfg.getRegisteredVehicleBeaconId();

        // Associate first
        for (int i = 0; i < cfg.getMinAdvCount(); i++) ctrl.onBeaconDetected(beaconId, -60);

        final int[] callCount = {0};
        ctrl.setListener((newState, prevState, snapshot) -> callCount[0]++);

        // Heartbeat — same state, no transition
        ctrl.onBeaconDetected(beaconId, -58);
        assertEquals(0, callCount[0]);
    }
}
