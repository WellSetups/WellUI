package com.wellsetups.wellui.platform.scheduling;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Bukkit implementation; expensive work is bounded independently of the gameplay scheduler. */
public final class BukkitSchedulerBridge implements PlatformScheduler {
    private final Plugin plugin;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "WellUI-IO");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public BukkitSchedulerBridge(Plugin plugin) { this.plugin = plugin; }
    public void entity(Player player, Runnable work, Runnable retired) {
        if (closed.get()) {
            retired.run();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!closed.get() && player.isOnline()) work.run();
            else retired.run();
        });
    }
    public AutoCloseable delay(Player player, long ticks, Runnable work, Runnable retired) {
        if (ticks < 1 || ticks > 1200) throw new IllegalArgumentException("logic.delay");
        if (closed.get()) { retired.run();
            return () -> { };
            }
        var task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!closed.get() && player.isOnline()) work.run();
            else retired.run();
        }, ticks);
        return task::cancel;
    }
    public void global(Runnable work) {
        if (!closed.get()) plugin.getServer().getScheduler().runTask(plugin, guarded(work));
    }
    public void region(Location location, Runnable work) { global(work); }
    public void repeatGlobal(Runnable work, long ticks) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, guarded(work), ticks, ticks);
    }
    public void async(Runnable work) { io.execute(guarded(work)); }
    public boolean folia() { return false; }
    private Runnable guarded(Runnable work) { return () -> { if (!closed.get()) work.run();
            };
        }
    void closeIo() {
        closed.set(true);
        io.shutdownNow();
    }
    public void close() {
        closeIo();
        plugin.getServer().getScheduler().cancelTasks(plugin);
    }
}
