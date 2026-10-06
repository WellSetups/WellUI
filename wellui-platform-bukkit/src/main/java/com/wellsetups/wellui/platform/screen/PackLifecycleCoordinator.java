package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.platform.config.ScreenConfig;
import com.wellsetups.wellui.platform.runtime.ScreenRenderer;
import com.wellsetups.wellui.platform.scheduling.PlatformScheduler;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Preload a settled deployment generation, independently of UI opens. No IO or provider rebuild on ticks. */
public final class PackLifecycleCoordinator implements Listener, AutoCloseable {
    private final ScreenAssets assets;
    private final ScreenRenderer screen;
    private final PlatformScheduler scheduler;
    private final java.util.function.LongSupplier clock;
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, String> attempted = new ConcurrentHashMap<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private String observed = "";
    private long changed;
    private volatile boolean closed;
    public PackLifecycleCoordinator(ScreenAssets assets, ScreenRenderer screen, PlatformScheduler scheduler) {
        this(assets, screen, scheduler, System::nanoTime);
    }
    public PackLifecycleCoordinator(ScreenAssets assets, ScreenRenderer screen, PlatformScheduler scheduler, java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.assets = assets;
        this.screen = screen;
        this.scheduler = scheduler;
    }
    public void track(Player player) { players.put(player.getUniqueId(), player); }
    @EventHandler public void joined(PlayerJoinEvent event) { track(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        players.remove(owner);
        attempted.remove(owner);
        queued.remove(owner);
        screen.delivery().disconnect(owner);
    }
    public void tick() {
        if (closed || assets.external() || !assets.deliverable() || assets.config().preload() != ScreenConfig.Preload.ON_JOIN) return;
        var generation = assets.generation();
        if (generation == null) return;
        String hash = generation.sha256();
        if (!observed.equals(hash)) {
            observed = hash;
            changed = clock.getAsLong();
            return;
        }
        if (clock.getAsLong() - changed < 2_000_000_000L) return;
        players.forEach((id, player) -> {
            if (hash.equals(attempted.get(id)) || !queued.add(id)) return;
            scheduler.entity(player, () -> {
                try {
                    if (closed || players.get(id) != player || !player.isOnline() || !screen.preloadEligible(player)) return;
                    if (screen.delivery().inFlight(id)) return;
                    attempted.put(id, hash);
                    screen.preload(player, false);
                } finally { queued.remove(id); }
            }, () -> queued.remove(id));
        });
    }
    public void close() {
        closed = true;
        players.clear();
        attempted.clear();
        queued.clear();
    }
}
