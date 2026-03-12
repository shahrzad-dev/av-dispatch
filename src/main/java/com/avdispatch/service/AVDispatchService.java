package com.avdispatch.service;

import com.avdispatch.model.RideRequest;
import com.avdispatch.model.Vehicle;
import com.avdispatch.model.VehicleState;
import com.avdispatch.registry.VehicleRegistry;

import java.io.Closeable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core dispatch service that matches incoming ride requests to the nearest
 * available vehicle using a priority-ordered queue.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>Callers submit {@link RideRequest}s via {@link #submitRequest}; requests
 *       are held in a {@link PriorityBlockingQueue} so higher-priority / older
 *       requests are processed first.</li>
 *   <li>A background single-thread executor drains the queue, finds the nearest
 *       available vehicle via the {@link VehicleRegistry}, and atomically claims
 *       it with a CAS transition ({@code AVAILABLE → EN_ROUTE}).</li>
 *   <li>If no vehicle is currently available the request is returned to the queue
 *       and retried after a brief back-off, preventing a busy-spin.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All public methods are safe to call from multiple threads concurrently.
 */
public final class AVDispatchService implements Closeable {

    /** Default search radius for vehicle matching (kilometres). */
    public static final double DEFAULT_SEARCH_RADIUS_KM = 20.0;

    /** How long to pause before re-queuing an unmatched request (ms). */
    private static final long RETRY_DELAY_MS = 50;

    private final VehicleRegistry              registry;
    private final double                       searchRadiusKm;
    private final PriorityBlockingQueue<RideRequest> pendingQueue;
    /** requestId → DispatchResult, populated when a match is made. */
    private final ConcurrentHashMap<String, DispatchResult> dispatched;
    private final ExecutorService              dispatchExecutor;
    private volatile boolean                   running;

    public AVDispatchService(VehicleRegistry registry) {
        this(registry, DEFAULT_SEARCH_RADIUS_KM);
    }

    public AVDispatchService(VehicleRegistry registry, double searchRadiusKm) {
        this.registry        = Objects.requireNonNull(registry, "registry");
        this.searchRadiusKm  = searchRadiusKm;
        this.pendingQueue    = new PriorityBlockingQueue<>();
        this.dispatched      = new ConcurrentHashMap<>();
        this.running         = true;
        this.dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "av-dispatch-loop");
            t.setDaemon(true);
            return t;
        });
        this.dispatchExecutor.submit(this::dispatchLoop);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enqueues a ride request for dispatch. Returns immediately; dispatch happens
     * asynchronously on the background thread.
     */
    public void submitRequest(RideRequest request) {
        Objects.requireNonNull(request, "request");
        pendingQueue.offer(request);
    }

    /**
     * Synchronously attempts to dispatch {@code request} to the nearest available
     * vehicle within the configured search radius.
     *
     * <p>This method is useful for testing and for callers that need an immediate
     * result without going through the background queue.
     *
     * @return the dispatch result, or {@link Optional#empty()} if no vehicle is available.
     */
    public Optional<DispatchResult> tryDispatch(RideRequest request) {
        Objects.requireNonNull(request, "request");
        return attempt(request);
    }

    /**
     * Returns the dispatch result for a previously submitted request, or
     * {@link Optional#empty()} if the request has not yet been matched.
     */
    public Optional<DispatchResult> getResult(String requestId) {
        return Optional.ofNullable(dispatched.get(requestId));
    }

    /** Returns the number of requests waiting in the pending queue. */
    public int pendingCount() { return pendingQueue.size(); }

    /** Returns the number of successfully dispatched rides. */
    public int dispatchedCount() { return dispatched.size(); }

    // -------------------------------------------------------------------------
    // Background dispatch loop
    // -------------------------------------------------------------------------

    private void dispatchLoop() {
        while (running) {
            try {
                RideRequest request = pendingQueue.poll(100, TimeUnit.MILLISECONDS);
                if (request == null) continue;

                Optional<DispatchResult> result = attempt(request);
                if (result.isEmpty()) {
                    // No vehicle available — back-off then re-queue
                    Thread.sleep(RETRY_DELAY_MS);
                    if (running) pendingQueue.offer(request);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Core dispatch attempt: find nearest available vehicle, CAS its state,
     * record the result.
     */
    private Optional<DispatchResult> attempt(RideRequest request) {
        Optional<Vehicle> candidate =
            registry.findNearestAvailable(request.getPickup(), searchRadiusKm);

        if (candidate.isEmpty()) return Optional.empty();

        Vehicle vehicle = candidate.get();

        // Atomically claim the vehicle; another thread might have claimed it first
        boolean claimed = vehicle.transitionTo(VehicleState.AVAILABLE, VehicleState.EN_ROUTE);
        if (!claimed) {
            // Race lost — retry with the next candidate (one level of recursion avoided
            // by simply returning empty; the caller/queue will retry)
            return Optional.empty();
        }

        DispatchResult result = new DispatchResult(request, vehicle, Instant.now());
        dispatched.put(request.getRequestId(), result);
        return Optional.of(result);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        running = false;
        dispatchExecutor.shutdown();
        try {
            if (!dispatchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dispatchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dispatchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
