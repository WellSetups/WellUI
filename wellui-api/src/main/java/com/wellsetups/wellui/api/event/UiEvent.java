package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** An extension event dispatched on the associated player's owning server thread. */
public interface UiEvent {
    UUID playerId();
    String documentId();
}
