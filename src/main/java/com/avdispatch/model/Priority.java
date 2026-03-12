package com.avdispatch.model;

/**
 * Ride request priority levels, ordered from highest to lowest urgency.
 * Lower ordinal = higher urgency (EMERGENCY dispatched first).
 */
public enum Priority {
    EMERGENCY,   // medical / safety critical
    HIGH,        // pre-scheduled, time-sensitive
    NORMAL,      // standard on-demand
    LOW;         // low-cost / pooled

    /** Returns true if this priority is more urgent than {@code other}. */
    public boolean isHigherThan(Priority other) {
        return this.ordinal() < other.ordinal();
    }
}
