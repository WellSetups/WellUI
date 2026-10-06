package com.wellsetups.wellui.core.screen;

import java.util.UUID;

/** Bounded network-to-owner mailbox. Packets never select a component or an action. */
public final class ScreenInput {
    public record Look(float yaw, float pitch) { }
    public record Sample(Look look, Look click, long received, int scroll) {
        public Sample(Look look, Look click, long received) { this(look, click, received, 0); }
    }
    private final UUID owner;
    private final UUID token = UUID.randomUUID();
    private Look look;
    private Look click;
    private long lastClick;
    private long received;
    private boolean closed;
    private int scroll;
    private final com.wellsetups.wellui.core.control.TextCapture text = new com.wellsetups.wellui.core.control.TextCapture();
    public com.wellsetups.wellui.core.control.TextCapture text() { return text; }
    public synchronized void scroll(int delta) {
        if (!closed) { scroll = Math.max(-4, Math.min(4, scroll + Integer.signum(delta)));
            received++;
        }
    }
    public ScreenInput(UUID owner, float yaw, float pitch) {
        this.owner = owner;
        look = new Look(yaw, pitch);
    }
    public UUID token() { return token; }
    public synchronized boolean owns(UUID player, UUID session) { return !closed && owner.equals(player) && token.equals(session); }
    public synchronized void look(float yaw, float pitch) {
        if (!closed && Float.isFinite(yaw) && Float.isFinite(pitch) && Math.abs(pitch) <= 90) {
            look = new Look(yaw % 360, pitch);
            received++;
        }
    }
    public synchronized void click(long now) {
        if (!closed && click == null && (lastClick == 0 || now - lastClick >= 50_000_000)) {
            click = look;
            lastClick = now;
            received++;
        }
    }
    public synchronized Sample drain() {
        Sample result = new Sample(look, click, received, scroll);
        click = null;
        received = 0;
        scroll = 0;
        return result;
    }
    public synchronized void close() {
        closed = true;
        text.close();
        click = null;
        scroll = 0;
    }
}
