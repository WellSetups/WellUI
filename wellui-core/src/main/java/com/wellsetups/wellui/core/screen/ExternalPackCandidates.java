package com.wellsetups.wellui.core.screen;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Bounded connection metadata only; no player, URL, IO, or trust before exact verified SHA-1 matching. */
public final class ExternalPackCandidates {
    public static final int MAX_PLAYERS = 1024;
    public static final int MAX_PACKS_PER_PLAYER = 4;
    public static final long PENDING_TTL = 600_000_000_000L;
    public record View(UUID pack, String observedHash, String clientStatus, String generation, PackReadiness.State state) {
        public boolean ready() { return state == PackReadiness.State.VERIFIED_LOADED; }
    }
    private record Candidate(String hash, PackRequest request, long observedAt) { }
    private final Map<UUID, LinkedHashMap<UUID, Candidate>> players = new LinkedHashMap<>();

    public synchronized void offered(UUID owner, UUID id, String hash, long now) {
        if (owner == null || id == null) return;
        String normalized = hash != null && hash.matches("(?i)[0-9a-f]{40}") ? hash.toLowerCase(Locale.ROOT) : "";
        var packs = players.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        boolean reused = packs.remove(id) != null;
        packs.put(id, new Candidate(normalized, new PackRequest(owner, id, normalized, now, reused), now));
        while (packs.size() > MAX_PACKS_PER_PLAYER) packs.remove(packs.keySet().iterator().next());
        while (players.size() > MAX_PLAYERS) players.remove(players.keySet().iterator().next());
    }
    public synchronized boolean status(UUID owner, UUID id, String status) {
        var packs = players.get(owner);
        var candidate = packs == null ? null : packs.get(id);
        return candidate != null && candidate.request().update(owner, id, status);
    }
    public synchronized View view(UUID owner, PackReadiness.Provider provider) {
        var packs = players.get(owner);
        Candidate chosen = null;
        UUID pack = null;
        if (packs != null) for (var entry : packs.entrySet()) {
            var candidate = entry.getValue();
            boolean matching = provider.verified() && candidate.hash().equals(provider.sha1());
            boolean previousMatch = chosen != null && provider.verified() && chosen.hash().equals(provider.sha1());
            if (chosen == null || matching || !previousMatch) {
                if (previousMatch && matching && chosen.request().state() == PackRequest.State.LOADED
                        && candidate.request().state() != PackRequest.State.LOADED) continue;
                chosen = candidate;
                pack = entry.getKey();
            }
        }
        if (chosen == null) return new View(null, "", "NOT_OBSERVED", "", provider.verified()
                ? PackReadiness.State.VERIFIED : provider.state());
        var client = chosen.request().state();
        boolean compatible = provider.verified() && chosen.hash().equals(provider.sha1());
        PackReadiness.State state;
        if (client == PackRequest.State.DECLINED) state = PackReadiness.State.DECLINED;
        else if (client == PackRequest.State.FAILED || client == PackRequest.State.TIMED_OUT) state = PackReadiness.State.FAILED;
        else if (!provider.verified()) state = PackReadiness.State.PENDING_EXTERNAL_VERIFICATION;
        else if (!compatible) state = PackReadiness.State.STALE;
        else state = switch (client) {
            case SENT -> PackReadiness.State.OFFERED;
            case ACCEPTED -> PackReadiness.State.ACCEPTED;
            case DOWNLOADED -> PackReadiness.State.DOWNLOADED;
            case LOADED -> PackReadiness.State.VERIFIED_LOADED;
            default -> PackReadiness.State.FAILED;
        };
        return new View(pack, chosen.hash(), client.name(), compatible ? provider.generation() : "", state);
    }
    public synchronized void expire(long now, PackReadiness.Provider provider) {
        players.values().forEach(packs -> packs.values().removeIf(candidate -> now - candidate.observedAt() >= PENDING_TTL
                && candidate.request().state() != PackRequest.State.LOADED));
        players.values().removeIf(Map::isEmpty);
    }
    public synchronized void remove(UUID owner, UUID pack) {
        if (pack == null) players.remove(owner);
        else {
            var packs = players.get(owner);
            if (packs != null) {
                packs.remove(pack);
                if (packs.isEmpty()) players.remove(owner);
            }
        }
    }
    public synchronized int size() { return players.values().stream().mapToInt(Map::size).sum(); }
    public synchronized Map<String, Long> readinessCounts(PackReadiness.Provider provider) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        players.keySet().forEach(owner -> counts.merge(view(owner, provider).state().name(), 1L, Long::sum));
        return Map.copyOf(counts);
    }
    public synchronized void clear() { players.clear(); }
}
