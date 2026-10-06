package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** Cancellable execution boundary, after player authorization and before each execution node. */
public final class UiLogicExecuteEvent implements UiEvent {
    private final UUID playerId;
    private final String documentId;
    private final String event;
    private final String nodeId;
    private boolean cancelled;
    public UiLogicExecuteEvent(UUID playerId, String documentId, String event, String nodeId) {
        this.playerId = playerId;
        this.documentId = documentId;
        this.event = event;
        this.nodeId = nodeId;
    }
    public UUID playerId() { return playerId; }
    public String documentId() { return documentId; }
    public String event() { return event; }
    public String nodeId() { return nodeId; }
    public boolean cancelled() { return cancelled; }
    public void setCancelled(boolean value) { cancelled = value; }
}
