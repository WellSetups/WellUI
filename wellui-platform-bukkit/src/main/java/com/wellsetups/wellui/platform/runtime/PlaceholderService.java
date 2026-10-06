package com.wellsetups.wellui.platform.runtime;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Optional linkage is isolated here. Resolve/clear run on the player's owning thread. */
public final class PlaceholderService {
    private record Cached(String value, long expires) { }
    private final Method parse;
    private final Consumer<Exception> errors;
    private final Map<UUID, Map<String, Cached>> cache = new ConcurrentHashMap<>();
    private volatile long lastError;

    public PlaceholderService(boolean enabled, Consumer<Exception> errors) throws ReflectiveOperationException {
        this.errors = errors;
        parse = enabled ? Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                .getMethod("setPlaceholders", OfflinePlayer.class, String.class) : null;
    }
    public String resolve(Player player, String text, long refreshMs) {
        if (parse == null || !text.contains("%")) return text;
        Map<String, Cached> values = cache.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        long now = System.nanoTime();
        Cached cached = values.get(text);
        if (cached != null && cached.expires > now) return cached.value;
        try {
            String result = (String) parse.invoke(null, player, text);
            if (values.size() >= 512) values.clear();
            values.put(text, new Cached(result, now + refreshMs * 1_000_000));
            return result;
        } catch (ReflectiveOperationException failure) {
            if (now - lastError > 60_000_000_000L) {
                lastError = now;
                errors.accept(failure);
            }
            return text;
        }
    }
    public boolean enabled() { return parse != null; }
    public void clear(UUID player) { cache.remove(player); }
    public void clear() { cache.clear(); }
}
