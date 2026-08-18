package com.hotel.blescanner.insurance;

/**
 * AssociationEvidenceProvider backed by InsuranceLocationProvider.
 */
public class LocationEvidenceProvider implements AssociationEvidenceProvider {

    private final InsuranceLocationProvider locationProvider;

    public LocationEvidenceProvider(InsuranceLocationProvider locationProvider) {
        this.locationProvider = locationProvider;
    }

    @Override public String getProviderName() { return "LOCATION"; }

    @Override
    public AssociationEvidence getEvidence() {
        boolean available = locationProvider.isLocationAvailable();
        String detail = "gpsPermission=" + locationProvider.isGpsPermissionGranted()
            + " source=" + locationProvider.getLocationSource();
        return new AssociationEvidence(getProviderName(), available, detail);
    }
}
