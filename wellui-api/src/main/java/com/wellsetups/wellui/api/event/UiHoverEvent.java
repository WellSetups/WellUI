package com.wellsetups.wellui.api.event;

import java.util.UUID;

/** A logical screen cursor entered or exited a component, on the player's owning thread. */
public record UiHoverEvent(UUID playerId, String documentId, String componentId, boolean entered) implements UiEvent { }
