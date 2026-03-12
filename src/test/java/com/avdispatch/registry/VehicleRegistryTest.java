package com.avdispatch.registry;

import com.avdispatch.model.Location;
import com.avdispatch.model.Vehicle;
import com.avdispatch.model.VehicleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VehicleRegistryTest {

    private VehicleRegistry registry;

    // SF downtown area — all within a few km of each other
    private static final Location SF_CENTER  = new Location(37.7749, -122.4194);
    private static final Location SF_NORTH   = new Location(37.7849, -122.4194);  // ~1.1 km N
    private static final Location SF_SOUTH   = new Location(37.7649, -122.4194);  // ~1.1 km S
    private static final Location SF_FAR     = new Location(37.8749, -122.4194);  // ~11 km N

    @BeforeEach
    void setUp() {
        registry = new VehicleRegistry();
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    @Test
    void registerAndRetrieveVehicle() {
        Vehicle v = new Vehicle("V1", SF_CENTER);
        registry.register(v);
        assertEquals(1, registry.size());
        assertTrue(registry.allVehicles().contains(v));
    }

    @Test
    void registerDuplicateIdThrows() {
        Vehicle v1 = new Vehicle("V1", SF_CENTER);
        Vehicle v2 = new Vehicle("V1", SF_NORTH);   // different object, same ID
        registry.register(v1);
        assertThrows(IllegalStateException.class, () -> registry.register(v2));
    }

    @Test
    void registerSameObjectTwiceIsIdempotent() {
        Vehicle v = new Vehicle("V1", SF_CENTER);
        registry.register(v);
        assertDoesNotThrow(() -> registry.register(v));
        assertEquals(1, registry.size());
    }

    @Test
    void deregisterRemovesVehicle() {
        Vehicle v = new Vehicle("V1", SF_CENTER);
        registry.register(v);
        assertTrue(registry.deregister("V1"));
        assertEquals(0, registry.size());
        assertFalse(registry.allVehicles().contains(v));
    }

    @Test
    void deregisterUnknownReturnsFalse() {
        assertFalse(registry.deregister("UNKNOWN"));
    }

    // -------------------------------------------------------------------------
    // Location update
    // -------------------------------------------------------------------------

    @Test
    void updateLocationMovesVehicle() {
        Vehicle v = new Vehicle("V1", SF_CENTER);
        registry.register(v);

        registry.updateLocation("V1", SF_FAR);

        assertEquals(SF_FAR, v.getLocation());
    }

    @Test
    void updateLocationThrowsForUnknownVehicle() {
        assertThrows(NoSuchElementException.class,
            () -> registry.updateLocation("GHOST", SF_CENTER));
    }

    @Test
    void vehicleAppearsInNewCellAfterLocationUpdate() {
        Vehicle v = new Vehicle("V1", SF_CENTER);
        registry.register(v);

        // Move to a location far enough to cross a grid cell boundary (>0.1°)
        Location newLoc = new Location(37.8749, -122.4194);
        registry.updateLocation("V1", newLoc);

        // Should be findable near the new location but not near the old one
        List<Vehicle> nearNew = registry.findWithinRadius(newLoc, 1.0);
        List<Vehicle> nearOld = registry.findWithinRadius(SF_CENTER, 1.0);

        assertTrue(nearNew.contains(v),  "Vehicle should appear near new location");
        assertFalse(nearOld.contains(v), "Vehicle should not appear near old location");
    }

    // -------------------------------------------------------------------------
    // Spatial queries — findWithinRadius
    // -------------------------------------------------------------------------

    @Test
    void findsVehiclesWithinRadius() {
        Vehicle close = new Vehicle("CLOSE", SF_NORTH);   // ~1.1 km
        Vehicle far   = new Vehicle("FAR",   SF_FAR);     // ~11 km
        registry.register(close);
        registry.register(far);

        List<Vehicle> result = registry.findWithinRadius(SF_CENTER, 2.0);
        assertTrue(result.contains(close));
        assertFalse(result.contains(far));
    }

    @Test
    void resultsAreOrderedByDistance() {
        Vehicle near = new Vehicle("NEAR", SF_NORTH);   // ~1.1 km
        Vehicle mid  = new Vehicle("MID",  new Location(37.7959, -122.4194)); // ~2.3 km
        registry.register(near);
        registry.register(mid);

        List<Vehicle> result = registry.findWithinRadius(SF_CENTER, 5.0);
        assertEquals(2, result.size());
        assertEquals("NEAR", result.get(0).getId());
        assertEquals("MID",  result.get(1).getId());
    }

    @Test
    void findWithinRadiusReturnsEmptyWhenNoVehicles() {
        assertTrue(registry.findWithinRadius(SF_CENTER, 10.0).isEmpty());
    }

    @Test
    void findWithinRadiusRejectsNonPositiveRadius() {
        assertThrows(IllegalArgumentException.class,
            () -> registry.findWithinRadius(SF_CENTER, 0));
        assertThrows(IllegalArgumentException.class,
            () -> registry.findWithinRadius(SF_CENTER, -1));
    }

    // -------------------------------------------------------------------------
    // Spatial queries — findNearestAvailable
    // -------------------------------------------------------------------------

    @Test
    void findsNearestAvailableVehicle() {
        Vehicle close = new Vehicle("CLOSE", SF_NORTH);
        Vehicle far   = new Vehicle("FAR",   SF_FAR);
        registry.register(close);
        registry.register(far);

        Optional<Vehicle> result = registry.findNearestAvailable(SF_CENTER, 20.0);
        assertTrue(result.isPresent());
        assertEquals("CLOSE", result.get().getId());
    }

    @Test
    void ignoresNonAvailableVehicles() {
        Vehicle busy = new Vehicle("BUSY", SF_NORTH);
        busy.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        registry.register(busy);

        Optional<Vehicle> result = registry.findNearestAvailable(SF_CENTER, 20.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoVehicleInRadius() {
        Vehicle v = new Vehicle("V1", SF_FAR);
        registry.register(v);

        // search in tiny radius that does not reach SF_FAR
        Optional<Vehicle> result = registry.findNearestAvailable(SF_CENTER, 1.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void prefersCloserAvailableVehicle() {
        Vehicle close  = new Vehicle("CLOSE", SF_NORTH);   // ~1.1 km
        Vehicle farOff = new Vehicle("FAR",   SF_FAR);     // ~11 km
        registry.register(close);
        registry.register(farOff);

        Optional<Vehicle> result = registry.findNearestAvailable(SF_CENTER, 20.0);
        assertEquals("CLOSE", result.get().getId());
    }

    // -------------------------------------------------------------------------
    // Cell key helper
    // -------------------------------------------------------------------------

    @Test
    void cellKeyDeterministic() {
        String k1 = VehicleRegistry.cellKey(new Location(37.7749, -122.4194));
        String k2 = VehicleRegistry.cellKey(new Location(37.7749, -122.4194));
        assertEquals(k1, k2);
    }

    @Test
    void neighbouringLocationsCanShareOrDifferCells() {
        // Both points inside the same 0.1° cell:
        //   lat  37.71 → floor(377.1)=377,    37.79 → floor(377.9)=377   ✓
        //   lon -122.51 → floor(-1225.1)=-1226, -122.59 → floor(-1225.9)=-1226 ✓
        String c1 = VehicleRegistry.cellKey(new Location(37.71, -122.51));
        String c2 = VehicleRegistry.cellKey(new Location(37.79, -122.59));
        assertEquals(c1, c2, "Points in same 0.1° cell should share key");

        // Two points straddling a cell boundary (lat differs by >0.1°)
        String c3 = VehicleRegistry.cellKey(new Location(37.69, -122.40));
        String c4 = VehicleRegistry.cellKey(new Location(37.81, -122.40));
        assertNotEquals(c3, c4, "Points in different cells should differ");
    }

    // -------------------------------------------------------------------------
    // Concurrency
    // -------------------------------------------------------------------------

    @RepeatedTest(5)
    void concurrentRegistrationDoesNotCorruptRegistry() throws InterruptedException {
        int count = 100;
        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch finish  = new CountDownLatch(count);
        ExecutorService exec   = Executors.newFixedThreadPool(10);
        AtomicInteger errors   = new AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    Vehicle v = new Vehicle("V" + idx, new Location(idx * 0.001, 0));
                    registry.register(v);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(finish.await(10, TimeUnit.SECONDS));
        exec.shutdownNow();

        assertEquals(0,     errors.get(),    "No exceptions expected");
        assertEquals(count, registry.size(), "All vehicles should be registered");
    }

    @RepeatedTest(5)
    void concurrentLocationUpdatesRemainConsistent() throws InterruptedException {
        Vehicle v = new Vehicle("V1", SF_CENTER);
        registry.register(v);

        int threads = 20;
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        ExecutorService exec  = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final double lat = 37.77 + i * 0.01;
            exec.submit(() -> {
                try {
                    start.await();
                    registry.updateLocation("V1", new Location(lat, -122.4194));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(finish.await(10, TimeUnit.SECONDS));
        exec.shutdownNow();

        // After all updates, vehicle must still be findable somewhere
        assertNotNull(v.getLocation());
        assertEquals(1, registry.size());
    }
}
