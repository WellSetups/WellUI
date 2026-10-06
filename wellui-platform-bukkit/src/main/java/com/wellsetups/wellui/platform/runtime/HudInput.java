package com.wellsetups.wellui.platform.runtime;

import java.util.UUID;

/** Session-scoped semantic input gate, independent of Bukkit. All mutation is player-owned. */
final class HudInput {
    static final long LIFETIME_NANOS = 120_000_000_000L;
    private final UUID owner;
    private final long opened;
    private final String token = UUID.randomUUID().toString();
    private volatile boolean closed;
    HudInput(UUID owner, long opened) {
        this.owner = owner;
        this.opened = opened;
    }
    String token() { return token; }
    boolean expired(long now) { return closed || now - opened >= LIFETIME_NANOS; }
    boolean accepts(UUID player, String supplied, long now) {
        return !expired(now) && owner.equals(player) && token.equals(supplied);
    }
    void close() { closed = true; }
}
