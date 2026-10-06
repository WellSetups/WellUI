package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** Fired once when a tracked session ends. Server shutdown may clear sessions without callbacks. */
public record UiCloseEvent(UUID playerId, String documentId, Reason reason) implements UiEvent {
    public enum Reason { CLOSED, REPLACED, DISCONNECTED }
}
