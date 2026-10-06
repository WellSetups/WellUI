package com.wellsetups.wellui.platform.scheduling;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The only Folia linkage boundary. Methods are resolved on public API interfaces, never
 * implementation classes. No Paper class is linked when this adapter is absent.
 */
public final class FoliaSchedulerBridge implements PlatformScheduler {
    private final Plugin plugin;
    private final BukkitSchedulerBridge io;
    private final Object global;
    private final Object regions;
    private final Method entityScheduler;
    private final Method entityExecute;
    private final Method entityDelay;
    private final Method globalExecute;
    private final Method globalRepeat;
    private final Method regionExecute;
    private final Method cancel;
    private final List<Object> tasks = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public FoliaSchedulerBridge(Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.io = new BukkitSchedulerBridge(plugin);
        Class<?> gs = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
        Class<?> rs = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
        Class<?> es = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        Class<?> task = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
        global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
        regions = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
        entityScheduler = Entity.class.getMethod("getScheduler");
        entityExecute = es.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        entityDelay = es.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
        globalExecute = gs.getMethod("execute", Plugin.class, Runnable.class);
        globalRepeat = gs.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
        regionExecute = rs.getMethod("execute", Plugin.class, Location.class, Runnable.class);
        cancel = task.getMethod("cancel");
    }

    public static boolean available() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        }
        catch (ClassNotFoundException absent) { return false; }
    }

    public void entity(Player player, Runnable work, Runnable retired) {
        if (closed) {
            retired.run();
            return;
        }
        Object scheduler = invoke(entityScheduler, player);
        boolean accepted = (boolean) invoke(entityExecute, scheduler, plugin,
                (Runnable) () -> {
                    if (closed) retired.run();
                    else work.run();
                }, retired, 1L);
        if (!accepted) retired.run();
    }
    public AutoCloseable delay(Player player, long ticks, Runnable work, Runnable retired) {
        if (ticks < 1 || ticks > 1200) throw new IllegalArgumentException("logic.delay");
        if (closed) { retired.run();
            return () -> { };
            }
        Object task = invoke(entityDelay, invoke(entityScheduler, player), plugin,
                (Consumer<Object>) ignored -> { if (closed) retired.run();
            else work.run();
            }, retired, ticks);
        if (task == null) { retired.run();
            return () -> { };
            }
        return () -> invoke(cancel, task);
    }
    public void global(Runnable work) {
        if (!closed) invoke(globalExecute, global, plugin, guard(work));
    }
    public void region(Location location, Runnable work) {
        if (!closed) invoke(regionExecute, regions, plugin, location.clone(), guard(work));
    }
    public void repeatGlobal(Runnable work, long ticks) {
        tasks.add(invoke(globalRepeat, global, plugin, (Consumer<Object>) ignored -> guard(work).run(), ticks, ticks));
    }
    public void async(Runnable work) { io.async(work); }
    public boolean folia() { return true; }
    private Runnable guard(Runnable work) { return () -> { if (!closed) work.run();
            };
        }
    public void close() {
        closed = true;
        for (Object task : tasks) invoke(cancel, task);
        tasks.clear();
        // Bukkit cancellation is unsupported on Folia; only stop the IO executor.
        io.closeIo();
    }
    private static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("scheduler.invocation", failure); }
    }
}
