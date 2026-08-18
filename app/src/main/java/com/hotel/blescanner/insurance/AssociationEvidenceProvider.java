package com.hotel.blescanner.insurance;

/**
 * Extension point for future association evidence providers.
 *
 * GAP #12 — pluggable evidence without touching session lifecycle.
 *
 * DO NOT implement confidence scoring here.
 * DO NOT implement pricing logic here.
 * The backend remains the confidence authority.
 *
 * Initial providers: BLEBeaconEvidenceProvider, BiometricEvidenceProvider,
 * LocationEvidenceProvider.
 */
public interface AssociationEvidenceProvider {

    /** Human-readable provider name for diagnostics. */
    String getProviderName();

    /**
     * Returns the current evidence snapshot from this provider.
     * Must be non-blocking and return quickly (called from diagnostic paths).
     * Returns null if no evidence is currently available.
     */
    AssociationEvidence getEvidence();

    /** Lightweight evidence record returned by each provider. */
    class AssociationEvidence {
        public final String  providerName;
        public final boolean evidencePresent;
        /** Optional detail string for diagnostics — must not contain PII. */
        public final String  detail;

        public AssociationEvidence(String providerName, boolean evidencePresent, String detail) {
            this.providerName    = providerName;
            this.evidencePresent = evidencePresent;
            this.detail          = detail;
        }
    }
}
