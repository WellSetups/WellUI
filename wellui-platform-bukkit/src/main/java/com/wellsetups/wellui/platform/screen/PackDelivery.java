package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.core.ScreenPackBuilder;
import com.wellsetups.wellui.core.screen.PackRequest;
import com.wellsetups.wellui.core.screen.ExternalPackCandidates;
import com.wellsetups.wellui.platform.config.Messages;
import com.wellsetups.wellui.platform.scheduling.PlatformScheduler;
import java.lang.reflect.Method;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/** Connection-level generation readiness, independent of UI sessions. Never removes another owner's pack. */
public final class PackDelivery implements Listener, AutoCloseable {
    /** Version bridge; readiness/ownership remains in this class regardless of API adapter. */
    public interface Bridge {
        boolean available();
        void send(Player player, UUID id, String url, byte[] sha1, String prompt) throws ReflectiveOperationException;
        UUID id(PlayerResourcePackStatusEvent event) throws ReflectiveOperationException;
    }
    private static final class BukkitBridge implements Bridge {
        private final Method add;
        private final Method eventId;
        BukkitBridge() {
            Method addMethod = null;
            Method idMethod = null;
            try {
                addMethod = Player.class.getMethod("addResourcePack", UUID.class, String.class, byte[].class, String.class, boolean.class);
                idMethod = PlayerResourcePackStatusEvent.class.getMethod("getID");
            } catch (NoSuchMethodException unsupported) { /* Legacy servers retain fallback capabilities. */ }
            add = addMethod;
            eventId = idMethod;
        }
        public boolean available() { return add != null && eventId != null; }
        public void send(Player player, UUID id, String url, byte[] sha1, String prompt) throws ReflectiveOperationException {
            add.invoke(player, id, url, sha1, prompt, false);
        }
        public UUID id(PlayerResourcePackStatusEvent event) throws ReflectiveOperationException { return (UUID) eventId.invoke(event); }
    }
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("wellui:screen-generation".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private record Entry(PackRequest request, ScreenTransport.Viewer viewer, CompletableFuture<Boolean> ready, boolean external) { }
    private final Map<UUID, Entry> states = new ConcurrentHashMap<>();
    private record Waiting(String generation, long deadline, CompletableFuture<Boolean> ready) { }
    private final ExternalPackCandidates candidates = new ExternalPackCandidates();
    private final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();
    private final Bridge bridge;
    private final ScreenAssets assets;
    private final PlatformScheduler scheduler;
    private final Supplier<Messages> messages;
    private volatile boolean closed;
    private long lastMaintenance = System.nanoTime() - 1_000_000_000L;
    public PackDelivery(ScreenAssets assets, PlatformScheduler scheduler, Supplier<Messages> messages) {
        this(assets, scheduler, messages, new BukkitBridge());
    }
    public PackDelivery(ScreenAssets assets, PlatformScheduler scheduler, Supplier<Messages> messages, Bridge bridge) {
        this.assets = assets;
        this.scheduler = scheduler;
        this.messages = messages;
        this.bridge = bridge;
        assets.onReadinessChanged(this::reconcile);
    }
    public boolean available() { return bridge.available() && !closed; }
    public CompletableFuture<Boolean> require(Player player, ScreenPackBuilder.Prepared pack, ScreenTransport.Viewer viewer) {
        if (!assets.contains(pack)) return CompletableFuture.completedFuture(false);
        return require(player, viewer, false);
    }
    public synchronized CompletableFuture<Boolean> require(Player player, ScreenTransport.Viewer viewer, boolean manual) {
        UUID owner = player.getUniqueId();
        var generation = assets.generation();
        if (assets.external()) {
            if (!available()) return CompletableFuture.completedFuture(false);
            if (generation != null && loaded(owner, generation.sha256())) return CompletableFuture.completedFuture(true);
            var prior = waiting.get(owner);
            if (prior != null) return prior.ready();
            if (waiting.size() >= ExternalPackCandidates.MAX_PLAYERS) return CompletableFuture.completedFuture(false);
            var pending = new Waiting(generation == null ? "" : generation.sha256(), System.nanoTime() + 90_000_000_000L, new CompletableFuture<>());
            waiting.put(owner, pending);
            reconcile();
            return pending.ready();
        }
        Entry old = states.get(owner);
        if (closed || !available() || generation == null) return CompletableFuture.completedFuture(false);
        if (old != null && old.request().hash().equals(generation.sha256())) {
            if (old.request().state() == PackRequest.State.LOADED) return CompletableFuture.completedFuture(loaded(owner, generation.sha256()));
            if (!old.ready().isDone()) return old.ready();
            if (!manual) return CompletableFuture.completedFuture(false);
        }
        if (!manual && (assets.config().preload() == com.wellsetups.wellui.platform.config.ScreenConfig.Preload.MANUAL
                || assets.config().preload() == com.wellsetups.wellui.platform.config.ScreenConfig.Preload.EXTERNAL_OWNER)) return CompletableFuture.completedFuture(false);
        String url = assets.url(generation.sha256(), player);
        if (url.isEmpty()) return CompletableFuture.completedFuture(false);
        // The protocol reports UUID, not hash. Do not overlap generations under our stable UUID.
        if (old != null && !old.ready().isDone()) return CompletableFuture.completedFuture(false);
        var request = new PackRequest(owner, PACK_ID, generation.sha256(), System.nanoTime(), old != null);
        var entry = new Entry(request, viewer, new CompletableFuture<>(), false);
        states.put(owner, entry);
        try {
            bridge.send(player, request.id(), url, HexFormat.of().parseHex(generation.sha1()),
                    messages.get().textFor(player.getLocale(), "screen.pack_prompt"));
            player.sendMessage(messages.get().prefixed(player.getLocale(), "screen.pack_sent"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            request.update(owner, request.id(), "FAILED_DOWNLOAD");
            entry.ready().complete(false);
        }
        return entry.ready();
    }
    /** Packet observer supplies immutable UUID/hash only. No URLs, player calls or packet mutation. */
    public synchronized void offered(UUID owner, UUID id, String sha1) {
        if (closed || !assets.external() || owner == null || id == null) return;
        candidates.offered(owner, id, sha1, System.nanoTime());
        reconcile();
    }
    public synchronized void removed(UUID owner, UUID id) {
        if (owner == null) return;
        if (assets.external()) {
            candidates.remove(owner, id);
            if (id == null) cancelWaiting(owner);
            reconcile();
            return;
        }
        Entry entry = states.get(owner);
        if (entry != null && (id == null || entry.request().id().equals(id))) disconnect(owner);
    }
    @EventHandler public void status(PlayerResourcePackStatusEvent event) {
        if (!available()) return;
        UUID id;
        try { id = bridge.id(event); }
        catch (ReflectiveOperationException failure) { return; }
        String state = event.getStatus().name();
        Player player = event.getPlayer();
        scheduler.entity(player, () -> {
            UUID owner = player.getUniqueId();
            if (assets.external()) {
                candidates.status(owner, id, state);
                reconcile();
                return;
            }
            Entry entry = states.get(owner);
            if (entry == null || !entry.request().update(owner, id, state)) return;
            if (entry.request().state() == PackRequest.State.LOADED) entry.ready().complete(true);
            else if (entry.request().state().ordinal() > PackRequest.State.LOADED.ordinal()) entry.ready().complete(false);
        }, () -> { });
    }
    public boolean loaded(UUID owner, String hash) {
        if (closed) return false;
        if (assets.external()) {
            var view = candidates.view(owner, assets.readiness());
            return view.ready() && view.generation().equals(hash);
        }
        Entry entry = states.get(owner);
        var generation = assets.generation();
        return generation != null && generation.sha256().equals(hash) && entry != null && entry.request().hash().equals(hash)
                && (!entry.external() || !assets.integration().verifiedHash(hash).isEmpty()) && entry.request().state() == PackRequest.State.LOADED;
    }
    public String state(UUID owner) {
        if (assets.external()) return candidates.view(owner, assets.readiness()).state().name();
        Entry entry = states.get(owner);
        if (entry == null) return "UNKNOWN";
        var current = assets.generation();
        return current == null || !entry.request().hash().equals(current.sha256()) ? "STALE" : entry.request().state().name();
    }
    public String summary() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        states.values().forEach(entry -> counts.merge(entry.request().state().name(), 1L, Long::sum));
        return counts + (assets.external() ? " external candidates=" + candidates.size() + " waiting=" + waiting.size() : "");
    }
    public boolean inFlight(UUID owner) {
        if (assets.external()) return waiting.containsKey(owner);
        var entry = states.get(owner);
        return entry != null && !entry.ready().isDone();
    }
    public void tick() {
        long now = System.nanoTime();
        if (now - lastMaintenance < 1_000_000_000L) return;
        lastMaintenance = now;
        if (assets.external()) {
            candidates.expire(System.nanoTime(), assets.readiness());
            reconcile();
            return;
        }
        states.values().forEach(entry -> {
            if (entry.request().timeout(System.nanoTime())) entry.ready().complete(false);
        });
    }
    public void cancelWaiting(UUID owner) {
        var pending = waiting.remove(owner);
        if (pending != null) pending.ready().complete(false);
        // Candidate/loaded connection state survives UI cancellation. Never uninstall an external pack.
    }
    public Map<String, Long> readinessCounts() {
        if (assets.external()) return candidates.readinessCounts(assets.readiness());
        Map<String, Long> counts = new java.util.TreeMap<>();
        states.keySet().forEach(owner -> counts.merge(state(owner), 1L, Long::sum));
        return Map.copyOf(counts);
    }
    private synchronized void reconcile() {
        if (closed || !assets.external()) return;
        var provider = assets.readiness();
        waiting.forEach((owner, pending) -> {
            var view = candidates.view(owner, provider);
            boolean changed = !pending.generation().isEmpty() && !pending.generation().equals(provider.generation());
            boolean terminal = !view.generation().isEmpty() && (view.state() == com.wellsetups.wellui.core.screen.PackReadiness.State.DECLINED
                    || view.state() == com.wellsetups.wellui.core.screen.PackReadiness.State.FAILED);
            if (view.ready() || changed || terminal || System.nanoTime() - pending.deadline() >= 0
                    || provider.state() == com.wellsetups.wellui.core.screen.PackReadiness.State.FAILED) {
                if (waiting.remove(owner, pending)) pending.ready().complete(view.ready() && !changed);
            }
        });
    }
    public String diagnostic(UUID owner) {
        if (assets.external()) {
            var provider = assets.readiness();
            var view = candidates.view(owner, provider);
            return "Provider=" + assets.owner() + " | generation=" + provider.generation()
                    + " | provider compiled pack=" + assets.integration().compiledPack() + " | verification=" + provider.state()
                    + " | observed pack UUID=" + view.pack() + " | observed pack hash=" + view.observedHash()
                    + " | client status=" + view.clientStatus() + " | player generation=" + view.generation()
                    + " | SCREEN=" + view.state() + " | pending auto-open=" + waiting.containsKey(owner)
                    + " | reason unavailable=" + (view.ready() ? "NONE" : provider.reason().isEmpty() ? "screen.pack_waiting" : provider.reason());
        }
        var current = assets.generation();
        var entry = states.get(owner);
        String state = state(owner);
        return "owner=" + assets.owner() + " | integration=" + assets.detected() + " | delivery=" + (assets.external() ? "MERGED_EXTERNAL" : assets.config().delivery())
                + " | generation=" + (current == null ? "NONE" : current.id()) + " | hash=" + (current == null ? "NONE" : current.sha256())
                + " | pack UUID=" + (entry == null ? assets.external() ? "NOT_OBSERVED" : PACK_ID : entry.request().id()) + " | player status=" + state
                + " | player loaded generation=" + (entry != null && entry.request().state() == PackRequest.State.LOADED ? entry.request().hash() : "NONE")
                + " | resend=" + (assets.external() ? "NEVER: external owner" : state.equals("LOADED") ? "NO: generation already loaded"
                : entry != null && !entry.ready().isDone() ? "NO: offer in flight" : entry != null && entry.request().hash().equals(current == null ? "" : current.sha256())
                ? "NO: terminal offer; manual retry required" : "required generation not loaded");
    }
    public void disconnect(UUID owner) {
        candidates.remove(owner, null);
        cancelWaiting(owner);
        Entry entry = states.remove(owner);
        if (entry != null) entry.ready().complete(false);
    }
    private void remove(UUID owner, Entry entry) {
        if (!states.remove(owner, entry)) return;
        entry.ready().complete(false);
        if (!entry.external()) entry.viewer().removePack(entry.request().id());
    }
    public synchronized void close() {
        closed = true;
        waiting.values().forEach(pending -> pending.ready().complete(false));
        waiting.clear();
        candidates.clear();
        for (var entry : states.entrySet()) {
            try { remove(entry.getKey(), entry.getValue()); }
            catch (RuntimeException ignored) { /* Disconnected channels cannot retain client entity/pack state. */ }
        }
        states.clear();
    }
}
