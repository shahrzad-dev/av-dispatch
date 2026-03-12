package com.avdispatch.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class LocationTest {

    // -------------------------------------------------------------------------
    // Construction & validation
    // -------------------------------------------------------------------------

    @Test
    void constructsWithValidCoordinates() {
        Location loc = new Location(37.7749, -122.4194);
        assertEquals(37.7749,    loc.latitude(),  1e-9);
        assertEquals(-122.4194, loc.longitude(), 1e-9);
    }

    @Test
    void rejectsLatitudeTooLow() {
        assertThrows(IllegalArgumentException.class, () -> new Location(-90.001, 0));
    }

    @Test
    void rejectsLatitudeTooHigh() {
        assertThrows(IllegalArgumentException.class, () -> new Location(90.001, 0));
    }

    @Test
    void rejectsLongitudeTooLow() {
        assertThrows(IllegalArgumentException.class, () -> new Location(0, -180.001));
    }

    @Test
    void rejectsLongitudeTooHigh() {
        assertThrows(IllegalArgumentException.class, () -> new Location(0, 180.001));
    }

    @Test
    void acceptsBoundaryValues() {
        assertDoesNotThrow(() -> new Location(-90, -180));
        assertDoesNotThrow(() -> new Location(90,   180));
        assertDoesNotThrow(() -> new Location(0,      0));
    }

    // -------------------------------------------------------------------------
    // Haversine distance
    // -------------------------------------------------------------------------

    @Test
    void distanceToSelfIsZero() {
        Location loc = new Location(48.8566, 2.3522);
        assertEquals(0.0, loc.distanceTo(loc), 1e-9);
    }

    @Test
    void distanceIsSymmetric() {
        Location a = new Location(51.5074, -0.1278);   // London
        Location b = new Location(48.8566,  2.3522);   // Paris
        assertEquals(a.distanceTo(b), b.distanceTo(a), 1e-6);
    }

    /**
     * London → Paris ≈ 340 km (accepted range: ±2 km for the Haversine formula
     * vs. WGS-84 ellipsoid).
     */
    @Test
    void londonToParisApproximateDistance() {
        Location london = new Location(51.5074, -0.1278);
        Location paris  = new Location(48.8566,  2.3522);
        double distKm   = london.distanceTo(paris);
        assertTrue(distKm > 338 && distKm < 345,
            "Expected ~340 km, got " + distKm);
    }

    /** NYC → LA ≈ 3,940 km. */
    @Test
    void newYorkToLosAngeles() {
        Location nyc = new Location(40.7128, -74.0060);
        Location la  = new Location(34.0522, -118.2437);
        double dist  = nyc.distanceTo(la);
        assertTrue(dist > 3930 && dist < 3960,
            "Expected ~3940 km, got " + dist);
    }

    /** One degree of latitude ≈ 111.195 km at the equator. */
    @Test
    void oneDegreeLatitudeEquator() {
        Location a = new Location(0, 0);
        Location b = new Location(1, 0);
        assertEquals(111.195, a.distanceTo(b), 0.1);
    }

    @ParameterizedTest(name = "({0},{1}) → ({2},{3}) ≈ {4} km")
    @CsvSource({
        "0.0,  0.0,   0.0,  1.0,  111.2",   // 1° lon at equator
        "0.0,  0.0,  90.0,  0.0, 10007.5",  // pole
        "37.7749, -122.4194,  34.0522, -118.2437, 559.0"  // SF → LA
    })
    void parameterisedDistances(double lat1, double lon1,
                                double lat2, double lon2,
                                double expectedKm) {
        Location a = new Location(lat1, lon1);
        Location b = new Location(lat2, lon2);
        assertEquals(expectedKm, a.distanceTo(b), 5.0);
    }

    // -------------------------------------------------------------------------
    // Equality (record semantics)
    // -------------------------------------------------------------------------

    @Test
    void equalLocationsAreEqual() {
        assertEquals(new Location(10, 20), new Location(10, 20));
    }

    @Test
    void differentLocationsAreNotEqual() {
        assertNotEquals(new Location(10, 20), new Location(10, 21));
    }
}
