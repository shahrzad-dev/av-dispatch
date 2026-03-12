package com.avdispatch.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable ride request that imposes a total ordering suitable for use in a
 * {@link java.util.concurrent.PriorityBlockingQueue}.
 *
 * <p>Ordering rules (highest to lowest precedence):
 * <ol>
 *   <li>Priority — EMERGENCY before HIGH before NORMAL before LOW.</li>
 *   <li>Creation timestamp — earlier requests precede later ones (FIFO within priority band).</li>
 *   <li>Request ID — lexicographic tie-breaker to satisfy {@code Comparable} consistency.</li>
 * </ol>
 */
public final class RideRequest implements Comparable<RideRequest> {

    private final String    requestId;
    private final Location  pickup;
    private final Location  dropoff;
    private final Priority  priority;
    private final Instant   createdAt;

    public RideRequest(String requestId, Location pickup, Location dropoff, Priority priority) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.pickup    = Objects.requireNonNull(pickup,    "pickup");
        this.dropoff   = Objects.requireNonNull(dropoff,   "dropoff");
        this.priority  = Objects.requireNonNull(priority,  "priority");
        this.createdAt = Instant.now();
    }

    /** Convenience factory that auto-generates a UUID request ID. */
    public static RideRequest of(Location pickup, Location dropoff, Priority priority) {
        return new RideRequest(UUID.randomUUID().toString(), pickup, dropoff, priority);
    }

    public String   getRequestId() { return requestId; }
    public Location getPickup()    { return pickup;    }
    public Location getDropoff()   { return dropoff;   }
    public Priority getPriority()  { return priority;  }
    public Instant  getCreatedAt() { return createdAt; }

    /**
     * {@inheritDoc}
     *
     * <p>Negative return value means {@code this} should be dequeued <em>before</em> {@code other}.
     */
    @Override
    public int compareTo(RideRequest other) {
        // Lower Priority ordinal = higher urgency → comes first (negative diff)
        int cmp = Integer.compare(this.priority.ordinal(), other.priority.ordinal());
        if (cmp != 0) return cmp;

        // Earlier timestamp → comes first
        cmp = this.createdAt.compareTo(other.createdAt);
        if (cmp != 0) return cmp;

        return this.requestId.compareTo(other.requestId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RideRequest r)) return false;
        return requestId.equals(r.requestId);
    }

    @Override
    public int hashCode() { return requestId.hashCode(); }

    @Override
    public String toString() {
        return "RideRequest{id='%s', priority=%s, pickup=%s, dropoff=%s, createdAt=%s}"
            .formatted(requestId, priority, pickup, dropoff, createdAt);
    }
}
