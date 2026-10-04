package dev.referrals;

import dev.referrals.Requests.Added;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsTest {

    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());
    private static final UUID NEW = UUID.nameUUIDFromBytes("new".getBytes());
    private static final long TTL = 120_000;

    @Test
    void aRequestCanBeAcceptedOnceAndOnlyBeforeItRunsOut() {
        Requests requests = new Requests();
        assertEquals(Added.ADDED, requests.add(A, NEW, 0, TTL, 0, 5));

        assertTrue(requests.take(NEW, A, 1000));
        assertFalse(requests.take(NEW, A, 1000), "a second accept finds nothing");

        requests.add(B, NEW, 0, TTL, 0, 5);
        assertFalse(requests.take(NEW, B, TTL + 1), "too late");
    }

    @Test
    void duplicatesCooldownsAndFullListsAreRefused() {
        Requests requests = new Requests();
        assertEquals(Added.ADDED, requests.add(A, NEW, 0, TTL, 10_000, 1));
        assertEquals(Added.COOLDOWN, requests.add(A, UUID.randomUUID(), 5_000, TTL, 10_000, 1));
        assertEquals(Added.DUPLICATE, requests.add(A, NEW, 20_000, TTL, 10_000, 1));
        assertEquals(Added.TARGET_FULL, requests.add(B, NEW, 20_000, TTL, 10_000, 1));
    }

    @Test
    void anExpiredRequestMakesRoomAgain() {
        Requests requests = new Requests();
        requests.add(A, NEW, 0, TTL, 0, 1);
        assertEquals(Added.ADDED, requests.add(B, NEW, TTL + 1, TTL, 0, 1));
    }

    @Test
    void leavingRemovesRequestsInBothDirections() {
        Requests requests = new Requests();
        requests.add(A, NEW, 0, TTL, 0, 5);
        requests.add(B, NEW, 0, TTL, 0, 5);

        requests.forget(A);
        assertEquals(List.of(B), requests.pending(NEW, 1000));

        requests.forget(NEW);
        assertTrue(requests.pending(NEW, 1000).isEmpty());
    }

    @Test
    void sweepReportsWhatRanOut() {
        Requests requests = new Requests();
        requests.add(A, NEW, 0, TTL, 0, 5);
        assertTrue(requests.sweep(1000, 0).isEmpty());
        List<Requests.Expired> expired = requests.sweep(TTL, 0);
        assertEquals(1, expired.size());
        assertEquals(A, expired.get(0).sender());
        assertEquals(NEW, expired.get(0).target());
        assertTrue(requests.pending(NEW, TTL).isEmpty());
    }

    @Test
    void answeringClearsTheOtherRequests() {
        Requests requests = new Requests();
        requests.add(A, NEW, 0, TTL, 0, 5);
        requests.add(B, NEW, 0, TTL, 0, 5);
        requests.take(NEW, A, 1000);
        requests.clearTarget(NEW);
        assertFalse(requests.take(NEW, B, 1000));
    }
}
