package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** Fired after permission checks and before opening; cancellation preserves the existing UI. */
public final class UiOpenEvent implements UiEvent {
    private final UUID playerId;
    private final String documentId;
    private boolean cancelled;

    public UiOpenEvent(UUID playerId, String documentId) {
        this.playerId = playerId;
        this.documentId = documentId;
    }

    public UUID playerId() { return playerId; }
    public String documentId() { return documentId; }
    public boolean cancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
