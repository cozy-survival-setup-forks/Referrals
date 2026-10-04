package dev.referrals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The referral requests waiting for an answer. Only the main thread uses this. */
final class Requests {

    enum Added { ADDED, COOLDOWN, DUPLICATE, TARGET_FULL }

    /** A request that ran out of time. */
    record Expired(UUID sender, UUID target) {
    }

    /** new player -> sender -> the moment the request ends */
    private final Map<UUID, Map<UUID, Long>> byTarget = new HashMap<>();
    private final Map<UUID, Long> lastSent = new HashMap<>();

    Added add(UUID sender, UUID target, long now, long ttlMillis, long cooldownMillis, int maxPending) {
        Long last = lastSent.get(sender);
        if (last != null && now - last < cooldownMillis) return Added.COOLDOWN;

        Map<UUID, Long> pending = byTarget.computeIfAbsent(target, id -> new HashMap<>());
        pending.values().removeIf(end -> end <= now);
        if (pending.containsKey(sender)) return Added.DUPLICATE;
        if (pending.size() >= maxPending) return Added.TARGET_FULL;

        pending.put(sender, now + ttlMillis);
        lastSent.put(sender, now);
        return Added.ADDED;
    }

    /** Removes and returns true if this sender has a request for the target that is still open. */
    boolean take(UUID target, UUID sender, long now) {
        Map<UUID, Long> pending = byTarget.get(target);
        if (pending == null) return false;
        Long end = pending.remove(sender);
        if (pending.isEmpty()) byTarget.remove(target);
        return end != null && end > now;
    }

    /** Everybody with an open request for this player. */
    List<UUID> pending(UUID target, long now) {
        Map<UUID, Long> pending = byTarget.get(target);
        List<UUID> senders = new ArrayList<>();
        if (pending == null) return senders;
        pending.forEach((sender, end) -> {
            if (end > now) senders.add(sender);
        });
        return senders;
    }

    /** Drops every request for this player: they answered one, so the rest are moot. */
    void clearTarget(UUID target) {
        byTarget.remove(target);
    }

    /** A player left: their requests, and the requests for them, are gone. */
    void forget(UUID player) {
        byTarget.remove(player);
        Iterator<Map<UUID, Long>> each = byTarget.values().iterator();
        while (each.hasNext()) {
            Map<UUID, Long> pending = each.next();
            pending.remove(player);
            if (pending.isEmpty()) each.remove();
        }
    }

    /** Removes the requests that ran out and returns them. Also forgets old cooldowns. */
    List<Expired> sweep(long now, long cooldownMillis) {
        List<Expired> expired = new ArrayList<>();
        Iterator<Map.Entry<UUID, Map<UUID, Long>>> each = byTarget.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<UUID, Map<UUID, Long>> entry = each.next();
            entry.getValue().entrySet().removeIf(request -> {
                if (request.getValue() > now) return false;
                expired.add(new Expired(request.getKey(), entry.getKey()));
                return true;
            });
            if (entry.getValue().isEmpty()) each.remove();
        }
        lastSent.values().removeIf(sent -> now - sent >= cooldownMillis);
        return expired;
    }
}
