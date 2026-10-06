package com.wellsetups.wellui.platform.scheduling;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Thread ownership boundary. Async work must never access mutable Bukkit state. */
public interface PlatformScheduler extends AutoCloseable {
    void entity(Player player, Runnable work, Runnable retired);
    default AutoCloseable delay(Player player, long ticks, Runnable work, Runnable retired) {
        throw new UnsupportedOperationException("scheduler.delay_unavailable");
    }
    void global(Runnable work);
    void region(Location location, Runnable work);
    void repeatGlobal(Runnable work, long ticks);
    void async(Runnable work);
    boolean folia();
    @Override void close();
}
