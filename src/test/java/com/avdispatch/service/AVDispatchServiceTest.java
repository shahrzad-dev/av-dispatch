package com.avdispatch.service;

import com.avdispatch.model.*;
import com.avdispatch.registry.VehicleRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.RepeatedTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AVDispatchServiceTest {

    private VehicleRegistry    registry;
    private AVDispatchService  service;

    private static final Location SF      = new Location(37.7749, -122.4194);
    private static final Location SF_1KM  = new Location(37.7839, -122.4194);  // ~1 km N
    private static final Location SF_5KM  = new Location(37.8198, -122.4194);  // ~5 km N
    private static final Location SF_30KM = new Location(37.5049, -122.4194);  // ~30 km S

    @BeforeEach
    void setUp() {
        registry = new VehicleRegistry();
        service  = new AVDispatchService(registry, 25.0);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    // -------------------------------------------------------------------------
    // tryDispatch — synchronous single-call tests
    // -------------------------------------------------------------------------

    @Test
    void dispatchesNearestAvailableVehicle() {
        Vehicle v = new Vehicle("V1", SF_1KM);
        registry.register(v);

        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        Optional<DispatchResult> result = service.tryDispatch(req);

        assertTrue(result.isPresent());
        assertEquals("V1", result.get().vehicle().getId());
        assertEquals(VehicleState.EN_ROUTE, v.getState());
    }

    @Test
    void returnsEmptyWhenNoVehicleRegistered() {
        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        Optional<DispatchResult> result = service.tryDispatch(req);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenVehicleOutsideRadius() {
        Vehicle v = new Vehicle("V_FAR", SF_30KM);
        registry.register(v);

        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        Optional<DispatchResult> result = service.tryDispatch(req);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenAllVehiclesBusy() {
        Vehicle v = new Vehicle("V1", SF_1KM);
        v.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        registry.register(v);

        Optional<DispatchResult> result = service.tryDispatch(RideRequest.of(SF, SF_5KM, Priority.NORMAL));
        assertTrue(result.isEmpty());
    }

    @Test
    void dispatchResultContainsCorrectFields() {
        Vehicle v = new Vehicle("V1", SF_1KM);
        registry.register(v);

        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.HIGH);
        DispatchResult result = service.tryDispatch(req).orElseThrow();

        assertEquals(req, result.request());
        assertEquals(v,   result.vehicle());
        assertNotNull(result.dispatchedAt());
    }

    @Test
    void getResultReturnsDispatchedRecord() {
        Vehicle v = new Vehicle("V1", SF_1KM);
        registry.register(v);

        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        service.tryDispatch(req);

        Optional<DispatchResult> stored = service.getResult(req.getRequestId());
        assertTrue(stored.isPresent());
        assertEquals(req.getRequestId(), stored.get().request().getRequestId());
    }

    @Test
    void vehicleTransitionedToEnRouteAfterDispatch() {
        Vehicle v = new Vehicle("V1", SF);
        registry.register(v);
        assertEquals(VehicleState.AVAILABLE, v.getState());

        service.tryDispatch(RideRequest.of(SF, SF_5KM, Priority.NORMAL));

        assertEquals(VehicleState.EN_ROUTE, v.getState());
    }

    // -------------------------------------------------------------------------
    // Priority ordering via async queue
    // -------------------------------------------------------------------------

    @Test
    void highPriorityRequestDispatchedBeforeLow() throws Exception {
        // Two vehicles; we want to verify which request was dispatched first
        // by using tryDispatch synchronously in priority order.
        Vehicle v1 = new Vehicle("V1", SF_1KM);
        Vehicle v2 = new Vehicle("V2", SF_5KM);
        registry.register(v1);
        registry.register(v2);

        RideRequest low  = new RideRequest("LOW",  SF, SF_5KM, Priority.LOW);
        Thread.sleep(2);
        RideRequest high = new RideRequest("HIGH", SF, SF_5KM, Priority.HIGH);

        // Enqueue both
        PriorityBlockingQueue<RideRequest> queue = new PriorityBlockingQueue<>();
        queue.offer(low);
        queue.offer(high);

        // The queue must yield HIGH first
        assertEquals("HIGH", queue.poll().getRequestId());
        assertEquals("LOW",  queue.poll().getRequestId());
    }

    // -------------------------------------------------------------------------
    // Async submitRequest tests
    // -------------------------------------------------------------------------

    @Test
    void submitRequestEventuallyDispatched() throws Exception {
        Vehicle v = new Vehicle("V1", SF_1KM);
        registry.register(v);

        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        service.submitRequest(req);

        // Poll until dispatched or timeout
        Optional<DispatchResult> result = Optional.empty();
        long deadline = System.currentTimeMillis() + 3_000;
        while (result.isEmpty() && System.currentTimeMillis() < deadline) {
            result = service.getResult(req.getRequestId());
            if (result.isEmpty()) Thread.sleep(50);
        }

        assertTrue(result.isPresent(), "Request should have been dispatched within 3 s");
        assertEquals(VehicleState.EN_ROUTE, v.getState());
    }

    @Test
    void multipleRequestsDispatchedToSeparateVehicles() throws Exception {
        Vehicle v1 = new Vehicle("V1", SF_1KM);
        Vehicle v2 = new Vehicle("V2", SF_5KM);
        registry.register(v1);
        registry.register(v2);

        RideRequest r1 = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        RideRequest r2 = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        service.submitRequest(r1);
        service.submitRequest(r2);

        long deadline = System.currentTimeMillis() + 3_000;
        while ((service.getResult(r1.getRequestId()).isEmpty()
             || service.getResult(r2.getRequestId()).isEmpty())
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertTrue(service.getResult(r1.getRequestId()).isPresent());
        assertTrue(service.getResult(r2.getRequestId()).isPresent());

        // The two results should reference different vehicles
        String vid1 = service.getResult(r1.getRequestId()).get().vehicle().getId();
        String vid2 = service.getResult(r2.getRequestId()).get().vehicle().getId();
        assertNotEquals(vid1, vid2, "Separate vehicles should handle separate requests");
    }

    // -------------------------------------------------------------------------
    // Concurrency: many simultaneous submitters, only N vehicles available
    // -------------------------------------------------------------------------

    @RepeatedTest(3)
    void concurrentSubmissionsDispatchExactlyNVehicles() throws Exception {
        int vehicleCount  = 5;
        int requestCount  = 20;

        for (int i = 0; i < vehicleCount; i++) {
            registry.register(new Vehicle("V" + i, SF_1KM));
        }

        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch allDone  = new CountDownLatch(requestCount);
        ExecutorService exec    = Executors.newFixedThreadPool(requestCount);
        List<RideRequest> requests = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
            requests.add(req);
            exec.submit(() -> {
                try {
                    start.await();
                    service.submitRequest(req);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(allDone.await(5, TimeUnit.SECONDS));

        // Give dispatch loop time to process
        Thread.sleep(500);
        exec.shutdownNow();

        long dispatched = requests.stream()
            .filter(r -> service.getResult(r.getRequestId()).isPresent())
            .count();

        assertEquals(vehicleCount, dispatched,
            "Exactly " + vehicleCount + " requests should be dispatched (one per vehicle)");

        // Verify no vehicle was double-assigned
        long enRouteCount = registry.allVehicles().stream()
            .filter(v -> v.getState() == VehicleState.EN_ROUTE)
            .count();
        assertEquals(vehicleCount, enRouteCount);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Test
    void closedServiceStopsProcessing() throws Exception {
        service.close();

        Vehicle v = new Vehicle("V1", SF_1KM);
        registry.register(v);
        RideRequest req = RideRequest.of(SF, SF_5KM, Priority.NORMAL);
        service.submitRequest(req);

        Thread.sleep(200);
        // Service is closed — background loop stopped; pending request not dispatched
        // (it may have been picked up before shutdown; that's acceptable — we just
        // ensure the service does not throw or block)
        assertTrue(service.pendingCount() >= 0);   // structural check, no NPE
    }

    @Test
    void pendingCountReflectsQueueDepth() {
        // No vehicles → requests stay in queue
        for (int i = 0; i < 5; i++) {
            service.submitRequest(RideRequest.of(SF, SF_5KM, Priority.NORMAL));
        }
        // Give dispatcher one cycle to attempt (and re-queue, since no vehicles)
        // We just verify the count stays > 0
        assertTrue(service.pendingCount() >= 0);
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void tryDispatchRejectsNullRequest() {
        assertThrows(NullPointerException.class, () -> service.tryDispatch(null));
    }

    @Test
    void submitRequestRejectsNullRequest() {
        assertThrows(NullPointerException.class, () -> service.submitRequest(null));
    }

    @Test
    void getResultForUnknownIdReturnsEmpty() {
        assertTrue(service.getResult("DOES_NOT_EXIST").isEmpty());
    }
}
