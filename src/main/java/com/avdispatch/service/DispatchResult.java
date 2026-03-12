package com.avdispatch.service;

import com.avdispatch.model.RideRequest;
import com.avdispatch.model.Vehicle;

import java.time.Instant;

/**
 * Immutable record of a successful vehicle-to-ride assignment.
 */
public record DispatchResult(
        RideRequest request,
        Vehicle     vehicle,
        Instant     dispatchedAt
) {
    public DispatchResult {
        java.util.Objects.requireNonNull(request,      "request");
        java.util.Objects.requireNonNull(vehicle,      "vehicle");
        java.util.Objects.requireNonNull(dispatchedAt, "dispatchedAt");
    }

    @Override
    public String toString() {
        return "DispatchResult{requestId='%s', vehicleId='%s', at=%s}"
            .formatted(request.getRequestId(), vehicle.getId(), dispatchedAt);
    }
}
