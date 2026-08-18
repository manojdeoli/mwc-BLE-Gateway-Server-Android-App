package com.hotel.blescanner.insurance;

import com.hotel.blescanner.transport.BiometricManager;

/**
 * AssociationEvidenceProvider backed by BiometricManager freshness state.
 */
public class BiometricEvidenceProvider implements AssociationEvidenceProvider {

    private final InsuranceTelemetryEventFactory factory;

    public BiometricEvidenceProvider(InsuranceTelemetryEventFactory factory) {
        this.factory = factory;
    }

    @Override public String getProviderName() { return "BIOMETRIC"; }

    @Override
    public AssociationEvidence getEvidence() {
        BiometricFreshnessState state = factory.computeFreshnessState();
        boolean present = state == BiometricFreshnessState.FRESH
                       || state == BiometricFreshnessState.AGEING;
        return new AssociationEvidence(getProviderName(), present, "freshness=" + state.name());
    }
}
