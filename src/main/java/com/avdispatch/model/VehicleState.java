package com.avdispatch.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Finite state machine for an autonomous vehicle.
 *
 * <pre>
 *   OFFLINE ──────────────────────────────────┐
 *      ↑                                      │
 *   AVAILABLE ──► EN_ROUTE ──► IN_RIDE ───────┘
 *      ↑                           │
 *      └───────────────────────────┘  (ride complete)
 *
 *   Any state → OFFLINE  (vehicle pulled from service)
 *   EN_ROUTE  → AVAILABLE (ride cancelled before pickup)
 * </pre>
 */
public enum VehicleState {

    AVAILABLE {
        @Override
        public Set<VehicleState> validTransitions() {
            return EnumSet.of(EN_ROUTE, OFFLINE);
        }
    },
    EN_ROUTE {
        @Override
        public Set<VehicleState> validTransitions() {
            return EnumSet.of(IN_RIDE, AVAILABLE, OFFLINE);
        }
    },
    IN_RIDE {
        @Override
        public Set<VehicleState> validTransitions() {
            return EnumSet.of(AVAILABLE, OFFLINE);
        }
    },
    OFFLINE {
        @Override
        public Set<VehicleState> validTransitions() {
            return EnumSet.of(AVAILABLE);
        }
    };

    /** Returns the set of states this state may legally transition to. */
    public abstract Set<VehicleState> validTransitions();

    /** Returns {@code true} if transitioning to {@code target} is permitted. */
    public boolean canTransitionTo(VehicleState target) {
        return validTransitions().contains(target);
    }
}
