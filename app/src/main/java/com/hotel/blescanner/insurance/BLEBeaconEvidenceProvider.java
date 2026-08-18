package com.hotel.blescanner.insurance;

/**
 * AssociationEvidenceProvider backed by VehicleAssociationController.
 * Reports current BLE beacon association state as evidence.
 */
public class BLEBeaconEvidenceProvider implements AssociationEvidenceProvider {

    private final VehicleAssociationController controller;

    public BLEBeaconEvidenceProvider(VehicleAssociationController controller) {
        this.controller = controller;
    }

    @Override public String getProviderName() { return "BLE_BEACON"; }

    @Override
    public AssociationEvidence getEvidence() {
        InsuranceSessionState state = controller.getState();
        boolean present = state == InsuranceSessionState.VEHICLE_ASSOCIATED
                       || state == InsuranceSessionState.ASSOCIATION_DEGRADED;
        return new AssociationEvidence(getProviderName(), present,
            "state=" + state.name() + " rssi=" + controller.getLastRssi());
    }
}
