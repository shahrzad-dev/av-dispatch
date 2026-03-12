package com.avdispatch.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

class RideRequestTest {

    private static final Location A = new Location(0, 0);
    private static final Location B = new Location(1, 1);

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void constructsWithAllFields() {
        RideRequest r = new RideRequest("R1", A, B, Priority.NORMAL);
        assertEquals("R1",           r.getRequestId());
        assertEquals(A,              r.getPickup());
        assertEquals(B,              r.getDropoff());
        assertEquals(Priority.NORMAL, r.getPriority());
        assertNotNull(r.getCreatedAt());
    }

    @Test
    void factoryMethodGeneratesUniqueIds() {
        RideRequest r1 = RideRequest.of(A, B, Priority.NORMAL);
        RideRequest r2 = RideRequest.of(A, B, Priority.NORMAL);
        assertNotEquals(r1.getRequestId(), r2.getRequestId());
    }

    @Test
    void rejectsNullRequestId() {
        assertThrows(NullPointerException.class, () -> new RideRequest(null, A, B, Priority.NORMAL));
    }

    @Test
    void rejectsNullPickup() {
        assertThrows(NullPointerException.class, () -> new RideRequest("R1", null, B, Priority.NORMAL));
    }

    @Test
    void rejectsNullDropoff() {
        assertThrows(NullPointerException.class, () -> new RideRequest("R1", A, null, Priority.NORMAL));
    }

    @Test
    void rejectsNullPriority() {
        assertThrows(NullPointerException.class, () -> new RideRequest("R1", A, B, null));
    }

    // -------------------------------------------------------------------------
    // Priority ordering
    // -------------------------------------------------------------------------

    @Test
    void emergencyBeforeHigh() {
        RideRequest emergency = new RideRequest("E", A, B, Priority.EMERGENCY);
        RideRequest high      = new RideRequest("H", A, B, Priority.HIGH);
        assertTrue(emergency.compareTo(high) < 0, "EMERGENCY should sort before HIGH");
    }

    @Test
    void highBeforeNormal() {
        RideRequest high   = new RideRequest("H", A, B, Priority.HIGH);
        RideRequest normal = new RideRequest("N", A, B, Priority.NORMAL);
        assertTrue(high.compareTo(normal) < 0);
    }

    @Test
    void normalBeforeLow() {
        RideRequest normal = new RideRequest("N", A, B, Priority.NORMAL);
        RideRequest low    = new RideRequest("L", A, B, Priority.LOW);
        assertTrue(normal.compareTo(low) < 0);
    }

    @Test
    void samePriorityOlderRequestComesFirst() throws InterruptedException {
        RideRequest first  = new RideRequest("F", A, B, Priority.NORMAL);
        Thread.sleep(2);                           // guarantee different Instant
        RideRequest second = new RideRequest("S", A, B, Priority.NORMAL);
        assertTrue(first.compareTo(second) < 0, "Earlier request should sort first within same priority");
    }

    @Test
    void compareToIsConsistentWithEquals() {
        RideRequest r1 = new RideRequest("R1", A, B, Priority.HIGH);
        assertEquals(0, r1.compareTo(r1));
    }

    // -------------------------------------------------------------------------
    // PriorityQueue integration
    // -------------------------------------------------------------------------

    @Test
    void priorityQueueDrainsInCorrectOrder() throws InterruptedException {
        PriorityQueue<RideRequest> queue = new PriorityQueue<>();

        RideRequest low       = new RideRequest("L",  A, B, Priority.LOW);
        Thread.sleep(2);
        RideRequest normal1   = new RideRequest("N1", A, B, Priority.NORMAL);
        Thread.sleep(2);
        RideRequest normal2   = new RideRequest("N2", A, B, Priority.NORMAL);
        Thread.sleep(2);
        RideRequest high      = new RideRequest("H",  A, B, Priority.HIGH);
        Thread.sleep(2);
        RideRequest emergency = new RideRequest("E",  A, B, Priority.EMERGENCY);

        // Insert in arbitrary order
        List<RideRequest> all = List.of(normal2, low, emergency, normal1, high);
        queue.addAll(all);

        assertEquals("E",  queue.poll().getRequestId());
        assertEquals("H",  queue.poll().getRequestId());
        assertEquals("N1", queue.poll().getRequestId());
        assertEquals("N2", queue.poll().getRequestId());
        assertEquals("L",  queue.poll().getRequestId());
    }

    @Test
    void sortedListMatchesPriorityOrder() throws InterruptedException {
        RideRequest r1 = new RideRequest("1", A, B, Priority.EMERGENCY);
        Thread.sleep(2);
        RideRequest r2 = new RideRequest("2", A, B, Priority.LOW);
        Thread.sleep(2);
        RideRequest r3 = new RideRequest("3", A, B, Priority.HIGH);

        List<RideRequest> list = new ArrayList<>(List.of(r2, r3, r1));
        Collections.sort(list);

        assertEquals(Priority.EMERGENCY, list.get(0).getPriority());
        assertEquals(Priority.HIGH,      list.get(1).getPriority());
        assertEquals(Priority.LOW,       list.get(2).getPriority());
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Test
    void sameIdEquality() {
        RideRequest r1 = new RideRequest("X", A, B, Priority.NORMAL);
        RideRequest r2 = new RideRequest("X", B, A, Priority.HIGH);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    void differentIdNotEqual() {
        assertNotEquals(
            new RideRequest("X", A, B, Priority.NORMAL),
            new RideRequest("Y", A, B, Priority.NORMAL)
        );
    }
}
