package com.avdispatch.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VehicleTest {

    private static final Location ORIGIN = new Location(0, 0);
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        vehicle = new Vehicle("V1", ORIGIN);
    }

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void initialStateIsAvailable() {
        assertEquals(VehicleState.AVAILABLE, vehicle.getState());
    }

    @Test
    void storesInitialLocation() {
        assertEquals(ORIGIN, vehicle.getLocation());
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> new Vehicle(null, ORIGIN));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new Vehicle("  ", ORIGIN));
    }

    @Test
    void rejectsNullLocation() {
        assertThrows(NullPointerException.class, () -> new Vehicle("V2", null));
    }

    // -------------------------------------------------------------------------
    // CAS-based state transitions
    // -------------------------------------------------------------------------

    @Test
    void availableToEnRoute() {
        assertTrue(vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE));
        assertEquals(VehicleState.EN_ROUTE, vehicle.getState());
    }

    @Test
    void availableToOffline() {
        assertTrue(vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.OFFLINE));
        assertEquals(VehicleState.OFFLINE, vehicle.getState());
    }

    @Test
    void enRouteToInRide() {
        vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        assertTrue(vehicle.transitionTo(VehicleState.EN_ROUTE, VehicleState.IN_RIDE));
        assertEquals(VehicleState.IN_RIDE, vehicle.getState());
    }

    @Test
    void enRouteToCancelledBackToAvailable() {
        vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        assertTrue(vehicle.transitionTo(VehicleState.EN_ROUTE, VehicleState.AVAILABLE));
        assertEquals(VehicleState.AVAILABLE, vehicle.getState());
    }

    @Test
    void inRideToAvailable() {
        vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        vehicle.transitionTo(VehicleState.EN_ROUTE,  VehicleState.IN_RIDE);
        assertTrue(vehicle.transitionTo(VehicleState.IN_RIDE, VehicleState.AVAILABLE));
        assertEquals(VehicleState.AVAILABLE, vehicle.getState());
    }

    @Test
    void offlineToAvailable() {
        vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.OFFLINE);
        assertTrue(vehicle.transitionTo(VehicleState.OFFLINE, VehicleState.AVAILABLE));
        assertEquals(VehicleState.AVAILABLE, vehicle.getState());
    }

    // -------------------------------------------------------------------------
    // CAS failure cases
    // -------------------------------------------------------------------------

    @Test
    void casFailsWhenExpectedStateDoesNotMatch() {
        // Vehicle is AVAILABLE; pretending it is EN_ROUTE should fail
        assertFalse(vehicle.transitionTo(VehicleState.EN_ROUTE, VehicleState.IN_RIDE));
        assertEquals(VehicleState.AVAILABLE, vehicle.getState());
    }

    @Test
    void illegalTransitionReturnsFalse() {
        // AVAILABLE → IN_RIDE is not a valid FSM edge
        assertFalse(vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.IN_RIDE));
        assertEquals(VehicleState.AVAILABLE, vehicle.getState());
    }

    @Test
    void inRideCannotGoDirectlyToEnRoute() {
        vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        vehicle.transitionTo(VehicleState.EN_ROUTE,  VehicleState.IN_RIDE);
        assertFalse(vehicle.transitionTo(VehicleState.IN_RIDE, VehicleState.EN_ROUTE));
    }

    // -------------------------------------------------------------------------
    // forceTransitionTo
    // -------------------------------------------------------------------------

    @Test
    void forceTransitionThrowsOnIllegalEdge() {
        assertThrows(IllegalStateException.class,
            () -> vehicle.forceTransitionTo(VehicleState.IN_RIDE));
    }

    @Test
    void forceTransitionSucceedsOnValidEdge() {
        vehicle.forceTransitionTo(VehicleState.EN_ROUTE);
        assertEquals(VehicleState.EN_ROUTE, vehicle.getState());
    }

    // -------------------------------------------------------------------------
    // Location updates
    // -------------------------------------------------------------------------

    @Test
    void updateLocationChangesLocation() {
        Location newLoc = new Location(1, 1);
        vehicle.updateLocation(newLoc);
        assertEquals(newLoc, vehicle.getLocation());
    }

    @Test
    void updateLocationRejectsNull() {
        assertThrows(NullPointerException.class, () -> vehicle.updateLocation(null));
    }

    // -------------------------------------------------------------------------
    // Concurrency: only one thread should win the CAS race
    // -------------------------------------------------------------------------

    @RepeatedTest(10)
    void onlyOneThreadWinsAvailableToEnRoute() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch ready  = new CountDownLatch(threadCount);
        CountDownLatch start  = new CountDownLatch(1);
        AtomicInteger  wins   = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService exec  = Executors.newFixedThreadPool(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(exec.submit(() -> {
                    ready.countDown();
                    try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE)) {
                        wins.incrementAndGet();
                    }
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> f : futures) {
                try { f.get(5, TimeUnit.SECONDS); } catch (ExecutionException | TimeoutException ignored) {}
            }
        } finally {
            exec.shutdownNow();
        }

        assertEquals(1, wins.get(), "Exactly one thread should claim the vehicle");
        assertEquals(VehicleState.EN_ROUTE, vehicle.getState());
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    void vehiclesWithSameIdAreEqual() {
        Vehicle a = new Vehicle("X", ORIGIN);
        Vehicle b = new Vehicle("X", new Location(1, 1));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void vehiclesWithDifferentIdsAreNotEqual() {
        assertNotEquals(new Vehicle("A", ORIGIN), new Vehicle("B", ORIGIN));
    }
}
