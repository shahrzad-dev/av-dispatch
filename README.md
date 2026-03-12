# AV Ride Dispatch System

A Java 21 / Maven implementation of a thread-safe, priority-driven ride dispatch engine for autonomous vehicles.

## Architecture

```
com.avdispatch
├── model
│   ├── Location        – Immutable GPS coordinate; Haversine great-circle distance
│   ├── Priority        – EMERGENCY > HIGH > NORMAL > LOW
│   ├── VehicleState    – FSM: AVAILABLE ↔ EN_ROUTE ↔ IN_RIDE ↔ OFFLINE
│   ├── Vehicle         – CAS-based state machine (AtomicReference)
│   └── RideRequest     – Comparable for priority-queue ordering
├── registry
│   └── VehicleRegistry – Grid-based spatial index (ConcurrentHashMap)
└── service
    ├── AVDispatchService – PriorityBlockingQueue + background dispatch loop
    └── DispatchResult    – Immutable record of a vehicle–ride assignment
```

## Key Design Decisions

### Location & Haversine Distance
`Location` is a Java record (immutable value type). `distanceTo` uses the Haversine formula to compute great-circle distances in kilometres — accurate to within ~0.3% for the distances relevant to ride dispatch.

### Vehicle State Machine
`VehicleState` is an enum where each constant overrides `validTransitions()`, encoding the FSM directly in the type system. Illegal edges are rejected before any CAS attempt reaches `AtomicReference`.

```
AVAILABLE ──► EN_ROUTE ──► IN_RIDE
    ▲               │          │
    └───────────────┴──────────┘
    Any state → OFFLINE → AVAILABLE
```

### CAS-Based State Transitions
`Vehicle.transitionTo(expected, next)` wraps `AtomicReference.compareAndSet`. Under concurrent dispatch, exactly one thread wins the claim per vehicle — no locks required.

### Priority Queue Ordering
`RideRequest` implements `Comparable` with three-level ordering:
1. **Priority** – lower ordinal = higher urgency (EMERGENCY first)
2. **Timestamp** – earlier `createdAt` wins within the same priority band (FIFO)
3. **Request ID** – lexicographic tie-breaker for `Comparable` consistency

### Grid Spatial Index
`VehicleRegistry` divides the Earth into 0.1° × 0.1° cells (~11 km at the equator). Each vehicle is stored in the cell matching its current location. A nearby-vehicle search checks only the surrounding `(2r+1)²` cells, bounding the candidate set independently of total fleet size. Location moves are `synchronized(vehicle)` to keep the grid and the vehicle's own location field consistent.

### Dispatch Loop
`AVDispatchService` runs a single background thread that:
1. Blocks on `PriorityBlockingQueue.poll` (100 ms timeout)
2. Calls `VehicleRegistry.findNearestAvailable` for the highest-priority pending request
3. Atomically claims the vehicle via CAS (`AVAILABLE → EN_ROUTE`)
4. If the CAS fails (race) or no vehicle is available, re-queues the request after a 50 ms back-off

## Getting Started

### Prerequisites
- Java 21
- Maven 3.8+

### Build & Test

```bash
mvn test
```

All 107 tests should pass in under 10 seconds:

```
Tests run: 107, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Run a Quick Dispatch

```java
VehicleRegistry registry = new VehicleRegistry();
registry.register(new Vehicle("AV-01", new Location(37.7749, -122.4194)));

try (AVDispatchService service = new AVDispatchService(registry)) {
    RideRequest req = RideRequest.of(
        new Location(37.7749, -122.4194),   // pickup – SF downtown
        new Location(37.8044, -122.2712),   // dropoff – Oakland
        Priority.HIGH
    );

    service.tryDispatch(req).ifPresent(result ->
        System.out.println("Dispatched " + result.vehicle().getId()
            + " → request " + result.request().getRequestId())
    );
}
```

## Test Coverage

| Test class | Tests | What is covered |
|---|---|---|
| `LocationTest` | 16 | Validation, Haversine accuracy, symmetry, parameterised distances |
| `VehicleTest` | 30 | All FSM edges, CAS failure cases, concurrent claim race (×10) |
| `RideRequestTest` | 15 | Priority ordering, FIFO within band, `PriorityQueue` drain order |
| `VehicleRegistryTest` | 28 | Register/deregister, spatial queries, grid cell moves, concurrent registration (×5), concurrent location updates (×5) |
| `AVDispatchServiceTest` | 18 | Sync dispatch, async queue, priority ordering, multi-vehicle, concurrent submitters (×3), lifecycle |
