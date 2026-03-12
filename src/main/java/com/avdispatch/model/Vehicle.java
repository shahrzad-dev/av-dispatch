package com.avdispatch.model;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An autonomous vehicle with lock-free, CAS-based state transitions.
 *
 * <p>All mutable fields ({@code state}, {@code location}) are updated atomically,
 * making this class safe for use across multiple dispatch threads without
 * external synchronisation.
 */
public final class Vehicle {

    private final String id;
    /** State is managed via compare-and-swap to prevent lost updates. */
    private final AtomicReference<VehicleState> state;
    /** Location is held in an AtomicReference for volatile-equivalent semantics. */
    private final AtomicReference<Location> location;

    public Vehicle(String id, Location initialLocation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(initialLocation, "initialLocation");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        this.id       = id;
        this.state    = new AtomicReference<>(VehicleState.AVAILABLE);
        this.location = new AtomicReference<>(initialLocation);
    }

    public String getId() { return id; }

    public VehicleState getState() { return state.get(); }

    public Location getLocation() { return location.get(); }

    /**
     * Atomically transitions state from {@code expected} to {@code next} using CAS.
     *
     * @return {@code true} if the transition succeeded; {@code false} if the current
     *         state was not {@code expected} or the transition is not permitted by the FSM.
     */
    public boolean transitionTo(VehicleState expected, VehicleState next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next,     "next");
        if (!expected.canTransitionTo(next)) {
            return false;
        }
        return state.compareAndSet(expected, next);
    }

    /**
     * Force-sets the state without CAS (use only for testing or administrative override).
     * Validates that the transition is permitted by the FSM.
     *
     * @throws IllegalStateException if the transition is not allowed.
     */
    public void forceTransitionTo(VehicleState next) {
        state.updateAndGet(current -> {
            if (!current.canTransitionTo(next)) {
                throw new IllegalStateException(
                    "Illegal transition " + current + " → " + next + " for vehicle " + id);
            }
            return next;
        });
    }

    /**
     * Updates the vehicle's reported GPS location.
     */
    public void updateLocation(Location newLocation) {
        Objects.requireNonNull(newLocation, "newLocation");
        location.set(newLocation);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vehicle v)) return false;
        return id.equals(v.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() {
        return "Vehicle{id='%s', state=%s, location=%s}".formatted(id, state.get(), location.get());
    }
}
