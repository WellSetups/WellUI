package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** Fired for a validated button activation before its action executes on the next entity tick. */
public final class UiClickEvent implements UiEvent {
    public enum Button { LEFT, RIGHT }
    private final UUID playerId;
    private final String documentId;
    private final String componentId;
    private final Button button;
    private boolean cancelled;

    public UiClickEvent(UUID playerId, String documentId, String componentId, Button button) {
        this.playerId = playerId;
        this.documentId = documentId;
        this.componentId = componentId;
        this.button = button;
    }

    public UUID playerId() { return playerId; }
    public String documentId() { return documentId; }
    public String componentId() { return componentId; }
    public Button button() { return button; }
    public boolean cancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
