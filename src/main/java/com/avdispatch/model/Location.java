package com.avdispatch.model;

/**
 * Immutable geographic coordinate with Haversine distance calculation.
 */
public record Location(double latitude, double longitude) {

    private static final double EARTH_RADIUS_KM = 6_371.0;

    public Location {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException(
                "Latitude must be in [-90, 90], got: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException(
                "Longitude must be in [-180, 180], got: " + longitude);
        }
    }

    /**
     * Returns the great-circle distance in kilometres using the Haversine formula.
     */
    public double distanceTo(Location other) {
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double dLat = Math.toRadians(other.latitude - this.latitude);
        double dLon = Math.toRadians(other.longitude - this.longitude);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(lat1) * Math.cos(lat2)
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    @Override
    public String toString() {
        return "(%.6f, %.6f)".formatted(latitude, longitude);
    }
}
