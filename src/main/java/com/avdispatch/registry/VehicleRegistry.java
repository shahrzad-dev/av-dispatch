package com.avdispatch.registry;

import com.avdispatch.model.Location;
import com.avdispatch.model.Vehicle;
import com.avdispatch.model.VehicleState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe vehicle registry backed by a grid-based spatial index.
 *
 * <h3>Grid design</h3>
 * <p>The Earth's surface is divided into cells of {@value #CELL_DEGREES}° in both
 * latitude and longitude (≈ 11 km at the equator). Each vehicle is indexed by the
 * cell that contains its current location, allowing nearby-vehicle searches to
 * examine only a small, bounded set of candidate cells rather than the entire fleet.
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>{@code vehiclesById} — {@link ConcurrentHashMap}, primary source of truth.</li>
 *   <li>{@code grid} — {@link ConcurrentHashMap} of {@link ConcurrentHashMap.KeySetView}
 *       (concurrent sets), permitting concurrent reads and fine-grained writes.</li>
 *   <li>Location updates are sequenced per-vehicle via {@code synchronized(vehicle)}
 *       to keep the grid and the vehicle's own location field consistent.</li>
 * </ul>
 */
public final class VehicleRegistry {

    /** Grid cell size in degrees. 0.1° ≈ 11.1 km (latitude), ≈ 7.8 km (longitude at 45°). */
    static final double CELL_DEGREES = 0.1;

    /** Vehicles indexed by their ID. */
    private final ConcurrentHashMap<String, Vehicle> vehiclesById = new ConcurrentHashMap<>();

    /**
     * Spatial grid: cell key → set of vehicle IDs in that cell.
     * Uses {@link ConcurrentHashMap#newKeySet()} so individual set mutations are thread-safe.
     */
    private final ConcurrentHashMap<String, Set<String>> grid = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers a vehicle and inserts it into the spatial grid.
     *
     * @throws IllegalStateException if a different vehicle with the same ID is already registered.
     */
    public void register(Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        Vehicle existing = vehiclesById.putIfAbsent(vehicle.getId(), vehicle);
        if (existing != null && existing != vehicle) {
            throw new IllegalStateException("Vehicle ID already registered: " + vehicle.getId());
        }
        addToGrid(vehicle.getId(), vehicle.getLocation());
    }

    /**
     * Removes a vehicle from the registry and the spatial grid.
     *
     * @return {@code true} if the vehicle was registered.
     */
    public boolean deregister(String vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId");
        Vehicle removed = vehiclesById.remove(vehicleId);
        if (removed == null) return false;
        removeFromGrid(vehicleId, removed.getLocation());
        return true;
    }

    // -------------------------------------------------------------------------
    // Location updates
    // -------------------------------------------------------------------------

    /**
     * Updates a vehicle's location and moves it to the correct grid cell.
     * Synchronized per-vehicle to make the grid move atomic relative to other
     * threads updating or querying the same vehicle.
     *
     * @throws NoSuchElementException if the vehicle is not registered.
     */
    public void updateLocation(String vehicleId, Location newLocation) {
        Objects.requireNonNull(vehicleId,    "vehicleId");
        Objects.requireNonNull(newLocation, "newLocation");

        Vehicle vehicle = requireVehicle(vehicleId);
        synchronized (vehicle) {
            Location oldLocation = vehicle.getLocation();
            String oldCell = cellKey(oldLocation);
            String newCell = cellKey(newLocation);

            vehicle.updateLocation(newLocation);

            if (!oldCell.equals(newCell)) {
                removeFromGrid(vehicleId, oldLocation);
                addToGrid(vehicleId, newLocation);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Spatial queries
    // -------------------------------------------------------------------------

    /**
     * Returns all vehicles within {@code radiusKm} of {@code center}, in ascending
     * distance order, regardless of state.
     */
    public List<Vehicle> findWithinRadius(Location center, double radiusKm) {
        Objects.requireNonNull(center, "center");
        if (radiusKm <= 0) throw new IllegalArgumentException("radiusKm must be positive");

        return candidateVehicles(center, radiusKm).stream()
            .filter(v -> v.getLocation().distanceTo(center) <= radiusKm)
            .sorted(Comparator.comparingDouble(v -> v.getLocation().distanceTo(center)))
            .collect(Collectors.toList());
    }

    /**
     * Returns the nearest {@link VehicleState#AVAILABLE} vehicle within
     * {@code radiusKm} of {@code pickup}, or {@link Optional#empty()} if none exists.
     *
     * <p>This method deliberately does <em>not</em> perform a CAS transition —
     * the caller (dispatch service) is responsible for atomically claiming the
     * vehicle via {@link Vehicle#transitionTo}.
     */
    public Optional<Vehicle> findNearestAvailable(Location pickup, double radiusKm) {
        Objects.requireNonNull(pickup, "pickup");
        if (radiusKm <= 0) throw new IllegalArgumentException("radiusKm must be positive");

        return candidateVehicles(pickup, radiusKm).stream()
            .filter(v -> v.getState() == VehicleState.AVAILABLE)
            .filter(v -> v.getLocation().distanceTo(pickup) <= radiusKm)
            .min(Comparator.comparingDouble(v -> v.getLocation().distanceTo(pickup)));
    }

    /**
     * Returns all registered vehicles (snapshot, unordered).
     */
    public Collection<Vehicle> allVehicles() {
        return Collections.unmodifiableCollection(vehiclesById.values());
    }

    /** Returns the number of registered vehicles. */
    public int size() { return vehiclesById.size(); }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Set<Vehicle> candidateVehicles(Location center, double radiusKm) {
        // Number of cells to expand in each direction.
        int cellRadius = (int) Math.ceil(radiusKm / (CELL_DEGREES * 111.0)) + 1;

        int centerCellLat = cellIndex(center.latitude());
        int centerCellLon = cellIndex(center.longitude());

        Set<Vehicle> candidates = new HashSet<>();
        for (int dLat = -cellRadius; dLat <= cellRadius; dLat++) {
            for (int dLon = -cellRadius; dLon <= cellRadius; dLon++) {
                String key = (centerCellLat + dLat) + "," + (centerCellLon + dLon);
                Set<String> ids = grid.get(key);
                if (ids == null) continue;
                for (String id : ids) {
                    Vehicle v = vehiclesById.get(id);
                    if (v != null) candidates.add(v);
                }
            }
        }
        return candidates;
    }

    private void addToGrid(String vehicleId, Location loc) {
        grid.computeIfAbsent(cellKey(loc), k -> ConcurrentHashMap.newKeySet())
            .add(vehicleId);
    }

    private void removeFromGrid(String vehicleId, Location loc) {
        Set<String> cell = grid.get(cellKey(loc));
        if (cell != null) cell.remove(vehicleId);
    }

    private Vehicle requireVehicle(String vehicleId) {
        Vehicle v = vehiclesById.get(vehicleId);
        if (v == null) throw new NoSuchElementException("Unknown vehicle: " + vehicleId);
        return v;
    }

    static String cellKey(Location loc) {
        return cellIndex(loc.latitude()) + "," + cellIndex(loc.longitude());
    }

    private static int cellIndex(double degrees) {
        return (int) Math.floor(degrees / CELL_DEGREES);
    }
}
